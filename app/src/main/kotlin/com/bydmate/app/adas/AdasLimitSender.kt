package com.bydmate.app.adas

import android.content.Context
import android.util.Log
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.gnss.GnssGuard
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Waze's speed limit and cameras sent to the driving computer while driving (#23, the owner's
 * decision of 2026-11-03: always, limit and cameras, a settings switch and a voice one, off by
 * default). The car reads only signs, so it misses a town's entry (50) and exit (90) and keeps the
 * last sign until the next; Waze has those from its map. The parked test showed the car takes the
 * sent limit both as the sign and as the speed-limit assist's limit, so with adaptive cruise on
 * the car follows it as it follows a sign.
 *
 * Every [PERIOD_MS] while the car is on and the switch is on: when Waze shows a limit, the frames
 * "navigating", the limit (and the camera ahead, when Waze shows one) go out. When Waze has no
 * limit (none on the road, or its reading is older than [NavGuidanceHub.SPEED_LIMIT_TIMEOUT_MS]),
 * the switch goes off or the car goes off, the factory map's idle (status 100, no path, no limit or camera) goes out once, for 3 s, and
 * the car is back on its own camera. Every change of what is sent goes to the trip log.
 *
 * While [GnssGuard] finds the position implausible (GPS jamming or spoofing threw it, and Waze's
 * limit and camera distance with it) the same goes as without a Waze limit: one clear, then
 * nothing, and no camera distance at all, until the position is plausible again.
 */
@Singleton
class AdasLimitSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parkedTest: AdasParkedTest,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    internal var gatewayFactory: (Context) -> AdasGateway = { AdasSharedGateway.process(it).lease() }
    internal var snapshot: () -> NavGuidanceHub.Snapshot = { NavGuidanceHub.snapshot() }
    internal var enabled: () -> Boolean = { isEnabled(context) }
    internal var gnssSuspect: () -> Boolean = { GnssGuard.suspect }
    internal var position: () -> RoutePoint? = {
        TrackingService.lastLocation.value?.let { RoutePoint(it.latitude, it.longitude) }
    }

    /** From the tracking service's tick: runs the sending loop while the car is on. */
    fun onTick(carOn: Boolean) {
        if (!carOn) {
            job?.cancel()
            return
        }
        if (enabled() && !parkedTest.status.value.running && job?.isActive != true) {
            job = scope.launch { loop() }
        }
    }

    private suspend fun loop() {
        val session = Session(gatewayFactory(context), position)
        try {
            while (scope.isActive && enabled() && !parkedTest.status.value.running) {
                val paused = gnssSuspect()
                session.send(if (paused) null else framesFor(snapshot()), if (paused) WHY_GNSS else WHY_NO_LIMIT)
                delay(PERIOD_MS)
            }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    /**
     * One stretch of sending: bound lazily (a lease of the process's [AdasSharedGateway]), services
     * started only when a frame needs them. [position]: the car's, for the idle location of a clear.
     */
    internal class Session(
        private val gateway: AdasGateway,
        private val position: () -> RoutePoint? = { null },
        private val log: (String) -> Unit = ::note,
    ) {
        private var bound = false
        private var nextBindMs = 0L
        private var started = false
        private var sending: List<ShadowFrame>? = null

        /** [frames] null: nothing to send, for [whyNone]; what was going out is cleared once. */
        suspend fun send(frames: List<ShadowFrame>?, whyNone: String = WHY_NO_LIMIT) {
            if (frames == null) {
                if (sending != null) clear(whyNone)
                return
            }
            if (!bind()) return
            val rcs = frames.map { gateway.fireEvent(it.topic, it.bytes) }
            if (rcs.any { it != 0 } && !started) startServices(rcs)
            if (frames != sending) log("ADAS live send ${frames.drop(1).joinToString(" | ") { it.fields }} rc=$rcs")
            sending = frames
        }

        /**
         * Ends sending with the factory map's idle. The gateway services stay up: stopping
         * and restarting them (dev.10-15: on every new limit and at the end) left the head unit's
         * ADAS view frozen on 3.11 (the car parked, no lanes or traffic) until the multimedia was
         * restarted. Once started they stay for the multimedia session, as the factory map's do:
         * the unbind lets go of this session's lease only, the shared binding stays.
         */
        suspend fun close() {
            if (sending != null) clear("stopped")
            if (bound) gateway.unbind()
            started = false
            bound = false
        }

        private suspend fun bind(): Boolean {
            if (bound) return true
            val now = System.currentTimeMillis()
            if (now < nextBindMs) return false
            bound = gateway.bind()
            if (!bound) {
                nextBindMs = now + REBIND_MS
                log("ADAS live: SOME/IP gateway unavailable")
            }
            return bound
        }

        private fun startServices(rcs: List<Int>) {
            started = true
            val rc = AdasParkedTest.SERVICES.map { gateway.startService(it) }
            log("ADAS live first frames rc=$rcs, services started rc=$rc")
        }

        /**
         * The idle of [AdasParkedTest.clearingFrames] for [AdasParkedTest.CLEAR_MS], the position
         * read each round (the car may be moving); none while the position is implausible.
         */
        private suspend fun clear(why: String) {
            repeat(AdasParkedTest.CLEAR_ROUNDS) {
                val here = if (why == WHY_GNSS) null else position()
                AdasParkedTest.clearingFrames(here).forEach { gateway.fireEvent(it.topic, it.bytes) }
                delay(AdasParkedTest.CLEAR_PERIOD_MS)
            }
            sending = null
            log("ADAS live clear: $why")
        }
    }

    companion object {
        const val PERIOD_MS = 500L
        private const val REBIND_MS = 30_000L
        internal const val WHY_NO_LIMIT = "no Waze limit"
        internal const val WHY_GNSS = "position implausible (GNSS)"

        private const val PREFS = "adas_limit_sender"
        private const val KEY_ENABLED = "enabled"

        /**
         * What goes out for [snap]: nothing (null) without a Waze limit, the car then falls back to
         * its camera; otherwise "navigating", the limit with the camera, and the camera alone in the
         * manoeuvre frame (no manoeuvre: the owner chose limits and cameras).
         */
        fun framesFor(snap: NavGuidanceHub.Snapshot): List<ShadowFrame>? {
            if (snap.speedLimit <= 0) return null
            val camera = if (snap.cameraKind == WazeReportsReader.Kind.CAMERA) snap.cameraDistanceMeters.coerceAtLeast(0) else 0
            return listOf(
                AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE),
                AdasShadowFrames.trafficInfo(snap.speedLimit, camera),
                AdasShadowFrames.actionAndCamera(0, 0, camera),
            )
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
            note("ADAS live ${if (on) "switched on" else "switched off"}")
        }

        private fun note(line: String) {
            Log.i(AdasShadow.TAG, line)
            TripDebugLog.event("ADAS", line)
        }
    }
}
