package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `navBarDirectionText` is the roundabout exit number Waze paints inside the arrow, and nothing
 * else. It used to be read as instruction text, where a bare "2" parsed to "no maneuver" and took
 * the whole route's code down with it - which is why no roundabout ever reached the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WazeExitNumberTest {

    @Before fun setUp() {
        NaviPhraseFixtures.load()
        WazeAccessibilityReader.resetCensus()
    }

    private fun AccessibilityNodeInfo.stubUnusedSemanticChannels() {
        every { hintText } returns null
        every { paneTitle } returns null
        every { tooltipText } returns null
        every { stateDescription } returns null
        every { actionList } returns emptyList()
        every { viewIdResourceName } returns null
    }

    private fun valueNode(value: String?): AccessibilityNodeInfo = mockk(relaxed = true) {
        every { isVisibleToUser } returns true
        every { text } returns value
        every { contentDescription } returns null
        every { childCount } returns 0
        stubUnusedSemanticChannels()
    }

    private fun routeRoot(vararg nodes: Pair<String, String?>): AccessibilityNodeInfo =
        mockk<AccessibilityNodeInfo>(relaxed = true).also { root ->
            every { root.packageName } returns "com.waze"
            every { root.isVisibleToUser } returns true
            every { root.text } returns null
            every { root.contentDescription } returns null
            every { root.childCount } returns 0
            every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
            root.stubUnusedSemanticChannels()
            for ((id, value) in nodes) {
                every { root.findAccessibilityNodeInfosByViewId("com.waze:id/$id") } returns
                    listOf(valueNode(value))
            }
        }

    @Test fun `a bare number in navBarDirectionText is the exit number, not a maneuver`() {
        val fields = WazeAccessibilityReader.read(
            routeRoot(
                "navBarDistance" to "300 m",
                "navBarStreetLine" to "Main St",
                "navBarInstructionText" to "Turn right onto Main St",
                "navBarDirectionText" to "2",
            ),
        )!!

        assertEquals(2, fields.exitNumber)
        assertEquals("Turn right onto Main St", fields.maneuver)
        assertEquals(2, WazeGuidanceParser.parse(fields)!!.maneuverGaode)
        assertEquals(2, WazeGuidanceParser.parse(fields)!!.exitNumber)
    }

    @Test fun `a signed number is still an exit number`() {
        val fields = WazeAccessibilityReader.read(
            routeRoot("navBarDistance" to "300 m", "navBarDirectionText" to " +3 "),
        )!!

        assertEquals(3, fields.exitNumber)
    }

    @Test fun `anything that is not purely a number yields no exit`() {
        val fields = WazeAccessibilityReader.read(
            routeRoot("navBarDistance" to "300 m", "navBarDirectionText" to "2nd"),
        )!!

        assertNull(fields.exitNumber)
        // And it is not a maneuver either: the id is no longer scanned for instruction text.
        assertNull(fields.maneuver)
    }

    @Test fun `without the node the exit named by the instruction text stands in`() {
        val fields = WazeAccessibilityReader.read(
            routeRoot(
                "navBarDistance" to "300 m",
                "navBarInstructionText" to "At the roundabout, take the 3rd exit",
            ),
        )!!

        assertEquals(3, fields.exitNumber)
        assertEquals("3", fields.textExitNumber)
        // The text path still resolves the roundabout code on its own (24 + 3).
        assertEquals(27, WazeGuidanceParser.parse(fields)!!.maneuverGaode)
    }
}
