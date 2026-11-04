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
        assertEquals(AdasShadowFrames.TOPIC_NAVI_STATUS, gateway.fired.last())
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
}
