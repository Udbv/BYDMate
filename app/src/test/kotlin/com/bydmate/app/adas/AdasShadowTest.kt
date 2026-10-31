package com.bydmate.app.adas

import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdasShadowTest {

    private fun route(limit: Int = 50, camera: Int = 0) = NavGuidanceHub.Snapshot(
        active = true, maneuverGaode = 2, distanceMeters = 300, totalDistMeters = 12_000, etaSeconds = 900,
        speedLimit = limit, cameraDistanceMeters = camera,
        cameraKind = if (camera > 0) WazeReportsReader.Kind.CAMERA else WazeReportsReader.Kind.OTHER,
    )

    @Test fun `no route offers only the stopped status`() {
        val frames = AdasShadowFrames.of(NavGuidanceHub.Snapshot(active = false))
        assertEquals(1, frames.size)
        // {1: {4: 9}}
        assertEquals("0a022009", frames.single().hex)
    }

    @Test fun `traffic info carries Waze's limit, the camera and the destination as proto3`() {
        val traffic = AdasShadowFrames.of(route(limit = 50, camera = 400))
            .single { it.topic == AdasShadowFrames.TOPIC_TRAFFIC_INFO }
        // {1: {8: 50, 10: 400, 17: 12000, 18: 900}}
        assertEquals("0a0d4032509003" + "88" + "01e05d" + "9001" + "8407", traffic.hex)
        assertTrue(traffic.fields.contains("SpdLmtSpeedValue=50"))
    }

    @Test fun `a report that is not a camera sends no camera`() {
        val snap = route().copy(cameraDistanceMeters = 200, cameraKind = WazeReportsReader.Kind.POLICE)
        val action = AdasShadowFrames.of(snap).single { it.topic == AdasShadowFrames.TOPIC_ACTION_CAMERA }
        assertTrue(action.fields.endsWith("cameraType=0 cameraDistance=0"))
    }

    @Test fun `the car's replies decode sign, assist limit and cruise, and drop the none values`() {
        val r = AdasCarReading.decode(listOf(0 to 70, 0 to 15, 0 to 6, 0 to 2, 0 to 110, 0 to 5, 1 to 3))
        assertEquals(70, r.signKph)
        assertEquals(70, r.slaKph)
        assertEquals(110, r.cruiseKph)
        assertNull(r.noaGray)
        val none = AdasCarReading.decode(listOf(0 to 255, 0 to 0))
        assertNull(none.signKph)
        assertNull(none.carLimitKph)
    }

    @Test fun `the drive's comparison counts agreement and the differing pairs`() {
        val stats = AdasShadowStats()
        repeat(3) { stats.add(50, 50) }
        stats.add(50, 70)
        stats.add(90, null)
        stats.add(null, 60)
        assertEquals(3, stats.agree)
        assertEquals(1, stats.differ)
        assertTrue(stats.summary(), stats.summary().contains("agree=3 (75%)"))
        assertTrue(stats.summary(), stats.summary().contains("50/70x1"))
    }
}
