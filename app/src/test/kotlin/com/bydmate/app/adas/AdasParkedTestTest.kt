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

    private class FakeGateway(private val rc: Int = 0) : AdasGateway {
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
        val last = gateway.fired.takeLast(3).map { it.first }
        assertEquals(AdasParkedTest.clearingFrames().map { it.topic }, last)
        assertTrue(gateway.fired.last().second.contentEquals(AdasParkedTest.clearingFrames().last().bytes))
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
        assertTrue(gateway.fired.last().second.contentEquals(AdasParkedTest.clearingFrames().last().bytes))
    }

    @Test fun `services it had to start are the ones it stops`() = runTest {
        val gateway = FakeGateway(rc = 1)
        val test = subject(gateway, { car(1, 0) }, { testScheduler.currentTime })
        test.run(30, 300)
        assertEquals(AdasParkedTest.SERVICES, gateway.started)
        assertEquals(AdasParkedTest.SERVICES, gateway.stopped)
    }
}
