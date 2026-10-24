package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The camera fields as Waze's reports list fills them: the nearest camera ahead wins, and it
 * leaves the glass by time because the list never says "gone", it simply stops listing the row.
 */
class NavGuidanceHubCameraTest {

    @Before fun reset() = NavGuidanceHub.reset()

    private fun activate(nowMs: Long) =
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 2, distanceMeters = 300),
            NavGuidanceHub.Source.A11Y, nowMs)

    private fun report(kind: WazeReportsReader.Kind, text: String, meters: Int) =
        WazeReportsReader.NavReport(kind, text, meters)

    @Test fun `the nearest camera row reaches the snapshot`() {
        activate(1_000)
        val changed = NavGuidanceHub.updateReports(
            listOf(
                report(WazeReportsReader.Kind.OTHER, "Глухий затор", 400),
                report(WazeReportsReader.Kind.CAMERA, "Камера", 800),
                report(WazeReportsReader.Kind.CAMERA, "Камера", 2_000),
            ),
            nowMs = 1_000,
        )

        assertTrue(changed)
        val s = NavGuidanceHub.snapshot(nowMs = 1_000)
        assertEquals("Камера", s.cameraAlert)
        assertEquals(800, s.cameraDistanceMeters)
        assertEquals(WazeReportsReader.Kind.CAMERA, s.cameraKind)
    }

    @Test fun `police and hazard rows never become a camera`() {
        activate(1_000)
        NavGuidanceHub.updateReports(
            listOf(
                report(WazeReportsReader.Kind.POLICE, "Поліція", 500),
                report(WazeReportsReader.Kind.OTHER, "Аварія", 700),
            ),
            nowMs = 1_000,
        )

        assertEquals("", NavGuidanceHub.snapshot(nowMs = 1_000).cameraAlert)
    }

    @Test fun `an unchanged camera reports no change but keeps the clock running`() {
        activate(1_000)
        val rows = listOf(report(WazeReportsReader.Kind.CAMERA, "Камера", 800))
        NavGuidanceHub.updateReports(rows, nowMs = 1_000)

        assertFalse(NavGuidanceHub.updateReports(rows, nowMs = 9_000))

        val later = 9_000 + NavGuidanceHub.CAMERA_TIMEOUT_MS
        assertEquals(800, NavGuidanceHub.snapshot(nowMs = later).cameraDistanceMeters)
    }

    @Test fun `a camera nobody reports again expires`() {
        activate(1_000)
        NavGuidanceHub.updateReports(
            listOf(report(WazeReportsReader.Kind.CAMERA, "Камера", 800)),
            nowMs = 1_000,
        )

        val s = NavGuidanceHub.snapshot(nowMs = 1_000 + NavGuidanceHub.CAMERA_TIMEOUT_MS + 1)
        assertEquals("", s.cameraAlert)
        assertEquals(0, s.cameraDistanceMeters)
        assertEquals(WazeReportsReader.Kind.OTHER, s.cameraKind)
    }

    @Test fun `an empty list leaves the camera alone until it expires`() {
        activate(1_000)
        NavGuidanceHub.updateReports(
            listOf(report(WazeReportsReader.Kind.CAMERA, "Камера", 800)),
            nowMs = 1_000,
        )

        assertFalse(NavGuidanceHub.updateReports(emptyList(), nowMs = 2_000))
        assertEquals(800, NavGuidanceHub.snapshot(nowMs = 2_000).cameraDistanceMeters)
    }

    @Test fun `reports never activate a route on their own`() {
        NavGuidanceHub.updateReports(
            listOf(report(WazeReportsReader.Kind.CAMERA, "Камера", 800)),
            nowMs = 1_000,
        )

        assertFalse(NavGuidanceHub.snapshot(nowMs = 1_000).active)
    }
}
