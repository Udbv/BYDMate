package com.bydmate.app.adas

import android.content.Context
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.hud.HudSomeIpBridge
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The SOME/IP gateway as the parked test uses it; [HudSomeIpBridge] on the car, a fake in tests. */
interface AdasGateway {
    suspend fun bind(): Boolean
    fun startService(serviceId: Long): Int
    fun stopService(serviceId: Long): Int
    fun fireEvent(topic: Long, payload: ByteArray): Int
    fun unbind()
}

/**
 * ADAS test on a parked car (issue #23, step 2, the owner's go-ahead of 2026-10-31): for
 * [DURATION_MS] it really sends the speed limit and a camera the way the factory map does
 * (NavigationStatus "navigating", TrafficInfo, naviActionAndCamera, every [PERIOD_MS]), so the
 * owner can see on the car's screens what the driving computer makes of them, and reads back
 * what the car's assistance reports meanwhile.
 *
 * Only in P at standstill: checked before the start and on every frame; the first frame that
 * finds the car not parked (or not answering) ends the test. Every end, whatever caused it, sends
 * the "stopped" status and empty limit and camera frames, then stops the gateway services it had
 * to start: the driving computer is left with nothing of ours. Nothing of this runs on its own.
 */
@Singleton
class AdasParkedTest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
) {
    data class Status(
        val running: Boolean = false,
        val secondsLeft: Int = 0,
        val limitKph: Int = 0,
        val cameraM: Int = 0,
        val car: AdasCarReading? = null,
        /** How the last test ended, in words; null before the first. */
        val result: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val running = AtomicBoolean(false)

    internal var data: () -> DiParsData? = { TrackingService.lastData.value }
    internal var gatewayFactory: (Context) -> AdasGateway = { BridgeGateway(HudSomeIpBridge(it)) }
    internal var readCar: suspend () -> AdasCarReading? = {
        helper.readBatch(AdasCarReading.ITEMS)?.let { AdasCarReading.decode(it) }
    }
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Starts the test; the refusal in words when the car is not parked or a test runs already. */
    fun start(scope: CoroutineScope, limitKph: Int, cameraM: Int): String? {
        refusal(data())?.let { return it }
        if (!running.compareAndSet(false, true)) return "тест уже йде"
        _status.value = Status(running = true, secondsLeft = (DURATION_MS / SECOND_MS).toInt(), limitKph = limitKph, cameraM = cameraM)
        scope.launch(Dispatchers.IO) { run(limitKph, cameraM) }
        return null
    }

    internal suspend fun run(limitKph: Int, cameraM: Int) {
        val gateway = gatewayFactory(context)
        val fires = HashMap<Int, Int>()
        // Whatever ends the run before it says otherwise: leaving the settings screen cancels it
        // (2026-10-31 17:03 reported «gateway unavailable» for a run that sent all its frames).
        var outcome = "перервано"
        var started = false
        try {
            val before = readCar()
            note("parked test start limit=$limitKph camera=${cameraM}m car before: ${before?.describe() ?: "no answer"}")
            if (!gateway.bind()) {
                outcome = "шлюз SOME/IP недоступний"
                return
            }
            val sending = sendingFrames(limitKph, cameraM)
            // The factory map offers these services itself; ours are started only when a frame
            // does not go out without them, and only those are stopped at the end.
            val probe = sending.map { gateway.fireEvent(it.topic, it.bytes) }
            note("parked test first frames rc=$probe")
            if (probe.any { it != 0 }) {
                started = true
                SERVICES.forEach { note("parked test startService(${HudSomeIpBridge.hex(it)}) rc=${gateway.startService(it)}") }
            }
            outcome = sendLoop(gateway, sending, fires, before)?.let { "зупинено: $it" } ?: "завершено"
        } finally {
            withContext(NonCancellable) { finish(gateway, started, outcome, fires) }
        }
    }

    /** The frames every [PERIOD_MS] until [DURATION_MS] is over; the refusal that cut it short, if any. */
    private suspend fun sendLoop(
        gateway: AdasGateway,
        sending: List<ShadowFrame>,
        fires: MutableMap<Int, Int>,
        before: AdasCarReading?,
    ): String? {
        val end = nowMs() + DURATION_MS
        var nextRead = 0L
        var lastCar = before
        while (nowMs() < end) {
            refusal(data())?.let { return it }
            sending.forEach { f -> gateway.fireEvent(f.topic, f.bytes).let { rc -> fires[rc] = (fires[rc] ?: 0) + 1 } }
            if (nowMs() >= nextRead) {
                nextRead = nowMs() + SECOND_MS
                val car = readCar()
                if (car != lastCar) note("parked test car: ${car?.describe() ?: "no answer"}")
                lastCar = car
                val left = ((end - nowMs()) / SECOND_MS).toInt().coerceAtLeast(0)
                _status.value = _status.value.copy(secondsLeft = left, car = car)
            }
            delay(PERIOD_MS)
        }
        return null
    }

    /** Whatever ended the test: empty limit and camera, "stopped", our services down. */
    private suspend fun finish(gateway: AdasGateway, started: Boolean, outcome: String, fires: Map<Int, Int>) {
        repeat(CLEAR_REPEATS) {
            clearingFrames().forEach { f -> gateway.fireEvent(f.topic, f.bytes) }
            delay(PERIOD_MS)
        }
        if (started) SERVICES.forEach { gateway.stopService(it) }
        gateway.unbind()
        val after = readCar()
        note("parked test end: $outcome fires=$fires car after: ${after?.describe() ?: "no answer"}")
        _status.value = _status.value.copy(running = false, secondsLeft = 0, car = after, result = outcome)
        running.set(false)
    }

    private fun note(line: String) {
        Log.i(AdasShadow.TAG, line)
        TripDebugLog.event("ADAS", line)
    }

    companion object {
        const val DURATION_MS = 30_000L
        const val PERIOD_MS = 200L
        private const val SECOND_MS = 1_000L
        private const val CLEAR_REPEATS = 5
        private const val GEAR_P = 1

        /** The gateway services of the three topics: 0x0007 and 0x8202. */
        val SERVICES: List<Long> = listOf(AdasShadowFrames.TOPIC_NAVI_STATUS, AdasShadowFrames.TOPIC_ACTION_CAMERA)
            .map(HudSomeIpBridge::serviceIdFor)

        /** Why the car may not be sent to now, or null when it is parked: P, standing, answering. */
        fun refusal(d: DiParsData?): String? = when {
            d == null -> "немає даних від машини"
            d.gear != GEAR_P -> "не на P"
            d.speed != 0 -> "машина рухається"
            else -> null
        }

        fun sendingFrames(limitKph: Int, cameraM: Int): List<ShadowFrame> = listOf(
            AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE),
            AdasShadowFrames.trafficInfo(limitKph, cameraM),
            AdasShadowFrames.actionAndCamera(0, 0, cameraM),
        )

        fun clearingFrames(): List<ShadowFrame> = listOf(
            AdasShadowFrames.trafficInfo(0, 0),
            AdasShadowFrames.actionAndCamera(0, 0, 0),
            AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED),
        )
    }
}

private class BridgeGateway(private val bridge: HudSomeIpBridge) : AdasGateway {
    override suspend fun bind(): Boolean = bridge.bind()
    override fun startService(serviceId: Long): Int = bridge.startService(serviceId)
    override fun stopService(serviceId: Long): Int = bridge.stopService(serviceId)
    override fun fireEvent(topic: Long, payload: ByteArray): Int = bridge.fireEvent(topic, payload)
    override fun unbind() = bridge.unbind()
}
