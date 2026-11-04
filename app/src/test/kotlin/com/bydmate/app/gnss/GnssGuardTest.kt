package com.bydmate.app.gnss

import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-wide guard and the car's speed track behind it. */
class GnssGuardTest {

    @After fun tearDown() {
        GnssGuard.clock = { android.os.SystemClock.elapsedRealtime() }
        GnssGuard.reset()
    }

    private val snap = NavGuidanceHub.Snapshot(
        active = true,
        maneuverGaode = 2,
        distanceMeters = 300,
        speedLimit = 90,
        cameraAlert = "Камера",
        cameraDistanceMeters = 1200,
        cameraKind = WazeReportsReader.Kind.CAMERA,
        cameraAlertMs = 5L,
    )

    @Test fun `a plausible position withholds nothing`() {
        GnssGuard.reset()
        assertSame(snap, GnssGuard.withheld(snap))
    }

    @Test fun `an implausible position withholds the limit and the camera, not the maneuver`() {
        GnssGuard.reset()
        var now = 0L
        GnssGuard.clock = { now }
        for (t in 0L..5_000L step 1_000) {
            now = t
            GnssGuard.onCarSpeed(80)
        }
        now = 3_000
        GnssGuard.onWazeCamera(3_000)
        now = 4_000
        GnssGuard.onWazeCamera(30_000)
        assertTrue(GnssGuard.suspect)
        val out = GnssGuard.withheld(snap)
        assertEquals(0, out.speedLimit)
        assertEquals(0, out.cameraDistanceMeters)
        assertEquals("", out.cameraAlert)
        assertEquals(WazeReportsReader.Kind.OTHER, out.cameraKind)
        assertEquals(2, out.maneuverGaode)
        assertEquals(300, out.distanceMeters)
    }

    @Test fun `the speed track bounds the distance driven`() {
        val track = CarSpeedTrack()
        for (t in 0L..10_000L step 1_000) track.add(t, 72) // 20 m/s
        assertEquals(200.0, track.maxDriven(0, 10_000), 1e-6)
        assertEquals(20.0, track.minSpeed(2_000, 8_000)!!, 1e-6)
        // Past the last sample's hold, nothing speaks: 200 km/h is assumed.
        assertEquals(20.0 * 16 + CarSpeedTrack.FALLBACK_MPS * 4, track.maxDriven(10_000, 30_000), 1e-6)
        assertEquals(null, track.minSpeed(10_000, 30_000))
        assertFalse(CarSpeedTrack().maxSpeed(0, 1_000) != null)
    }
}
