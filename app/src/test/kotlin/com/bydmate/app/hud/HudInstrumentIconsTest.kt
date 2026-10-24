package com.bydmate.app.hud

import com.bydmate.app.navdata.NavManeuverCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the symptoms seen in the car on 3.16.0-dev.2 — a detour glyph for
 * roundabouts, a left arrow for a slight right — and for the openbyd rule that replaced the
 * blanking BYDMate used to do: an unmappable maneuver draws a straight arrow, never an empty box.
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

    @Test fun `entering or leaving a roundabout draws the plain roundabout, not a detour`() {
        val enter = HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER)
        val exit = HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ROUNDABOUT_EXIT)
        // openbyd's `directions_roundabout` glyph, the same one it uses when no exit number is
        // known. BYDMate used to pick 23, which is a different drawing.
        assertEquals(HudInstrumentIcons.ROUNDABOUT_PLAIN, enter)
        assertEquals(HudInstrumentIcons.ROUNDABOUT_PLAIN, exit)
        assertEquals(25, enter)
        // 13 is the panel's detour-right glyph and must never be selected for a roundabout.
        assertTrue(enter != NavManeuverCodes.GAODE_ROUNDABOUT_ENTER)
    }

    @Test fun `numbered roundabout exits line up one for one`() {
        for (n in 1..10) {
            val gaode = NavManeuverCodes.GAODE_ROUNDABOUT_EXIT + n
            assertEquals("exit $n", 24 + n, HudInstrumentIcons.fromGaode(gaode))
        }
    }

    @Test fun `clockwise exits are not reachable from gaode and come from the panel code instead`() {
        // 35..44 are the left-hand-traffic exits. No Gaode number maps there, so a code in that
        // range is not a clockwise exit, it is an unknown - and unknown means straight.
        assertEquals(HudInstrumentIcons.STRAIGHT, HudInstrumentIcons.fromGaode(35))
        assertEquals("TURN_ICON_ROUNDABOUT_CW_1_EXIT", HudInstrumentIcons.name(35))
    }

    @Test fun `arrival draws the destination glyph`() {
        // The panel spells it in Chinese characters. openbyd shows it anyway; an unmistakable
        // glyph in the wrong script beats an empty box at the moment of arrival.
        assertEquals(
            HudInstrumentIcons.DESTINATION_CHINESE,
            HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ARRIVE),
        )
        assertEquals(48, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_ARRIVE))
    }

    @Test fun `a waypoint draws the stop glyph and a ferry falls back to straight`() {
        assertEquals(HudInstrumentIcons.STOP_LEFT, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_WAYPOINT))
        assertEquals(45, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_WAYPOINT))
        // 46 is parking/cafe on the panel, which is not a ferry; the donor sends straight.
        assertEquals(HudInstrumentIcons.STRAIGHT, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_FERRY))
    }

    @Test fun `toll and tunnel keep their glyphs`() {
        assertEquals(47, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_TOLL))
        assertEquals(49, HudInstrumentIcons.fromGaode(NavManeuverCodes.GAODE_TUNNEL))
    }

    @Test fun `no maneuver and unknown codes draw a straight arrow, never a blank`() {
        assertEquals(HudInstrumentIcons.STRAIGHT, HudInstrumentIcons.fromGaode(0))
        assertEquals(HudInstrumentIcons.STRAIGHT, HudInstrumentIcons.fromGaode(999))
        assertEquals(HudInstrumentIcons.STRAIGHT, HudInstrumentIcons.fromGaode(-1))
    }

    @Test fun `every produced glyph is within the panel table and never blank`() {
        for (gaode in -5..60) {
            val icon = HudInstrumentIcons.fromGaode(gaode)
            assertTrue("gaode=$gaode produced $icon", icon in 1..HudInstrumentIcons.MAX_GLYPH)
        }
    }

    @Test fun `glyph names match the donor table`() {
        assertEquals("TURN_ICON_LEFT", HudInstrumentIcons.name(1))
        assertEquals("TURN_ICON_STRAIGHT_SOLID", HudInstrumentIcons.name(11))
        assertEquals("TURN_ICON_DETOUR_RIGHT", HudInstrumentIcons.name(13))
        assertEquals("TURN_ICON_ROUNDABOUT_CCW_1_EXIT", HudInstrumentIcons.name(25))
        assertEquals("TURN_ICON_ROUNDABOUT_CCW_10_EXIT", HudInstrumentIcons.name(34))
        assertEquals("TURN_ICON_ROUNDABOUT_CW_10_EXIT", HudInstrumentIcons.name(44))
        assertEquals("TURN_ICON_STOP_LEFT", HudInstrumentIcons.name(45))
        assertEquals("TURN_ICON_DESTINATION_CHINESE", HudInstrumentIcons.name(48))
        assertEquals("TURN_ICON_TUNNEL", HudInstrumentIcons.name(49))
        assertEquals("UNKNOWN (0)", HudInstrumentIcons.name(0))
        assertEquals("UNKNOWN (50)", HudInstrumentIcons.name(50))
    }
}
