package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "then" widget: `navBarThenDirection` carries the icon-font character Waze paints for the
 * maneuver after the next one. It is neither a word nor a code anything here understands yet, so
 * it is read, carried and logged with its code points - and sent nowhere. The code points are the
 * key the panel's secondary-maneuver icons will eventually be mapped from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WazeThenGlyphTest {

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

    @Test fun `the then glyph is read verbatim`() {
        val fields = WazeAccessibilityReader.read(routeRoot(
            "navBarDistance" to "300 m",
            "navBarStreetLine" to "Khreshchatyk",
            "navBarThenDirection" to "",
        ))!!
        assertEquals("", fields.thenGlyph)
        assertEquals("", WazeGuidanceParser.parse(fields)!!.thenText)
    }

    @Test fun `a blank then glyph is absent, not an empty string`() {
        val fields = WazeAccessibilityReader.read(routeRoot(
            "navBarDistance" to "300 m",
            "navBarThenDirection" to "   ",
        ))!!
        assertNull(fields.thenGlyph)
        assertEquals("", WazeGuidanceParser.parse(fields)!!.thenText)
    }

    @Test fun `a route without the widget carries no then text`() {
        val fields = WazeAccessibilityReader.read(routeRoot("navBarDistance" to "300 m"))!!
        assertNull(fields.thenGlyph)
    }

    // -- the trip-log line ------------------------------------------------------------------

    @Test fun `a changed glyph writes one line with its code points`() {
        val lines = collect {
            NavA11yFeed.logThen("")
            NavA11yFeed.logThen("")
            NavA11yFeed.logThen("")
        }
        assertEquals(2, lines.size)
        assertTrue(lines[0], "U+E915" in lines[0])
        assertTrue(lines[0], "glyph=''" in lines[0])
        assertTrue(lines[1], "U+E917" in lines[1])
    }

    @Test fun `an empty glyph writes nothing`() {
        assertTrue(collect { NavA11yFeed.logThen("") }.isEmpty())
    }

    @Test fun `a surrogate pair is one code point`() {
        // U+1F500 outside the BMP: two chars in the string, one character on the glass.
        assertEquals("U+1F500", NavA11yFeed.codePoints("🔀"))
        assertEquals("U+0041 U+E915", NavA11yFeed.codePoints("A"))
    }

    /** Runs [body] with the "then" sink captured; the feed is reset so each test starts fresh. */
    private fun collect(body: () -> Unit): List<String> {
        val out = mutableListOf<String>()
        val real = NavA11yFeed.thenSink
        NavA11yFeed.enabled = false
        NavA11yFeed.enabled = true   // clears the last-glyph memo
        NavA11yFeed.thenSink = { out.add(it) }
        try {
            body()
        } finally {
            NavA11yFeed.thenSink = real
            NavA11yFeed.enabled = false
        }
        return out
    }
}
