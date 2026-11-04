package com.bydmate.app.hma

import android.content.Context
import android.util.Log
import com.bydmate.app.R
import com.bydmate.app.data.push.FidPushChannel
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import com.bydmate.app.util.AppStrings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The decisions of the HMA watch without Android: which journal lines a change earns and which
 * warnings are due. [HmaWatch] feeds it and does the logging and the showing.
 *
 * Not thread-safe on its own; [HmaWatch] serializes every call.
 */
class HmaWatchCore(
    private val gate: HmaWarningGate = HmaWarningGate(),
    private val maxLinesPerMinute: Int = MAX_LINES_PER_MINUTE,
) {
    /** What one call produced: journal lines and the warning reasons due now. */
    data class Output(val lines: List<String> = emptyList(), val warn: Set<HmaReason> = emptySet())

    var reading: HmaReading = HmaReading()
        private set

    private var tripKey: Long? = null
    private var carWasOn = false
    private var windowStart = 0L
    private var windowLines = 0
    private var suppressed = 0

    /** One pushed HMA signal (or the pushed high beam lamp). */
    fun onSignal(key: String, intValue: Int, doubleValue: Double, nowMs: Long): Output =
        advance(reading.with(key, intValue, doubleValue), nowMs)

    /**
     * One poll tick: the context fields from the snapshot, trip bookkeeping, and the warning
     * check when [warnEnabled]. A new [trip] (or the car coming on without one) forgets the
     * warnings already shown.
     */
    fun onTick(
        carOn: Boolean,
        trip: Long?,
        highBeam: Int?,
        lightLevel: Int?,
        wiperRelay: Int?,
        autoWipers: Int?,
        speedKph: Int?,
        warnEnabled: Boolean,
        nowMs: Long,
    ): Output {
        if ((trip != null && trip != tripKey) || (carOn && !carWasOn && trip == null)) gate.newTrip()
        if (trip != null) tripKey = trip
        carWasOn = carOn
        val next = reading.copy(
            highBeam = highBeam ?: reading.highBeam,
            lightLevel = lightLevel ?: reading.lightLevel,
            wiperRelay = wiperRelay ?: reading.wiperRelay,
            autoWipers = autoWipers ?: reading.autoWipers,
            speedKph = speedKph,
        )
        val journal = advance(next, nowMs)
        if (!carOn || !warnEnabled) return journal
        val due = gate.update(HmaWarningRule.reasons(reading), nowMs)
        return journal.copy(warn = due)
    }

    private fun advance(next: HmaReading, nowMs: Long): Output {
        val events = HmaJournal.changes(reading, next)
        reading = next
        if (events.isEmpty()) return Output()
        val lines = mutableListOf<String>()
        if (nowMs - windowStart >= MINUTE_MS) {
            if (suppressed > 0) lines += "${HmaJournal.PREFIX} journal suppressed=$suppressed lines in the last minute"
            windowStart = nowMs
            windowLines = 0
            suppressed = 0
        }
        for (event in events) {
            if (windowLines >= maxLinesPerMinute) {
                suppressed++
                continue
            }
            windowLines++
            lines += HmaJournal.line(event, next)
        }
        return Output(lines = lines)
    }

    companion object {
        /** A flapping signal must not flood the log: past this, lines are counted, not written. */
        const val MAX_LINES_PER_MINUTE = 30
        private const val MINUTE_MS = 60_000L
    }
}

/**
 * Watches the automatic high beam (HMA) from the car's own signals, read only: journals every
 * change of its state and of the high beam lamp with the light, rain, wiper and speed context, and
 * shows the driver one quiet overlay «Автодальнє ненадійне — протріть лобове скло перед камерою»
 * when the car says the camera is blocked or the function faulty, when the function is grayed out
 * at night on the move, or when a sensor behind the windscreen reports an error. Each reason at
 * most once per trip. The warning has its own switch, on by default; the journal always runs.
 *
 * Nothing is ever written to the car.
 */
@Singleton
class HmaWatch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fidPush: FidPushChannel,
    private val strings: AppStrings,
) {
    private val core = HmaWatchCore()
    private val showScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Follows the push channel: the HMA signals and the high beam lamp, as they arrive. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            fidPush.events.collect { event ->
                val key = fidPush.hmaSignalFor(event.fid)?.key
                    ?: fidPush.fieldFor(event.fid)?.takeIf { it == HmaReading.HIGH_BEAM_FIELD }
                    ?: return@collect
                val out = synchronized(core) {
                    core.onSignal(key, event.intValue, event.doubleValue, System.currentTimeMillis())
                }
                emit(out)
            }
        }
    }

    fun onTick(carOn: Boolean, data: DiParsData?, trip: Long?, nowMs: Long = System.currentTimeMillis()) {
        if (data == null) return
        val out = synchronized(core) {
            core.onTick(
                carOn = carOn,
                trip = trip,
                highBeam = data.lightHigh,
                lightLevel = data.lightLevel,
                wiperRelay = data.wiperRelay,
                autoWipers = data.autoWipers,
                speedKph = data.speed,
                warnEnabled = isEnabled(context),
                nowMs = nowMs,
            )
        }
        emit(out)
    }

    private fun emit(out: HmaWatchCore.Output) {
        out.lines.forEach { line ->
            Log.i(HmaJournal.TAG, line)
            TripDebugLog.event(HmaJournal.TAG, line)
        }
        if (out.warn.isNotEmpty()) warn(out.warn)
    }

    private fun warn(reasons: Set<HmaReason>) {
        val codes = reasons.joinToString(",") { it.code }
        showScope.launch {
            val title = strings.get(R.string.hma_warning_title)
            val text = reasons.joinToString("\n") { strings.get(reasonText(it)) }
            val shown = runCatching { OverlayNotificationManager.show(context, title, text) }.getOrDefault(false)
            val line = "${HmaJournal.PREFIX} warn reasons=$codes shown=${if (shown) "overlay" else "no (overlay not allowed)"}"
            Log.i(HmaJournal.TAG, line)
            TripDebugLog.event(HmaJournal.TAG, line)
        }
    }

    companion object {
        private const val PREFS = "hma_watch"
        private const val KEY_WARN = "warn_enabled"

        /** The warning switch; on unless the owner turned it off. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WARN, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WARN, on).apply()
            Log.i(HmaJournal.TAG, "${HmaJournal.PREFIX} warning ${if (on) "enabled" else "disabled"}")
        }

        fun reasonText(reason: HmaReason): Int = when (reason) {
            HmaReason.FAULT -> R.string.hma_reason_fault
            HmaReason.CAMERA_BLOCKED -> R.string.hma_reason_camera_blocked
            HmaReason.SUPPRESSED -> R.string.hma_reason_suppressed
            HmaReason.SENSOR_ERROR -> R.string.hma_reason_sensor_error
        }
    }
}
