package com.bydmate.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPlannerTest {

    @Test fun `consumption grows with speed and with cold`() {
        val p90 = SpeedPlanner.per100(90, 10.0)
        val p130 = SpeedPlanner.per100(130, 10.0)
        // About 19 and 29 kWh/100 for a Tang L at +10 C.
        assertEquals(19.4, p90, 1.0)
        assertEquals(29.2, p130, 1.5)
        assertTrue(SpeedPlanner.per100(110, -10.0) > SpeedPlanner.per100(110, 15.0))
    }

    @Test fun `500 km from full, faster wins on 160 kW chargers despite more stops`() {
        val plans = SpeedPlanner.CRUISE_SPEEDS.map {
            SpeedPlanner.plan(500.0, it, PlanStart(90.0, 90.0, 10.0, 20.0))
        }
        val (p90, p110, p130) = plans
        assertTrue(p90.stops <= p110.stops && p110.stops <= p130.stops)
        assertTrue(p130.totalMin < p90.totalMin)
        assertTrue(p130.energyKwh > p90.energyKwh)
        assertTrue(p130.cost!! > p90.cost!!)
    }

    @Test fun `a short trip charges nothing`() {
        val p = SpeedPlanner.plan(100.0, 110, PlanStart(80.0, 90.0, 10.0, 20.0))
        assertEquals(0, p.stops)
        assertEquals(0.0, p.chargeMin, 1e-9)
        assertEquals(0.0, p.chargeKwh, 1e-9)
    }

    @Test fun `one stop never charges more than 10 to 80 percent`() {
        val p = SpeedPlanner.plan(800.0, 130, PlanStart(90.0, 90.0, 10.0, null))
        assertTrue(p.chargeKwh <= p.stops * 0.7 * 90.0 + 1e-9)
        assertEquals(null, p.cost)
    }
}
