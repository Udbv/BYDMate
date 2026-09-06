package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazeGuidanceParserTest {

    private fun fields(
        maneuver: String? = null, distance: String? = null, street: String? = null,
        remainingDistance: String? = null, remainingTime: String? = null,
        arrivalTime: String? = null, speedLimit: String? = null, exitNumber: String? = null,
    ) = WazeAccessibilityReader.Fields(
        maneuver = maneuver, maneuverDistance = distance, street = street,
        remainingDistance = remainingDistance, remainingTime = remainingTime,
        arrivalTime = arrivalTime, speedLimit = speedLimit, exitNumber = exitNumber,
    )

    @Test fun `eta alone is not guidance`() {
        assertNull(WazeGuidanceParser.parse(fields(remainingTime = "27 min", speedLimit = "60")))
    }

    @Test fun `metric distance and english instruction`() {
        val d = WazeGuidanceParser.parse(fields(maneuver = "Turn right onto Main St", distance = "250 m", street = "Main St"))!!
        assertEquals(2, d.maneuverGaode)
        assertEquals(250, d.distanceMeters)
        assertEquals("Main St", d.road)
    }

    @Test fun `imperial distances convert to meters`() {
        assertEquals(4988, WazeGuidanceParser.parse(fields(maneuver = "Keep left", distance = "3.1 mi"))!!.distanceMeters)
        assertEquals(152, WazeGuidanceParser.parse(fields(maneuver = "Keep left", distance = "500 ft"))!!.distanceMeters)
        assertEquals(1200, WazeGuidanceParser.parse(fields(maneuver = ">>>", distance = "1,2 км"))!!.distanceMeters)
    }

    @Test fun `unit-less distance is meters`() {
        assertEquals(350, WazeGuidanceParser.parse(fields(maneuver = "Turn left", distance = "350"))!!.distanceMeters)
    }

    @Test fun `numbered exit maps to per-exit roundabout code`() {
        assertEquals(26, WazeGuidanceParser.parse(fields(maneuver = "Turn right", exitNumber = "2", distance = "300 m"))!!.maneuverGaode)
        assertEquals(24, WazeGuidanceParser.parse(fields(maneuver = "Take the 12th exit", exitNumber = "12", distance = "300 m"))!!.maneuverGaode)
        assertEquals(27, WazeGuidanceParser.resolveManeuver("At the roundabout, take the 3rd exit", "3"))
    }

    @Test fun `remaining time in english and russian`() {
        assertEquals(27 * 60, WazeGuidanceParser.parse(fields(maneuver = ">>>", remainingTime = "27 min"))!!.etaSeconds)
        assertEquals(3900, WazeGuidanceParser.parse(fields(maneuver = ">>>", remainingTime = "1 hr 5 mins"))!!.etaSeconds)
        assertEquals(3900, WazeGuidanceParser.parseDurationSeconds("1 ч 5 мин"))
        assertEquals(7200, WazeGuidanceParser.parseDurationSeconds("2 h"))
        assertEquals(0, WazeGuidanceParser.parseDurationSeconds("15:40"))
    }

    @Test fun `total distance from remaining distance label`() {
        assertEquals(28000, WazeGuidanceParser.parse(fields(maneuver = ">>>", remainingDistance = "28 km"))!!.totalDistMeters)
        assertEquals(800, WazeGuidanceParser.parse(fields(maneuver = ">>>", remainingDistance = "800 м"))!!.totalDistMeters)
    }

    @Test fun `speed limit parsed and bounded`() {
        assertEquals(60, WazeGuidanceParser.parse(fields(maneuver = ">>>", speedLimit = "60"))!!.speedLimit)
        assertEquals(130, WazeGuidanceParser.parse(fields(maneuver = ">>>", speedLimit = "130 km/h"))!!.speedLimit)
        assertEquals(0, WazeGuidanceParser.parse(fields(maneuver = ">>>", speedLimit = "9999"))!!.speedLimit)
    }

    @Test fun `distance text picks the first unit and rejects junk`() {
        assertEquals(500, WazeGuidanceParser.parseDistanceText("In 500 m, turn right"))
        assertEquals(500, WazeGuidanceParser.parseDistanceText("In 500 m, 12 km remaining"))
        assertEquals(1200, WazeGuidanceParser.parseDistanceText("In 1.2 km — keep left"))
        assertEquals(0, WazeGuidanceParser.parseDistanceText("скоро"))
        assertEquals(0, WazeGuidanceParser.parseDistanceText(null))
        assertEquals(0, WazeGuidanceParser.parseDistanceText("-500 m"))
        assertEquals(0, WazeGuidanceParser.parseDistanceText("0.5 m"))
    }

    @Test fun `implausible values fail soft`() {
        assertEquals(0, WazeGuidanceParser.parseDistanceText("999999999999999999999 km"))
        assertEquals(0, WazeGuidanceParser.parseDurationSeconds("999999999999999999999 h"))
        assertEquals(0, WazeGuidanceParser.parseDurationSeconds("-15 min"))
        val parsed = WazeGuidanceParser.parse(fields(
            maneuver = "Turn right", distance = "999999999999999999999", remainingTime = "999999999999999999999 min",
        ))!!
        assertEquals(0, parsed.distanceMeters)
        assertEquals(0, parsed.etaSeconds)
    }
}
