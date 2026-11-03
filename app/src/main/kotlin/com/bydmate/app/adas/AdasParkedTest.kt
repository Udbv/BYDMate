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
        /** The change test: which variant runs now (#23). */
        val variant: String? = null,
        /** How the last test ended, in words; null before the first. */
        val result: String? = null,
        /** The running test offers a route (#53) rather than a limit and camera. */
        val route: Boolean = false,
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

    /** Whether this run started the gateway services, and so stops them at the end. */
    @Volatile private var servicesStarted = false

    /** Starts the test; the refusal in words when the car is not parked or a test runs already. */
    fun start(scope: CoroutineScope, limitKph: Int, cameraM: Int): String? =
        launch(scope, { _ -> sendingFrames(limitKph, cameraM) }, DURATION_MS, "limit=$limitKph camera=${cameraM}m") {
            Status(running = true, secondsLeft = (DURATION_MS / SECOND_MS).toInt(), limitKph = limitKph, cameraM = cameraM)
        }

    /**
     * The route test (#53, the owner's go-ahead of 2026-11-03): [points] offered as the route the
     * car is on, the same 30 s, parked only, cleared after. Needs a position for the car's place on
     * the route.
     */
    fun startRoute(scope: CoroutineScope, points: List<RoutePoint>): String? {
        if (points.size < 2) return "немає маршруту"
        val ahead = AdasRouteFrames.head(points)
        val here = location()?.let { RoutePoint(it.latitude, it.longitude) } ?: points.first()
        val pathId = PATH_ID_BASE + nowMs() / SECOND_MS % PATH_ID_BASE
        val frames = listOf(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE)) +
            AdasRouteFrames.of(ahead, pathId, nowMs(), here)
        return launch(scope, { _ -> frames }, DURATION_MS, "route pathID=$pathId points=${ahead.size}/${points.size}") {
            Status(running = true, secondsLeft = (DURATION_MS / SECOND_MS).toInt(), route = true)
        }
    }

    /**
     * The limit change test (#23, the owner's go-ahead of 2026-11-03): the car took only the first
     * limit of a drive. [AdasLimitChangePlan] changes the limit inside one session in four ways,
     * ~1.5 min in all, so the car's answer each second tells which one the car follows.
     */
    fun startChange(scope: CoroutineScope): String? {
        val here = location()?.let { RoutePoint(it.latitude, it.longitude) } ?: RoutePoint(0.0, 0.0)
        val pathId = PATH_ID_BASE + nowMs() / SECOND_MS % PATH_ID_BASE
        val plan = AdasLimitChangePlan(here, pathId, nowMs())
        return launch(scope, plan::framesAt, plan.durationMs, "limit change pathID=$pathId") {
            Status(running = true, secondsLeft = (plan.durationMs / SECOND_MS).toInt(), variant = plan.variantAt(0))
        }
    }

    internal var location: () -> android.location.Location? = { TrackingService.lastLocation.value }

    private fun launch(
        scope: CoroutineScope,
        plan: (Long) -> List<ShadowFrame>,
        durationMs: Long,
        what: String,
        initial: () -> Status,
    ): String? {
        refusal(data())?.let { return it }
        if (!running.compareAndSet(false, true)) return "тест уже йде"
        _status.value = initial()
        scope.launch(Dispatchers.IO) { run(plan, durationMs, what) }
        return null
    }

    internal suspend fun run(limitKph: Int, cameraM: Int) =
        run({ _ -> sendingFrames(limitKph, cameraM) }, DURATION_MS, "limit=$limitKph camera=${cameraM}m")

    internal suspend fun run(plan: (Long) -> List<ShadowFrame>, durationMs: Long, what: String) {
        val sending = plan(0)
        val gateway = gatewayFactory(context)
        val fires = HashMap<Int, Int>()
        // Whatever ends the run before it says otherwise: leaving the settings screen cancels it
        // (2026-10-31 17:03 reported «gateway unavailable» for a run that sent all its frames).
        var outcome = "перервано"
        servicesStarted = false
        try {
            val before = readCar()
            note("parked test start $what car before: ${before?.describe() ?: "no answer"}")
            sending.forEach { note("parked test frame ${it.name} {${it.fields}} ${it.bytes.size} B") }
            if (!gateway.bind()) {
                outcome = "шлюз SOME/IP недоступний"
                return
            }
            // The factory map offers these services itself; ours are started only when a frame
            // does not go out without them, and only those are stopped at the end.
            val probe = sending.filter(::fired).map { gateway.fireEvent(it.topic, it.bytes) }
            note("parked test first frames rc=$probe")
            if (probe.any { it != 0 }) {
                servicesStarted = true
                gateway.startAll(::note)
            }
            outcome = sendLoop(gateway, plan, durationMs, fires, before)?.let { "зупинено: $it" } ?: "завершено"
        } finally {
            withContext(NonCancellable) { finish(gateway, servicesStarted, outcome, fires) }
        }
    }

    /** The plan's frames every [PERIOD_MS] until [durationMs] is over; the refusal that cut it short, if any. */
    private suspend fun sendLoop(
        gateway: AdasGateway,
        plan: (Long) -> List<ShadowFrame>,
        durationMs: Long,
        fires: MutableMap<Int, Int>,
        before: AdasCarReading?,
    ): String? {
        val start = nowMs()
        val end = start + durationMs
        var nextRead = 0L
        var lastCar = before
        var last: List<ShadowFrame>? = null
        while (nowMs() < end) {
            refusal(data())?.let { return it }
            val sending = plan(nowMs() - start)
            if (last != null && sending != last) noteChange(sending, last)
            last = sending
            val rcs = sending.filter(::fired).map { f -> gateway.fireEvent(f.topic, f.bytes).also { rc -> fires[rc] = (fires[rc] ?: 0) + 1 } }
            // A frame refused mid-run (3.11 20:27: the drive-time sender stopped the services as
            // the test began, 1694 of 1696 frames came back rc=11): start them and go on.
            if (rcs.any { it != 0 } && !servicesStarted) {
                servicesStarted = true
                note("parked test frames refused rc=$rcs: starting the services")
                gateway.startAll(::note)
            }
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

    /** A planned change: the frames whose bytes changed, so the car's next answers can be matched. */
    private fun noteChange(now: List<ShadowFrame>, before: List<ShadowFrame>) {
        val changed = now.filter { f -> before.none { it == f } }
        note("parked test now: " + changed.joinToString(" | ") { "${it.name} {${it.fields}}" })
        AdasLimitChangePlan.variantOf(changed)?.let { v -> _status.value = _status.value.copy(variant = v) }
        changed.firstOrNull { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }?.let { f ->
            f.fields.substringAfter("SpdLmtSpeedValue=").substringBefore(' ').toIntOrNull()
                ?.let { _status.value = _status.value.copy(limitKph = it) }
        }
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
        /** Route ids of the test sit apart from the factory map's small counters. */
        private const val PATH_ID_BASE = 1_000_000_000L

        /** The gateway services of the frames the tests send. */
        val SERVICES: List<Long> = listOf(
            AdasShadowFrames.TOPIC_NAVI_STATUS, AdasShadowFrames.TOPIC_ACTION_CAMERA,
            AdasRouteFrames.TOPIC_SD_ROUTE, AdasRouteFrames.TOPIC_PATH_SELECT,
        ).map(HudSomeIpBridge::serviceIdFor).distinct()

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

/** Starts every service the tests send on, each start logged. */
private fun AdasGateway.startAll(note: (String) -> Unit) {
    AdasParkedTest.SERVICES.forEach { note("parked test startService(${HudSomeIpBridge.hex(it)}) rc=${startService(it)}") }
}

/** Bookkeeping frames of a plan are never fired. */
private fun fired(f: ShadowFrame) = f.topic != AdasLimitChangePlan.MARKER_TOPIC

internal class BridgeGateway(private val bridge: HudSomeIpBridge) : AdasGateway {
    override suspend fun bind(): Boolean = bridge.bind()
    override fun startService(serviceId: Long): Int = bridge.startService(serviceId)
    override fun stopService(serviceId: Long): Int = bridge.stopService(serviceId)
    override fun fireEvent(topic: Long, payload: ByteArray): Int = bridge.fireEvent(topic, payload)
    override fun unbind() = bridge.unbind()
}
