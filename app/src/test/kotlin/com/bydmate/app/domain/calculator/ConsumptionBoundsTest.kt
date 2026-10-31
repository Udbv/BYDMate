package com.bydmate.app.domain.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsumptionBoundsTest {

    private fun trip(ts: Long, km: Double, per100: Double) = ConsumptionBounds.Trip(ts, km, km * per100 / 100.0)

    @Test fun `lowest and highest 100 km stretch, not a single trip`() {
        val trips = listOf(
            // stretch 1: a 5 km heater trip at 60 is averaged with 95 km at 30
            trip(1, 5.0, 60.0), trip(2, 95.0, 30.0),
            trip(3, 100.0, 25.0),
            trip(4, 100.0, 35.0),
            trip(5, 120.0, 20.0),
        )
        val bounds = ConsumptionBounds.of(trips.shuffled())!!
        assertEquals(20.0, bounds.start, 0.01)
        assertEquals(35.0, bounds.endInclusive, 0.01)
    }

    @Test fun `too short a history gives no bounds`() {
        val trips = listOf(trip(1, 100.0, 25.0), trip(2, 100.0, 30.0), trip(3, 100.0, 35.0), trip(4, 50.0, 40.0))
        assertNull(ConsumptionBounds.of(trips))
    }
}
