package com.bydmate.app.data.automation

import android.content.Context
import android.util.Log
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Масаж до кінця поїздки» (#3): the stock seat massage switches itself off 15 minutes after its
 * last change (Tang L recorder 2026-10-25: 14:56–14:58 every time, driver, passenger and rear).
 * While the car is on, this turns the same mode and intensity back on when that timer — and not
 * the driver — ended it.
 *
 * Timer and hand look the same on the car: both drop the mode to 1 and pulse the Bodywork
 * massage switch with 2. What tells them apart is time: the timer fires ~15 minutes after the
 * last change, a hand is almost always sooner. So an off at least [TIMER_MIN_MS] after the last
 * change is the timer; anything sooner is the driver, and the massage stays off.
 *
 * Reads the front seats' mode and level every [READ_INTERVAL_MS] on its own (FidMap extra, not
 * the poll batch); writes mode then intensity as one CommandTranslator command ([massageCommand])
 * (dev=1023 carve-out).
 */
@Singleton
class MassageKeeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
    private val vehicleApi: VehicleApi,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Driver, passenger. */
    private val seats = arrayOf(MassageSeatState(), MassageSeatState())
    private var lastReadAt = 0L

    /** Called on every poll tick from TrackingService. */
    suspend fun onTick(data: DiParsData, now: Long = System.currentTimeMillis()) {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        if (data.powerState?.let { it >= 1 } != true) {
            seats.fill(MassageSeatState())
            return
        }
        if (now - lastReadAt < READ_INTERVAL_MS) return
        lastReadAt = now
        val readings = helper.readBatch(batchItems())?.takeIf { it.size == FIELDS.size } ?: return
        for (i in seats.indices) {
            val (modeStatus, mode) = readings[2 * i]
            val (levelStatus, level) = readings[2 * i + 1]
            val step = massageStep(
                seats[i],
                mode.takeIf { modeStatus == 0 },
                level.takeIf { levelStatus == 0 },
                now,
            )
            seats[i] = step.state
            val (restoreMode, restoreLevel) = step.restore ?: continue
            val driver = i == 0
            val result = vehicleApi.dispatch(massageCommand(driver, restoreMode, restoreLevel))
            Log.i(
                TAG,
                "massage back on: seat=${if (driver) "driver" else "passenger"} mode=$restoreMode " +
                    "level=$restoreLevel attempt=${step.state.attempts} ok=${result.isSuccess}",
            )
        }
    }

    private fun batchItems(): List<BatchReadItem> = FIELDS.map {
        val address = FidAddresses.of(it)
        BatchReadItem(TX_GET_INT, address.device, address.fid)
    }

    companion object {
        private const val TAG = "MassageKeeper"
        const val PREFS_NAME = "massage_keeper"
        const val KEY_ENABLED = "enabled"

        /** Mode then level, driver then passenger: the order [onTick] reads the reply in. */
        private val FIELDS = listOf("massageModeFl", "massageLevelFl", "massageModeFr", "massageLevelFr")
        private const val TX_GET_INT = 5

        const val READ_INTERVAL_MS = 5_000L

        /** The stock timer ends a massage 14:56–14:58 after its last change; a hand is sooner. */
        const val TIMER_MIN_MS = 14 * 60_000L + 30_000L

        /** Writes that did not bring the massage back before giving up for this cycle. */
        const val MAX_ATTEMPTS = 3

        /** The CommandTranslator massage command: mode, then intensity, on one seat. */
        fun massageCommand(driver: Boolean, mode: Int, level: Int): String =
            "${if (driver) "主驾" else "副驾"}按摩模式${mode}强度$level"
    }
}

/**
 * What one seat's massage was last seen doing. [mode] 1 = off, 2..6 = a program; [level] 0 = off,
 * 1..3. [changedAt] is when mode or level last changed while on — the stock timer counts from
 * there. [attempts] counts restores since the massage was last seen on.
 */
internal data class MassageSeatState(
    val mode: Int? = null,
    val level: Int? = null,
    val changedAt: Long = 0L,
    val attempts: Int = 0,
)

internal data class MassageStep(val state: MassageSeatState, val restore: Pair<Int, Int>? = null)

private fun isOn(mode: Int?, level: Int?): Boolean = mode in 2..6 && level in 1..3

/**
 * One read of one seat. Pure, so the timer/hand split is unit-tested. A failed read changes
 * nothing. An off that comes [MassageKeeper.TIMER_MIN_MS] or more after the last change asks for
 * the remembered mode and level back, up to [MassageKeeper.MAX_ATTEMPTS] times; a sooner off is
 * the driver's and is remembered as off.
 */
internal fun massageStep(state: MassageSeatState, mode: Int?, level: Int?, now: Long): MassageStep {
    if (mode == null || level == null) return MassageStep(state)
    val wasOn = isOn(state.mode, state.level)
    if (isOn(mode, level)) {
        // Back on after a restore counts as a change: the stock timer starts over from the write.
        val changed = !wasOn || state.attempts > 0 || mode != state.mode || level != state.level
        return MassageStep(MassageSeatState(mode, level, if (changed) now else state.changedAt, attempts = 0))
    }
    val timer = wasOn && now - state.changedAt >= MassageKeeper.TIMER_MIN_MS
    if (timer && state.attempts < MassageKeeper.MAX_ATTEMPTS) {
        // Keep the on-state: the next read sees the massage back on (a change), or tries again.
        return MassageStep(state.copy(attempts = state.attempts + 1), restore = state.mode!! to state.level!!)
    }
    return MassageStep(MassageSeatState(mode, level, now))
}
