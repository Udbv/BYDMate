package com.bydmate.app.hud

import com.bydmate.app.navdata.NavManeuverCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the four symptoms seen in the car on 3.16.0-dev.2: a detour glyph for
 * roundabouts, a left arrow for a slight right, and Chinese characters on arrival.
 */
class HudInstrumentIconsTest {

    @Test fun `plain turns pass through unchanged`() {
        assertEquals(1, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_LEFT))
        assertEquals(2, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_RIGHT))
        assertEquals(3, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_SLIGHT_LEFT))
        assertEquals(7, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_HARD_LEFT))
        assertEquals(8, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_HARD_RIGHT))
        assertEquals(9, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_UTURN))
        assertEquals(10, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_UTURN_RIGHT))
        assertEquals(11, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_STRAIGHT))
    }

    @Test fun `a slight right never draws a left arrow`() {
        val icon = HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_SLIGHT_RIGHT)
        assertEquals(HudInstrumentIcons.SLIGHT_RIGHT, icon)
        // The bug: the raw code 4 is the panel's second slight-LEFT glyph.
        assertTrue("slight right must not reuse the raw Gaode number", icon != NavManeuverCodes.GAODE_SLIGHT_RIGHT)
        assertTrue(icon != HudInstrumentIcons.SLIGHT_LEFT)
    }

    @Test fun `entering or leaving a roundabout draws a roundabout, not a detour`() {
        val enter = HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER)
        val exit = HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ROUNDABOUT_EXIT)
        assertEquals(HudInstrumentIcons.ROUNDABOUT_STRAIGHT, enter)
        assertEquals(HudInstrumentIcons.ROUNDABOUT_STRAIGHT, exit)
        // 13 is the panel's detour-right glyph and must never be selected for a roundabout.
        assertTrue(enter != NavManeuverCodes.GAODE_ROUNDABOUT_ENTER)
    }

    @Test fun `numbered roundabout exits line up one for one`() {
        for (n in 1..10) {
            val gaode = NavManeuverCodes.GAODE_ROUNDABOUT_EXIT + n
            assertEquals("exit $n", 24 + n, HudInstrumentIcons.fromGaode(gaode))
        }
        // Just past the last exit the panel switches to clockwise glyphs, which are wrong here.
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(35))
    }

    @Test fun `arrival draws nothing rather than Chinese characters`() {
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ARRIVE))
        assertFalse(HudInstrumentIcons.hasGlyph(NavManeuverCodes.GAODE_ARRIVE))
        assertTrue(HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ARRIVE) != HudInstrumentIcons.DESTINATION_CHINESE)
    }

    @Test fun `waypoint and ferry blank rather than drawing stop or parking`() {
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_WAYPOINT))
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_FERRY))
    }

    @Test fun `toll and tunnel keep their glyphs`() {
        assertEquals(47, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_TOLL))
        assertEquals(49, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_TUNNEL))
    }

    @Test fun `no maneuver and unknown codes blank the icon`() {
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(0))
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(999))
        assertEquals(HudInstrumentIcons.NONE, HudInstrumentIcons.fromGaode(-1))
    }

    @Test fun `every produced glyph is within the panel table`() {
        for (gaode in -5..60) {
            val icon = HudInstrumentIcons.fromGaode(gaode)
            assertTrue("gaode=$gaode produced $icon", icon in 0..HudInstrumentIcons.MAX_GLYPH)
        }
    }
}
