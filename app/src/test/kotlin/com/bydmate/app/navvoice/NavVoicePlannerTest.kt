package com.bydmate.app.navvoice

import com.bydmate.app.navdata.NavManeuverCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When and what BYDMate says for a Waze maneuver. */
class NavVoicePlannerTest {

    private val planner = NavVoicePlanner()
    private var clock = 0L

    private fun at(m: Int, gaode: Int = NavManeuverCodes.GAODE_RIGHT, road: String = "вул. Шевченка", exit: Int? = null): String? {
        clock += 5_000
        return planner.next(NavVoiceInput(true, gaode, m, road, exit), clock)
    }

    /** Drives toward the maneuver from [from] to 0 in 50 m steps, every phrase said. */
    private fun approach(from: Int, gaode: Int = NavManeuverCodes.GAODE_RIGHT, road: String = "вул. Шевченка"): List<String> =
        (from downTo 0 step 50).mapNotNull { at(it.coerceAtLeast(1), gaode, road) }

    @Test fun `a far maneuver gets three calls, each once`() {
        assertEquals(
            listOf(
                "Через километр поверните направо на вул. Шевченка",
                "Через 400 метров поверните направо на вул. Шевченка",
                "Сейчас направо",
            ),
            approach(2_000),
        )
    }

    @Test fun `a maneuver first seen nearby gets no far call`() {
        assertEquals(listOf("Через 400 метров поверните налево", "Сейчас налево"), approach(900, NavManeuverCodes.GAODE_LEFT, ""))
    }

    @Test fun `a maneuver first seen right before it gets only now`() {
        assertEquals(listOf("Сейчас налево"), approach(100, NavManeuverCodes.GAODE_LEFT, ""))
    }

    @Test fun `a new maneuver starts afresh, a jump in distance too`() {
        approach(500)
        assertEquals("Через 400 метров поверните налево на вул. Франка", at(400, NavManeuverCodes.GAODE_LEFT, "вул. Франка"))
        at(40, NavManeuverCodes.GAODE_LEFT, "вул. Франка")
        // The same turn at the next junction: the distance goes up again.
        assertEquals("Через 400 метров поверните налево на вул. Франка", at(390, NavManeuverCodes.GAODE_LEFT, "вул. Франка"))
    }

    @Test fun `two phrases are never closer than the gap`() {
        planner.next(NavVoiceInput(true, NavManeuverCodes.GAODE_RIGHT, 1_000, "", null), 0)
        planner.next(NavVoiceInput(true, NavManeuverCodes.GAODE_RIGHT, 990, "", null), 0)
        // first seen at 1000: no far call; near is due at 400 but only after the gap
        assertEquals("Через 400 метров поверните направо", planner.next(NavVoiceInput(true, NavManeuverCodes.GAODE_RIGHT, 400, "", null), 1_000))
        assertNull(planner.next(NavVoiceInput(true, NavManeuverCodes.GAODE_RIGHT, 50, "", null), 2_000))
        assertEquals("Сейчас направо", planner.next(NavVoiceInput(true, NavManeuverCodes.GAODE_RIGHT, 40, "", null), 5_500))
    }

    @Test fun `straight on, unknown codes and inactive guidance say nothing`() {
        assertEquals(emptyList<String>(), approach(2_000, NavManeuverCodes.GAODE_STRAIGHT))
        assertEquals(emptyList<String>(), approach(2_000, 99))
        assertNull(planner.next(NavVoiceInput(false, NavManeuverCodes.GAODE_RIGHT, 40, "", null), 100_000))
    }

    @Test fun `roundabout exits are named by number`() {
        assertEquals("Через 400 метров на кольце третий съезд на М-06", at(400, NavManeuverCodes.GAODE_ROUNDABOUT_EXIT + 3, "М-06"))
        assertEquals("Сейчас третий съезд", at(50, NavManeuverCodes.GAODE_ROUNDABOUT_EXIT + 3, "М-06"))
        assertEquals("Сейчас второй съезд", NavVoicePhrases.now(NavManeuverCodes.GAODE_ROUNDABOUT_EXIT, 2))
        assertNull(NavVoicePhrases.now(NavManeuverCodes.GAODE_ROUNDABOUT_EXIT, null))
    }

    @Test fun `distances are rounded the way they are said`() {
        assertEquals("километр", NavVoicePhrases.distance(980))
        assertEquals("400 метров", NavVoicePhrases.distance(377))
        assertEquals("250 метров", NavVoicePhrases.distance(240))
        assertEquals("50 метров", NavVoicePhrases.distance(10))
    }
}
