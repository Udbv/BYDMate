package com.bydmate.app.navdata

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WazeVisualManeuverReader.diagnostics] is overwritten with "pending" the moment a screenshot is
 * requested, so a diagnostic export taken mid-flight cannot say whether the visual path was ever
 * requested, started or completed. The counters keep that separable across one route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WazeVisualManeuverCountersTest {

    @Before fun setUp() {
        WazeVisualManeuverReader.resetCounters()
    }

    @Test fun `counters start empty`() {
        val counters = WazeVisualManeuverReader.counters()

        assertEquals(0, counters.requested)
        assertEquals(0, counters.started)
        assertEquals(0, counters.completed)
        assertEquals(null, counters.lastCompleted)
    }

    @Test fun `a route bar whose arrow node reports no bounds is not classified at all`() {
        // Waze does this while the route bar animates. openbyd measured where the arrow actually
        // sits on this head unit, so the capture goes ahead against that fixed rectangle rather
        // than skipping the maneuver entirely.
        val service = mockk<AccessibilityService>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns "com.waze"
            every { isVisibleToUser } returns true
            every { childCount } returns 0
            every { window } returns null
            every { viewIdResourceName } returns null
            every { findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        }

        WazeVisualManeuverReader.request(service, root, null) { }

        val counters = WazeVisualManeuverReader.counters()
        assertEquals(1, counters.requested)
        // No navBarDirection bounds -> no screenshot at all: the donor's default rectangle lands on
        // the map on the Tang L and produced 80 phantom roundabouts in one drive.
        assertEquals(0, counters.started)
        assertEquals("maneuver_bounds_not_found", WazeVisualManeuverReader.diagnostics().failure)
    }

    @Test fun `a foreign root is requested but never produces a completion target`() {
        val service = mockk<AccessibilityService>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns "com.android.launcher"
        }

        WazeVisualManeuverReader.request(service, root, null) { }

        assertEquals(1, WazeVisualManeuverReader.counters().requested)
        assertEquals(0, WazeVisualManeuverReader.counters().started)
        assertEquals("not_a_waze_window", WazeVisualManeuverReader.counters().lastCompleted?.failure)
    }
}
