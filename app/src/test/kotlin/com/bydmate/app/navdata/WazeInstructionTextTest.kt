package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Waze instruction text -> GAODE (NavManeuverCodes.fromInstructionText). Ported from the
 *  Sea Lion 07 fork; the Yandex a11y/notification tables are covered by NavManeuverCodesTest. */
class WazeInstructionTextTest {

    private fun gaode(text: String?) = NavManeuverCodes.fromInstructionText(text)

    @Test fun `waze package is a guidance source next to yandex`() {
        assertTrue(NavPackages.isWazePackage("com.waze"))
        assertTrue("com.waze" in NavPackages.GUIDANCE_SOURCES)
        assertTrue("ru.yandex.yandexnavi" in NavPackages.GUIDANCE_SOURCES)
        assertFalse(NavPackages.isWazePackage("ru.yandex.yandexnavi"))
        assertTrue(NavPackages.isYandexPackage("ru.yandex.yandexmaps"))
    }

    @Test fun `null and blank map to unknown`() {
        assertEquals(0, gaode(null))
        assertEquals(0, gaode(""))
        assertEquals(0, gaode("   "))
    }

    @Test fun `straight marker maps to straight`() {
        assertEquals(11, gaode(">>>"))
        assertEquals(11, gaode("Продолжайте движение прямо"))
    }

    @Test fun `russian turns map to gaode codes`() {
        assertEquals(2, gaode("Поверните направо"))
        assertEquals(1, gaode("Поворот налево"))
        assertEquals(3, gaode("Держитесь левее"))
        assertEquals(4, gaode("Плавно направо"))
        assertEquals(7, gaode("Резкий поворот налево"))
        assertEquals(8, gaode("Резко направо"))
        assertEquals(9, gaode("Развернитесь"))
        assertEquals(10, gaode("Развернитесь направо"))
    }

    @Test fun `roundabout enter exit and numbered exit`() {
        assertEquals(13, gaode("Кольцевое движение"))
        assertEquals(24, gaode("Выезд с кольца"))
        assertEquals(24, gaode("2-й съезд"))
        assertEquals(0, gaode("Take the 11th exit"))
    }

    @Test fun `english waze instructions map to gaode`() {
        assertEquals(2, gaode("Turn right onto Main Street"))
        assertEquals(1, gaode("Turn left"))
        assertEquals(3, gaode("Keep left"))
        assertEquals(4, gaode("Slight right"))
        assertEquals(11, gaode("Continue straight"))
        assertEquals(13, gaode("Enter the roundabout"))
        assertEquals(24, gaode("Take the 2nd exit"))
        assertEquals(48, gaode("You have arrived at your destination"))
        assertEquals(9, gaode("Make a U-turn"))
        assertEquals(10, gaode("Make a U-turn to the right"))
    }

    @Test fun `short uppercase waze arrow descriptions map to turns`() {
        assertEquals(1, gaode("LEFT"))
        assertEquals(2, gaode("RIGHT"))
    }

    @Test fun `symbolic waze arrow tags are parsed without accepting road prose`() {
        assertEquals(2, gaode("TURN_RIGHT"))
        assertEquals(1, gaode("NAVIGATION_TURN_LEFT"))
        assertEquals(4, gaode("KEEP_RIGHT"))
        assertEquals(2, gaode("ic_turn_right_24"))
        assertEquals(2, gaode("TURN_RIGHT_THEN_LEFT"))
        assertEquals(0, gaode("Left Bank Road"))
    }

    @Test fun `chinese and czech waze directions map to hud codes`() {
        assertEquals(1, gaode("向左转"))
        assertEquals(2, gaode("向右转"))
        assertEquals(11, gaode("直行"))
        assertEquals(9, gaode("掉头"))
        assertEquals(1, gaode("Odbočte vlevo"))
        assertEquals(4, gaode("Mírně vpravo"))
        assertEquals(11, gaode("Pokračujte rovně"))
    }

    @Test fun `compound instruction selects first maneuver by textual position`() {
        assertEquals(2, gaode("Turn right, then turn left"))
        assertEquals(1, gaode("Turn left, then turn right"))
        assertEquals(4, gaode("Slight right, then turn left"))
        assertEquals(10, gaode("Make a U-turn to the right, then keep left"))
        assertEquals(24, gaode("At the roundabout, take the 2nd exit, then turn left"))
        assertEquals(2, gaode("Turn right, then take the 2nd exit"))
    }

    @Test fun `diagnostic result contains only recognized maneuver sequence`() {
        val result = NavManeuverCodes.parseInstructionText("Turn right onto Secret Road, then turn left")
        assertEquals(listOf(2, 1), result.recognizedCodes)
        assertEquals("recognized=RIGHT>LEFT selected=RIGHT gaode=2", result.diagnosticSummary())
        assertFalse("Secret Road" in result.diagnosticSummary())
    }

    @Test fun `road names and engagement copy are not maneuvers`() {
        assertEquals(0, gaode("Left Bank Road"))
        assertEquals(0, gaode("Правый берег"))
        assertEquals(0, gaode("Your destination is waiting"))
    }

    @Test fun `nbsp and narrow nbsp inside phrase are normalized`() {
        assertEquals(7, gaode("Резкий поворот налево"))
        assertEquals(24, gaode("Съезд с кольца"))
        assertEquals(1, gaode("Turn left"))
        assertEquals(9, gaode("Make a U‑turn"))
    }

    @Test fun `directional gate excludes lifecycle codes`() {
        assertTrue(NavManeuverCodes.isDirectionalManeuver(NavManeuverCodes.GAODE_LEFT))
        assertTrue(NavManeuverCodes.isDirectionalManeuver(NavManeuverCodes.GAODE_ROUNDABOUT_EXIT))
        assertFalse(NavManeuverCodes.isDirectionalManeuver(NavManeuverCodes.GAODE_ARRIVE))
        assertFalse(NavManeuverCodes.isDirectionalManeuver(NavManeuverCodes.GAODE_TUNNEL))
        assertFalse(NavManeuverCodes.isDirectionalManeuver(0))
    }

    @Test fun `yandex tables are untouched by the waze parser`() {
        assertEquals(2, NavManeuverCodes.fromA11yDescription("Поверните направо"))
        assertEquals(26, NavManeuverCodes.fromA11yDescription("2-й съезд"))
        assertEquals(1, NavManeuverCodes.fromNotificationRes("notification_left_sdl"))
    }
}
