package com.bydmate.app.adas

import android.content.Context
import android.util.Log
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.hud.HudSomeIpBridge
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
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
 * the switch goes off or the car goes off, the "stopped" status and empty frames go out once and
 * the car is back on its own camera. Every change of what is sent goes to the trip log.
 */
@Singleton
class AdasLimitSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parkedTest: AdasParkedTest,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    internal var gatewayFactory: (Context) -> AdasGateway = { BridgeGateway(HudSomeIpBridge(it)) }
    internal var snapshot: () -> NavGuidanceHub.Snapshot = { NavGuidanceHub.snapshot() }
    internal var enabled: () -> Boolean = { isEnabled(context) }

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
        val session = Session(gatewayFactory(context))
        try {
            while (scope.isActive && enabled() && !parkedTest.status.value.running) {
                session.send(framesFor(snapshot()))
                delay(PERIOD_MS)
            }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    /** One stretch of sending: bound lazily, services started only when a frame needs them. */
    internal class Session(private val gateway: AdasGateway, private val log: (String) -> Unit = ::note) {
        private var bound = false
        private var nextBindMs = 0L
        private var started = false
        private var sending: List<ShadowFrame>? = null

        suspend fun send(frames: List<ShadowFrame>?) {
            if (frames == null) {
                if (sending != null) clear("no Waze limit")
                return
            }
            if (!bind()) return
            val limit = limitOf(frames)
            val previous = sending?.let(::limitOf)
            if (previous != null && previous != limit) restart(previous, limit)
            val rcs = frames.map { gateway.fireEvent(it.topic, it.bytes) }
            if (rcs.any { it != 0 } && !started) startServices(rcs)
            if (frames != sending) log("ADAS live send ${frames.drop(1).joinToString(" | ") { it.fields }} rc=$rcs")
            sending = frames
        }

        /**
         * The car takes only the first limit after navigation starts and ignores later changes
         * (3.11 drive: 90 taken at the start, then 50, 50, 50 and 70 sent, the car stayed on 90).
         * So a new limit goes out the way the first one did: "stopped", the services restarted
         * (when they are ours), then "navigating" with the new limit.
         */
        private suspend fun restart(from: Int, to: Int?) {
            AdasParkedTest.clearingFrames().forEach { gateway.fireEvent(it.topic, it.bytes) }
            delay(RESTART_GAP_MS)
            if (started) {
                AdasParkedTest.SERVICES.forEach { gateway.stopService(it) }
                delay(RESTART_GAP_MS)
                AdasParkedTest.SERVICES.forEach { gateway.startService(it) }
                delay(RESTART_GAP_MS)
            }
            log("ADAS live restart for the new limit $from -> $to (services restarted=$started)")
        }

        private fun limitOf(frames: List<ShadowFrame>): Int? =
            frames.firstOrNull { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }?.fields
                ?.substringAfter("SpdLmtSpeedValue=", "")?.substringBefore(' ')?.toIntOrNull()

        suspend fun close() {
            if (sending != null) clear("stopped")
            if (started) AdasParkedTest.SERVICES.forEach { gateway.stopService(it) }
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

        private suspend fun clear(why: String) {
            repeat(CLEAR_REPEATS) {
                AdasParkedTest.clearingFrames().forEach { gateway.fireEvent(it.topic, it.bytes) }
                delay(PERIOD_MS)
            }
            sending = null
            log("ADAS live clear: $why")
        }
    }

    companion object {
        const val PERIOD_MS = 500L
        private const val CLEAR_REPEATS = 3
        private const val REBIND_MS = 30_000L
        private const val RESTART_GAP_MS = 300L

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
