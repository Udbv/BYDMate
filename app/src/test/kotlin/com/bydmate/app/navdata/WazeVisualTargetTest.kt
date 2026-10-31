package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where the screenshot is aimed. Near a junction Waze swaps its arrow for the lane strip, and in
 * the 2026-10-31 logs every screenshot stopped right there, so the strip was never read whole.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class WazeVisualTargetTest {

    @After fun tearDown() {
        NavLaneState.geometry = null
    }

    @Test fun `the arrow is the target while Waze shows it`() {
        NavLaneState.geometry = strip()
        val target = WazeVisualManeuverReader.findTarget(wazeRoot(arrow = true))!!
        assertEquals(WazeVisualManeuverReader.SOURCE_ARROW, target.source)
        assertEquals(75, target.bounds.width())
    }

    @Test fun `with the arrow gone the lane strip is the target`() {
        NavLaneState.geometry = strip()
        val target = WazeVisualManeuverReader.findTarget(wazeRoot(arrow = false))!!
        assertEquals(WazeVisualManeuverReader.SOURCE_LANES, target.source)
        assertEquals(960, target.bounds.width())
        assertEquals(96, target.bounds.height())
    }

    @Test fun `neither arrow nor strip means no screenshot`() {
        assertNull(WazeVisualManeuverReader.findTarget(wazeRoot(arrow = false)))
    }

    private fun strip() = NavLaneState.LaneGeometry(0, NavLaneState.LaneRect(0, 84, 960, 180))

    private fun wazeRoot(arrow: Boolean): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.waze"
        every { root.window } returns null
        val nodes = if (arrow) {
            val node = mockk<AccessibilityNodeInfo>(relaxed = true)
            every { node.getBoundsInScreen(any()) } answers { firstArg<android.graphics.Rect>().set(15, 93, 90, 168) }
            listOf(node)
        } else {
            emptyList()
        }
        every { root.findAccessibilityNodeInfosByViewId("com.waze:id/navBarDirection") } returns nodes
        return root
    }
}
