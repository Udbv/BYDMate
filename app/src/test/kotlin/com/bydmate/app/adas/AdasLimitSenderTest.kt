package com.bydmate.app.adas

import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Waze's limits sent to ADAS while driving (#23). */
class AdasLimitSenderTest {

    private class FakeGateway(var rc: Int = 0, private val binds: Boolean = true) : AdasGateway {
        val fired = mutableListOf<Long>()
        val started = mutableListOf<Long>()
        val stopped = mutableListOf<Long>()
        var unbound = false
        override suspend fun bind() = binds
        override fun startService(serviceId: Long): Int = 0.also { started += serviceId }
        override fun stopService(serviceId: Long): Int = 0.also { stopped += serviceId }
        override fun fireEvent(topic: Long, payload: ByteArray): Int = rc.also { fired += topic }
        override fun unbind() { unbound = true }
    }

    private fun snap(limit: Int, camera: Int = 0) = NavGuidanceHub.Snapshot(
        speedLimit = limit,
        cameraDistanceMeters = camera,
        cameraKind = if (camera > 0) WazeReportsReader.Kind.CAMERA else WazeReportsReader.Kind.OTHER,
    )

    @Test fun `nothing goes out without a Waze limit`() {
        assertNull(AdasLimitSender.framesFor(snap(0, camera = 300)))
    }

    @Test fun `the limit goes out with the camera and no manoeuvre`() {
        val frames = AdasLimitSender.framesFor(snap(50, camera = 300))!!
        assertEquals(AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE), frames[0])
        assertEquals(AdasShadowFrames.trafficInfo(50, 300), frames[1])
        assertEquals(AdasShadowFrames.actionAndCamera(0, 0, 300), frames[2])
    }

    @Test fun `a lost limit clears once, and closing stops what was started`() = runTest {
        val gateway = FakeGateway(rc = 11)
        val lines = mutableListOf<String>()
        val session = AdasLimitSender.Session(gateway) { lines += it }
        session.send(AdasLimitSender.framesFor(snap(90)))
        assertEquals(AdasParkedTest.SERVICES, gateway.started)
        gateway.rc = 0
        session.send(AdasLimitSender.framesFor(snap(90)))
        assertEquals(1, lines.count { it.startsWith("ADAS live send") })

        val before = gateway.fired.size
        session.send(null)
        assertTrue(gateway.fired.size > before)
        assertEquals(AdasParkedTest.clearingFrames(null).map { it.topic }, gateway.fired.takeLast(3))
        val afterClear = gateway.fired.size
        session.send(null)
        assertEquals("cleared only once", afterClear, gateway.fired.size)

        session.close()
        assertTrue("services are never stopped", gateway.stopped.isEmpty())
        assertTrue(gateway.unbound)
    }

    @Test fun `an implausible position clears once, with its reason, and sends no camera`() = runTest {
        val gateway = FakeGateway()
        val lines = mutableListOf<String>()
        val session = AdasLimitSender.Session(gateway) { lines += it }
        session.send(AdasLimitSender.framesFor(snap(90, camera = 1200)))
        session.send(null, AdasLimitSender.WHY_GNSS)
        assertEquals("ADAS live clear: ${AdasLimitSender.WHY_GNSS}", lines.last())
        val afterClear = gateway.fired.size
        session.send(null, AdasLimitSender.WHY_GNSS)
        assertEquals("cleared only once", afterClear, gateway.fired.size)
    }

    @Test fun `no gateway, nothing sent and nothing to clear`() = runTest {
        val gateway = FakeGateway(binds = false)
        val session = AdasLimitSender.Session(gateway) {}
        session.send(AdasLimitSender.framesFor(snap(50)))
        session.close()
        assertTrue(gateway.fired.isEmpty())
        assertTrue(!gateway.unbound)
    }

    @Test fun `the clear is the map's idle for three seconds, the car on no path at its position`() = runTest {
        val gateway = FakeGateway()
        val here = RoutePoint(50.0, 30.0)
        val session = AdasLimitSender.Session(gateway, { here }) {}
        session.send(AdasLimitSender.framesFor(snap(90)))
        val before = gateway.fired.size
        val start = testScheduler.currentTime
        session.send(null)
        val round = AdasParkedTest.clearingFrames(here).map { it.topic }
        assertEquals(List(AdasParkedTest.CLEAR_ROUNDS) { round }.flatten(), gateway.fired.drop(before))
        assertTrue(testScheduler.currentTime - start >= AdasParkedTest.CLEAR_MS)
    }

    @Test fun `an implausible position sends no location in the clear`() = runTest {
        val gateway = FakeGateway()
        val session = AdasLimitSender.Session(gateway, { RoutePoint(50.0, 30.0) }) {}
        session.send(AdasLimitSender.framesFor(snap(90)))
        val before = gateway.fired.size
        session.send(null, AdasLimitSender.WHY_GNSS)
        assertTrue(gateway.fired.drop(before).none { it == AdasRouteFrames.TOPIC_VEHICLE_LOCATION })
    }

    @Test fun `closing a session lets go of its lease only, the started services keep the binding`() = runTest {
        val gateway = FakeGateway(rc = 11)
        val shared = AdasSharedGateway { gateway }
        val session = AdasLimitSender.Session(shared.lease()) {}
        session.send(AdasLimitSender.framesFor(snap(90)))
        session.close()
        assertTrue(shared.isPinned)
        assertTrue("the binding stays", !gateway.unbound)
    }
}