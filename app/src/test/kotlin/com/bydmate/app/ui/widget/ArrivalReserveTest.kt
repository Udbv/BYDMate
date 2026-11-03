package com.bydmate.app.ui.widget

import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalReserveTest {

    @Test fun `hidden without a guided route`() {
        assertNull(arrivalReserve(rangeKm = 300.0, routeActive = false, routeMeters = 50_000))
    }

    @Test fun `hidden when the navigator shows no distance`() {
        assertNull(arrivalReserve(rangeKm = 300.0, routeActive = true, routeMeters = 0))
    }

    @Test fun `hidden without a usable range`() {
        assertNull(arrivalReserve(rangeKm = null, routeActive = true, routeMeters = 50_000))
        assertNull(arrivalReserve(rangeKm = Double.NaN, routeActive = true, routeMeters = 50_000))
        assertNull(arrivalReserve(rangeKm = Double.POSITIVE_INFINITY, routeActive = true, routeMeters = 50_000))
        assertNull(arrivalReserve(rangeKm = -1.0, routeActive = true, routeMeters = 50_000))
    }

    @Test fun `plenty left is ok and rounded down`() {
        assertEquals(
            ArrivalReserve(249, ArrivalLevel.OK),
            arrivalReserve(rangeKm = 300.0, routeActive = true, routeMeters = 50_600),
        )
    }

    @Test fun `exactly fifteen percent left is still ok`() {
        assertEquals(
            ArrivalReserve(30, ArrivalLevel.OK),
            arrivalReserve(rangeKm = 200.0, routeActive = true, routeMeters = 170_000),
        )
    }

    @Test fun `under fifteen percent left is low`() {
        assertEquals(
            ArrivalReserve(29, ArrivalLevel.LOW),
            arrivalReserve(rangeKm = 200.0, routeActive = true, routeMeters = 170_500),
        )
    }

    @Test fun `arriving on the last km is low with zero reserve`() {
        assertEquals(
            ArrivalReserve(0, ArrivalLevel.LOW),
            arrivalReserve(rangeKm = 100.0, routeActive = true, routeMeters = 100_000),
        )
    }

    @Test fun `out of reach is short and rounded up`() {
        assertEquals(
            ArrivalReserve(21, ArrivalLevel.SHORT),
            arrivalReserve(rangeKm = 100.0, routeActive = true, routeMeters = 120_200),
        )
    }

    @Test fun `a few metres short still reads one km`() {
        assertEquals(
            ArrivalReserve(1, ArrivalLevel.SHORT),
            arrivalReserve(rangeKm = 100.0, routeActive = true, routeMeters = 100_050),
        )
    }

    @Test fun `zero range with a route is short by the whole route`() {
        assertEquals(
            ArrivalReserve(5, ArrivalLevel.SHORT),
            arrivalReserve(rangeKm = 0.0, routeActive = true, routeMeters = 5_000),
        )
    }

    @Test fun `shortfall and reserve use their own strings`() {
        assertEquals(R.string.widget_arrival_short, arrivalReserveTextRes(ArrivalReserve(3, ArrivalLevel.SHORT)))
        assertEquals(R.string.widget_arrival_reserve, arrivalReserveTextRes(ArrivalReserve(3, ArrivalLevel.LOW)))
        assertEquals(R.string.widget_arrival_reserve, arrivalReserveTextRes(ArrivalReserve(30, ArrivalLevel.OK)))
    }

    @Test fun `the arrival row adds its height on top of the power row`() {
        assertEquals(WIDGET_BASE_HEIGHT_DP, widgetHeightDp(powerGraph = false))
        assertEquals(WIDGET_BASE_HEIGHT_DP + ARRIVAL_CHIP_EXTRA_HEIGHT_DP, widgetHeightDp(false, arrivalChip = true))
        assertEquals(
            WIDGET_BASE_HEIGHT_DP + POWER_GRAPH_EXTRA_HEIGHT_DP + ARRIVAL_CHIP_EXTRA_HEIGHT_DP,
            widgetHeightDp(powerGraph = true, arrivalChip = true),
        )
    }

    @Test fun `a distance read within the freshness window counts`() {
        assertEquals(50_000, freshRouteMeters(50_000, readAtMs = 1_000, nowMs = 1_000 + ARRIVAL_DISTANCE_FRESH_MS))
    }

    @Test fun `a held distance older than the window hides the chip`() {
        val meters = freshRouteMeters(50_000, readAtMs = 1_000, nowMs = 1_001 + ARRIVAL_DISTANCE_FRESH_MS)
        assertEquals(0, meters)
        assertNull(arrivalReserve(rangeKm = 300.0, routeActive = true, routeMeters = meters))
    }

    @Test fun `a distance never read counts as none`() {
        assertEquals(0, freshRouteMeters(50_000, readAtMs = 0L, nowMs = 5_000))
    }
}
