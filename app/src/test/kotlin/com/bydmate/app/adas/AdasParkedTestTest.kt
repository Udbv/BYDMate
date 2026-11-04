package com.bydmate.app.adas

import com.bydmate.app.data.remote.DiParsData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parked ADAS test: parked only, stops the moment the car is not, always clears after. */
class AdasParkedTestTest {

    private class FakeGateway(var rc: Int = 0) : AdasGateway {
        val fired = mutableListOf<Pair<Long, ByteArray>>()
        val started = mutableListOf<Long>()
        val stopped = mutableListOf<Long>()
        var unbound = false
        override suspend fun bind() = true
        override fun startService(serviceId: Long): Int = 0.also { started += serviceId }
        override fun stopService(serviceId: Long): Int = 0.also { stopped += serviceId }
        override fun fireEvent(topic: Long, payload: ByteArray): Int = rc.also { fired += topic to payload }
        override fun unbind() { unbound = true }
    }

    private fun car(gear: Int?, speed: Int?): DiParsData = mockk {
        every { this@mockk.gear } returns gear
        every { this@mockk.speed } returns speed
    }

    private fun subject(gateway: FakeGateway, data: () -> DiParsData?, clock: () -> Long) =
        AdasParkedTest(mockk(relaxed = true), mockk(relaxed = true)).apply {
            this.data = data
            gatewayFactory = { gateway }
            readCar = { null }
            nowMs = clock
            location = { null }
            sharedState = { "-" }
        }

    @Test fun `only a car in P standing still may be sent to`() {
        assertNull(AdasParkedTest.refusal(car(gear = 1, speed = 0)))
        assertEquals("не на P", AdasParkedTest.refusal(car(gear = 4, speed = 0)))
        assertEquals("не на P", AdasParkedTest.refusal(car(gear = null, speed = 0)))
        assertEquals("машина рухається", AdasParkedTest.refusal(car(gear = 1, speed = 3)))
        assertEquals("немає даних від машини", AdasParkedTest.refusal(null))
    }

    @Test fun `a full run sends the limit and camera, then clears, and leaves the services alone`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.run(limitKph = 70, cameraM = 300)

        val sending = AdasParkedTest.sendingFrames(70, 300)
        val sent = gateway.fired.count { (t, b) -> sending.any { it.topic == t && it.bytes.contentEquals(b) } }
        assertTrue("about 30 s at 200 ms", sent >= 3 * 140)
        val clearing = AdasParkedTest.clearingFrames(null)
        val last = gateway.fired.takeLast(clearing.size).map { it.first }
        assertEquals(clearing.map { it.topic }, last)
        assertTrue(gateway.fired.last().second.contentEquals(AdasParkedTest.clearingFrames(null).last().bytes))
        assertTrue("the factory map's services are not ours to start or stop", gateway.started.isEmpty() && gateway.stopped.isEmpty())
        assertTrue(gateway.unbound)
        assertFalse(test.status.value.running)
        assertEquals("завершено", test.status.value.result)
    }

    @Test fun `leaving P stops at once and still clears`() = runTest {
        val gateway = FakeGateway()
        var gear = 1
        val test = subject(gateway, { car(gear, 0) }, { testScheduler.currentTime })
        gateway.fired.clear()
        val job = backgroundScope.launch { test.run(110, 300) }
        testScheduler.advanceTimeBy(1_000)
        gear = 4
        testScheduler.advanceTimeBy(5_000)
        assertTrue(job.isCompleted)
        assertEquals("зупинено: не на P", test.status.value.result)
        assertTrue(gateway.fired.size < 3 * 20)
        assertTrue(gateway.fired.last().second.contentEquals(AdasParkedTest.clearingFrames(null).last().bytes))
    }

    @Test fun `services it had to start are the ones it stops`() = runTest {
        val gateway = FakeGateway(rc = 1)
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.run(30, 300)
        assertEquals(AdasParkedTest.SERVICES, gateway.started)
        assertTrue("services are left running", gateway.stopped.isEmpty())
    }

    @Test fun `a cancelled run says it was cut short and still clears`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        val job = backgroundScope.launch { test.run(70, 300) }
        testScheduler.advanceTimeBy(2_000)
        job.cancel()
        testScheduler.advanceTimeBy(5_000)
        assertEquals("перервано", test.status.value.result)
        assertTrue(gateway.fired.last().second.contentEquals(AdasParkedTest.clearingFrames(null).last().bytes))
    }

    @Test fun `frames refused after a good start start the services and the run goes on`() = runTest {
        val gateway = FakeGateway(rc = 0)
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        val job = backgroundScope.launch { test.run(70, 300) }
        testScheduler.advanceTimeBy(1_000)
        assertTrue(gateway.started.isEmpty())
        gateway.rc = 11 // someone else stopped the services
        testScheduler.advanceTimeBy(1_000)
        assertEquals(AdasParkedTest.SERVICES, gateway.started)
        job.join()
        assertTrue("services are left running", gateway.stopped.isEmpty())
    }

    private val here = RoutePoint(50.0, 30.0)

    private fun routeFrames(): List<ShadowFrame> =
        listOf(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE)) +
            AdasRouteFrames.of(List(5) { RoutePoint(50.0 + it * 0.001, 30.0) }, 7L, 0L, here)

    private fun FakeGateway.count(topic: Long) = fired.count { it.first == topic }

    @Test fun `the end is the factory map's idle for three seconds, status 100 and the car on no path`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.run(70, 300)
        val idle = AdasParkedTest.clearingFrames(null)
        val tail = gateway.fired.takeLast(idle.size * AdasParkedTest.CLEAR_ROUNDS)
        assertTrue(tail.zip(idle.let { r -> List(AdasParkedTest.CLEAR_ROUNDS) { r }.flatten() }).all { (f, e) -> f.first == e.topic && f.second.contentEquals(e.bytes) })
        assertTrue((AdasParkedTest.CLEAR_ROUNDS - 1) * AdasParkedTest.CLEAR_PERIOD_MS >= 3_000)
        assertEquals(AdasShadowFrames.naviStatus(100), idle.first())
        assertTrue("no 9 on the bus any more", gateway.fired.none { it.first == AdasShadowFrames.TOPIC_NAVI_STATUS && it.second.contentEquals(AdasShadowFrames.naviStatus(9).bytes) })
    }

    @Test fun `a route goes out once and the car's place on it with every frame`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.run({ _ -> routeFrames() }, AdasParkedTest.DURATION_MS, "route", here)
        assertEquals(1, gateway.count(AdasRouteFrames.TOPIC_SD_ROUTE))
        assertEquals(1, gateway.count(AdasRouteFrames.TOPIC_PATH_SELECT))
        assertTrue(gateway.count(AdasRouteFrames.TOPIC_VEHICLE_LOCATION) > 100)
    }

    @Test fun `a route refused at first goes once the services are up`() = runTest {
        val gateway = FakeGateway(rc = 11)
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        val job = backgroundScope.launch { test.run({ _ -> routeFrames() }, AdasParkedTest.DURATION_MS, "route", here) }
        testScheduler.advanceTimeBy(100)
        val refused = gateway.count(AdasRouteFrames.TOPIC_SD_ROUTE)
        assertTrue("refused frames are tried again", refused >= 1)
        assertEquals(AdasParkedTest.SERVICES, gateway.started)
        gateway.rc = 0
        job.join()
        assertEquals("delivered once", refused + 1, gateway.count(AdasRouteFrames.TOPIC_SD_ROUTE))
    }

    @Test fun `after a route the car is left on no path at its position, the route is not retracted by default`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        assertFalse(AdasParkedTest.RETRACT_ROUTE_DEFAULT)
        test.run({ _ -> routeFrames() }, AdasParkedTest.DURATION_MS, "route", here)
        val idle = AdasParkedTest.clearingFrames(here)
        assertEquals(AdasRouteFrames.idleLocation(here), idle[1])
        val last = gateway.fired.takeLast(idle.size)
        assertTrue(last.zip(idle).all { (f, e) -> f.first == e.topic && f.second.contentEquals(e.bytes) })
        assertEquals(1, gateway.count(AdasRouteFrames.TOPIC_SD_ROUTE))
    }

    @Test fun `with the switch on a route is retracted once, after the first idle round`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.retractRoute = true
        test.run({ _ -> routeFrames() }, AdasParkedTest.DURATION_MS, "route", here)
        val idle = AdasParkedTest.clearingFrames(here)
        val retraction = AdasRouteFrames.retraction(0L)
        val end = gateway.fired.takeLast(idle.size * AdasParkedTest.CLEAR_ROUNDS + retraction.size)
        val after = end.drop(idle.size).take(retraction.size)
        assertEquals(retraction.map { it.topic }, after.map { it.first })
        assertTrue("pathID_Req 0: an empty struct", after[0].second.contentEquals(byteArrayOf(0x0A, 0x00)))
        assertEquals(2, gateway.count(AdasRouteFrames.TOPIC_SD_ROUTE))
    }

    @Test fun `a test without a route retracts nothing even with the switch on`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.retractRoute = true
        test.run(70, 300)
        assertEquals(0, gateway.count(AdasRouteFrames.TOPIC_SD_ROUTE))
        assertEquals(0, gateway.count(AdasRouteFrames.TOPIC_PATH_SELECT))
    }

    @Test fun `the change test with a route is refused without a position and sends nothing`() {
        val gateway = FakeGateway()
        var made = 0
        val test = subject(gateway, { car(1, 0) }, { 0L }).apply { gatewayFactory = { made++; gateway } }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        assertEquals("немає позиції", test.startChange(scope, withRoute = true))
        assertEquals(0, made)
        assertTrue(gateway.fired.isEmpty())
        assertFalse(test.status.value.running)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test fun `the change test by default has no route at all, so needs no position`() = runTest {
        val gateway = FakeGateway()
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        val plan = AdasLimitChangePlan(null, 1L, 0L)
        test.run(plan::framesAt, plan.durationMs, "limit change")
        assertTrue(gateway.fired.none { it.first in AdasRouteFrames.ONCE_TOPICS || it.first == AdasRouteFrames.TOPIC_VEHICLE_LOCATION })
    }

    @Test fun `the shared binding stays once the run started the services`() = runTest {
        val gateway = FakeGateway(rc = 11)
        val shared = AdasSharedGateway { gateway }
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime }).apply { gatewayFactory = { shared.lease() } }
        test.run(70, 300)
        assertEquals(AdasParkedTest.SERVICES, gateway.started)
        assertEquals(0, shared.consumers)
        assertTrue(shared.isBound && shared.isPinned)
        assertFalse("the gateway's onUnbind would stop the services on the HAL", gateway.unbound)
    }
}
