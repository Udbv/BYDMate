package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * While Waze's lane strip covers the arrow, the arrow read just before is kept, but only for as
 * long as nothing else named the manoeuvre (2026-10-31 13:45: roundabout and "keep straight"
 * alternated on the HUD every second).
 */
class WazeLaneArrowKeepTest {

    @Before fun reset() = NavGuidanceHub.reset()

    private val fromLanes = WazeVisualManeuverReader.Classification(0, null, null, "", fromLanes = true)

    @Test fun `the strip keeps the arrow read before it`() {
        NavGuidanceHub.update(NavGuidance(distanceMeters = 900), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.updateWazeArrow(panelIcon = 27, exitNumber = 3, nowMs = 1000)
        NavA11yFeed.applyWazeArrow(fromLanes, exitNumber = 3, nowMs = 20_000)
        val s = NavGuidanceHub.snapshot(25_000)
        assertEquals(27, s.panelIcon)
        assertEquals(NavGuidanceHub.gaodeOf(27), s.maneuverGaode)
    }

    @Test fun `a manoeuvre named after the arrow is not overridden by it`() {
        NavGuidanceHub.update(NavGuidance(distanceMeters = 900), NavGuidanceHub.Source.A11Y, nowMs = 1000)
        NavGuidanceHub.updateWazeArrow(panelIcon = 27, exitNumber = 3, nowMs = 1000)
        NavGuidanceHub.update(NavGuidance(maneuverGaode = 11, distanceMeters = 490), NavGuidanceHub.Source.A11Y, nowMs = 2000)
        NavA11yFeed.applyWazeArrow(fromLanes, exitNumber = null, nowMs = 2500)
        assertEquals(11, NavGuidanceHub.snapshot(2600).maneuverGaode)
    }
}
