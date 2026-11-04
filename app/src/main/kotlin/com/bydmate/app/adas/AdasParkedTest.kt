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
 * for [CLEAR_MS] what the factory map sends with no route ([clearingFrames]: status 100, the car
 * on no path, idle traffic info, no camera), so the driving computer is left with nothing of
 * ours: after a route test that ended with the old "stopped" alone, the head unit's ADAS view
 * froze twice (3.11, 4.11). The gateway services it had to start stay up (stopping them froze the
 * head unit's ADAS view on 3.11), on the process's [AdasSharedGateway]. Nothing of this runs on
 * its own.
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
    internal var gatewayFactory: (Context) -> AdasGateway = { AdasSharedGateway.process(it).lease() }

    /**
     * Take our route back at the end of a test that offered one ([AdasRouteFrames.retraction]).
     * Off by default: neither the contract nor the factory map has such a step (the map leaves a
     * route to be replaced by the next one), and what the driving computer does with an empty
     * route or path 0 has not been tried on the car. The idle frames alone are what the map does.
     * Turned on only for the owner's A/B on the car or by a test.
     */
    internal var retractRoute: Boolean = RETRACT_ROUTE_DEFAULT
    internal var readCar: suspend () -> AdasCarReading? = {
        helper.readBatch(AdasCarReading.ITEMS)?.let { AdasCarReading.decode(it) }
    }
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var sharedState: () -> String = { AdasSharedGateway.process(context).describe() }

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
        note("parked test route pressed: points=${points.size}")
        if (points.size < 2) return "немає маршруту".also { note("parked test refused (route): $it") }
        return runCatching {
            val ahead = AdasRouteFrames.head(points)
            val here = location()?.let { RoutePoint(it.latitude, it.longitude) } ?: points.first()
            val pathId = PATH_ID_BASE + nowMs() / SECOND_MS % PATH_ID_BASE
            val frames = listOf(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE)) +
                AdasRouteFrames.of(ahead, pathId, nowMs(), here)
            launch(scope, { _ -> frames }, DURATION_MS, "route pathID=$pathId points=${ahead.size}/${points.size}", here) {
                Status(running = true, secondsLeft = (DURATION_MS / SECOND_MS).toInt(), route = true)
            }
        }.getOrElse { e ->
            note("parked test route failed: ${e.javaClass.simpleName}: ${e.message}")
            "помилка: ${e.javaClass.simpleName}"
        }
    }

    /**
     * The limit change test (#23, the owner's go-ahead of 2026-11-03): the car took only the first
     * limit of a drive. [AdasLimitChangePlan] changes the limit inside one session, so the car's
     * answer each second tells whether it follows. By default without a route
     * ([AdasLimitChangePlan.VARIANTS]): both freezes of the head unit's ADAS view (3.11, 4.11)
     * followed the only two runs whose route reached the car. [withRoute] runs the route variant
     * instead ([AdasLimitChangePlan.ROUTE_VARIANTS]); it needs the car's position and is refused
     * without one (it used to put the route at 0/0).
     */
    fun startChange(scope: CoroutineScope, withRoute: Boolean = false): String? {
        val what = if (withRoute) "limit change with route" else "limit change"
        val pos = location()
        note("parked test $what pressed: position ${if (pos != null) "known" else "unknown"}")
        val here = pos?.let { RoutePoint(it.latitude, it.longitude) }
        if (withRoute && here == null) return "немає позиції".also { note("parked test refused ($what): $it") }
        val pathId = PATH_ID_BASE + nowMs() / SECOND_MS % PATH_ID_BASE
        val plan = AdasLimitChangePlan(here, pathId, nowMs(), if (withRoute) AdasLimitChangePlan.ROUTE_VARIANTS else AdasLimitChangePlan.VARIANTS)
        val tag = if (withRoute) "$what pathID=$pathId" else what
        return launch(scope, plan::framesAt, plan.durationMs, tag, here) {
            Status(running = true, secondsLeft = (plan.durationMs / SECOND_MS).toInt(), variant = plan.variantAt(0))
        }
    }

    internal var location: () -> android.location.Location? = { TrackingService.lastLocation.value }

    private fun position(): RoutePoint? = location()?.let { RoutePoint(it.latitude, it.longitude) }

    private fun launch(
        scope: CoroutineScope,
        plan: (Long) -> List<ShadowFrame>,
        durationMs: Long,
        what: String,
        here: RoutePoint? = null,
        initial: () -> Status,
    ): String? {
        // Every refusal is logged: the screen line alone left three route tests without a trace (3.11).
        refusal(data())?.let { reason -> return reason.also { note("parked test refused ($what): $it") } }
        if (!running.compareAndSet(false, true)) return "тест уже йде".also { note("parked test refused ($what): $it") }
        _status.value = initial()
        scope.launch(Dispatchers.IO) { run(plan, durationMs, what, here) }
        return null
    }

    internal suspend fun run(limitKph: Int, cameraM: Int) =
        run({ _ -> sendingFrames(limitKph, cameraM) }, DURATION_MS, "limit=$limitKph camera=${cameraM}m")

    /** [here]: the position the plan's frames used, for the idle location when none is known at the end. */
    internal suspend fun run(plan: (Long) -> List<ShadowFrame>, durationMs: Long, what: String, here: RoutePoint? = null) {
        val sending = plan(0)
        val gateway = gatewayFactory(context)
        val fires = HashMap<Int, Int>()
        // The route frames that reached the car, per topic: sent again only when they change.
        val once = RouteOnce()
        // Whatever ends the run before it says otherwise: leaving the settings screen cancels it
        // (2026-10-31 17:03 reported «gateway unavailable» for a run that sent all its frames).
        var outcome = "перервано"
        servicesStarted = false
        var bound = false
        try {
            val before = readCar()
            note("parked test start $what car before: ${before?.describe() ?: "no answer"}")
            sending.forEach { note("parked test frame ${it.name} {${it.fields}} ${it.bytes.size} B") }
            if (!gateway.bind()) {
                outcome = "шлюз SOME/IP недоступний"
                return
            }
            bound = true
            // The factory map offers these services itself; ours are started only when a frame
            // does not go out without them, and only those are stopped at the end.
            val probe = fireAll(gateway, sending, once)
            note("parked test first frames rc=$probe")
            if (probe.any { it != 0 }) {
                servicesStarted = true
                gateway.startAll(::note)
            }
            outcome = sendLoop(gateway, plan, durationMs, fires, once, before)?.let { "зупинено: $it" } ?: "завершено"
        } finally {
            withContext(NonCancellable) { finish(gateway, bound, servicesStarted, outcome, fires, once.offered, here) }
        }
    }

    /**
     * Fires [frames] but the bookkeeping ones; a route frame ([AdasRouteFrames.ONCE_TOPICS]) only
     * when it differs from the one already delivered on its topic. The rc of each frame fired.
     */
    private fun fireAll(gateway: AdasGateway, frames: List<ShadowFrame>, once: RouteOnce): List<Int> =
        frames.filter(::fired).filter { f -> f.topic !in AdasRouteFrames.ONCE_TOPICS || once.sent[f.topic] != f }.map { f ->
            gateway.fireEvent(f.topic, f.bytes).also { rc ->
                if (rc == 0 && f.topic in AdasRouteFrames.ONCE_TOPICS) {
                    once.sent[f.topic] = f
                    once.offered = true
                    note("parked test route frame delivered: ${f.name} {${f.fields}}")
                }
            }
        }

    /** The route frames delivered, per topic, and whether any ever was (the route then needs clearing). */
    private class RouteOnce {
        val sent = HashMap<Long, ShadowFrame>()
        var offered = false
    }

    /** The plan's frames every [PERIOD_MS] until [durationMs] is over; the refusal that cut it short, if any. */
    private suspend fun sendLoop(
        gateway: AdasGateway,
        plan: (Long) -> List<ShadowFrame>,
        durationMs: Long,
        fires: MutableMap<Int, Int>,
        once: RouteOnce,
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
            val rcs = fireAll(gateway, sending, once).onEach { rc -> fires[rc] = (fires[rc] ?: 0) + 1 }
            // A frame refused mid-run (3.11 20:27: the drive-time sender stopped the services as
            // the test began, 1694 of 1696 frames came back rc=11): start them and go on.
            if (rcs.any { it != 0 } && !servicesStarted) {
                servicesStarted = true
                note("parked test frames refused rc=$rcs: starting the services")
                gateway.startAll(::note)
                // The services went down under the run: a route delivered before is gone with them.
                once.sent.clear()
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

    /**
     * Whatever ended the test: the factory map's idle for [CLEAR_MS] (status 100, the car on no
     * path at the position now or, unknown, at [here], idle traffic info, no camera) and, after a
     * route reached the car and only with [retractRoute], the route taken back after the first
     * round. Our services stay up and the shared binding with them.
     */
    private suspend fun finish(
        gateway: AdasGateway,
        bound: Boolean,
        started: Boolean,
        outcome: String,
        fires: Map<Int, Int>,
        routeOffered: Boolean,
        here: RoutePoint?,
    ) {
        if (bound) {
            val retract = routeOffered && retractRoute
            note(
                "parked test clear: idle ${CLEAR_MS} ms, status ${AdasShadowFrames.NAVI_STOPPED}, " +
                    "position ${if (position() != null) "known" else if (here != null) "from the run" else "unknown, no location frame"}, " +
                    "route offered=$routeOffered retract=$retract",
            )
            repeat(CLEAR_ROUNDS) { round ->
                clearingFrames(position() ?: here).forEach { f -> gateway.fireEvent(f.topic, f.bytes) }
                if (round == 0 && retract) {
                    val rcs = AdasRouteFrames.retraction(nowMs()).map { f -> gateway.fireEvent(f.topic, f.bytes) }
                    note("parked test route retracted rc=$rcs")
                }
                delay(CLEAR_PERIOD_MS)
            }
        }
        // The services stay up (see AdasLimitSender.Session.close): stopping them froze the
        // head unit's ADAS view on 3.11.
        if (started) note("parked test: services left running")
        gateway.unbind()
        note("parked test: gateway lease released, shared ${sharedState()}")
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
        /** How long the idle goes out at the end: the map repeats its idle every second, for good. */
        const val CLEAR_MS = 3_000L
        const val CLEAR_PERIOD_MS = 500L
        /** Rounds at 0, 0.5 … 3 s: the last one is [CLEAR_MS] after the first. */
        const val CLEAR_ROUNDS = (CLEAR_MS / CLEAR_PERIOD_MS + 1).toInt()
        /** See [retractRoute]: the report on the freezes did not justify it as a default. */
        const val RETRACT_ROUTE_DEFAULT = false
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

        /**
         * One round of the factory map's idle: status 100, the car at [here] on no path (left out
         * without a position: a location at 0/0 is worse than none, the map sends its own every
         * second), idle traffic info and no manoeuvre or camera.
         */
        fun clearingFrames(here: RoutePoint?): List<ShadowFrame> = listOfNotNull(
            AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED),
            here?.let(AdasRouteFrames::idleLocation),
            AdasShadowFrames.idleTrafficInfo(),
            AdasShadowFrames.actionAndCamera(0, 0, 0),
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
