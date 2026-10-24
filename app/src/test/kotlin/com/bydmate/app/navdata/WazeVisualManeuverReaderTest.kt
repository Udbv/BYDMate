package com.bydmate.app.navdata

import com.bydmate.app.navdata.waze.ArrowFixtures
import com.bydmate.app.navdata.waze.WazeArrowTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The classification step between the cropped pixels and the panel glyph: the signature match, the
 * roundabout exit override, and what happens when nothing is recognised.
 */
class WazeVisualManeuverReaderTest {

    private fun signatureOf(name: String): String =
        WazeArrowTable.entries.entries.first { it.value == name }.key.asString()

    private fun classify(name: String, exitNumber: Int? = null, side: Int = 183) =
        WazeVisualManeuverReader.classifyPixels(
            side,
            side,
            ArrowFixtures.render(signatureOf(name), side),
            exitNumber,
        )

    @Test fun `a recognised turn becomes its panel glyph`() {
        val result = classify("big_trans_direction_left")

        assertEquals(1, result.panelIcon)
        assertEquals("big_trans_direction_left", result.matchedName)
        assertEquals(0, result.hamming)
    }

    @Test fun `a roundabout takes the exit number Waze printed inside it`() {
        // 24 + 3: the panel's own numbering for "third exit, right-hand traffic".
        assertEquals(27, classify("big_trans_directions_roundabout", exitNumber = 3).panelIcon)
    }

    @Test fun `a left-hand-traffic roundabout uses the second block of exit glyphs`() {
        // 34 + 2, not 24 + 2: the UK/LHS artwork has its own ten glyphs on the panel.
        assertEquals(36, classify("big_trans_directions_roundabout_lhs", exitNumber = 2).panelIcon)
        assertEquals(36, classify("car_dark_big_directions_roundabout_uk", exitNumber = 2).panelIcon)
    }

    @Test fun `a roundabout without an exit number keeps its plain glyph`() {
        assertEquals(25, classify("big_trans_directions_roundabout").panelIcon)
        assertEquals(35, classify("big_trans_directions_roundabout_lhs").panelIcon)
    }

    @Test fun `a non-roundabout arrow ignores a stale exit number`() {
        assertEquals(1, classify("big_trans_direction_left", exitNumber = 3).panelIcon)
    }

    @Test fun `an unmatched arrow leaves the glyph unknown and reports its shape`() {
        val half = IntArray(183 * 183) { index ->
            if (index / 183 < 91) ArrowFixtures.INK else ArrowFixtures.PAPER
        }

        val result = WazeVisualManeuverReader.classifyPixels(183, 183, half, null)

        // 0, not the donor's 11: an unknown arrow must not overwrite a maneuver the text path read.
        assertEquals(0, result.panelIcon)
        assertNull(result.matchedName)
        assertEquals(225, result.gridString.length)
    }

    @Test fun `a crop with no contrast produces no shape string at all`() {
        val blank = IntArray(183 * 183) { ArrowFixtures.PAPER }

        val result = WazeVisualManeuverReader.classifyPixels(183, 183, blank, 7)

        assertEquals(0, result.panelIcon)
        assertNull(result.matchedName)
        assertEquals("", result.gridString)
    }

    @Test fun `every reference arrow classifies to the glyph its name names`() {
        for ((grid, name) in WazeArrowTable.entries) {
            val result = WazeVisualManeuverReader.classifyPixels(
                183,
                183,
                ArrowFixtures.render(grid.asString(), 183),
                null,
            )
            assertEquals(name, result.matchedName)
            assertEquals("glyph for $name", WazeArrowTable.iconFor(name), result.panelIcon)
        }
    }
}
