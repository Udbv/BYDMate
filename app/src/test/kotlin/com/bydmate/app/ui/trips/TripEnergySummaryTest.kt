package com.bydmate.app.ui.trips

import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.domain.cost.TripEnergyPrice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripEnergySummaryTest {

    private fun minute(ts: Long, regen: Double = 0.0, peak: Double = 60.0) = TripMinuteEntity(
        minuteTs = ts, samples = 60, motorsKwh = 0.4, frontKwh = 0.1, rearKwh = 0.3, regenKwh = regen,
        climateKwh = 0.02, otherKwh = 0.01, peakKw = peak, minKw = -10.0, avgSpeed = 80.0, maxSpeed = 80, soc = 70,
    )

    @Test fun `sums the axles, keeps unsplit traction apart and finds the extremes`() {
        val s = TripEnergySummary.of(
            listOf(
                minute(0, peak = 90.0),
                minute(60_000, regen = -0.2).copy(frontKwh = null, rearKwh = null, motorsKwh = 0.5, minKw = -45.0, maxSpeed = 110),
            ),
        )!!
        assertEquals(0.1, s.frontKwh, 1e-9)
        assertEquals(0.3, s.rearKwh, 1e-9)
        assertEquals(0.5, s.motorsUnsplitKwh, 1e-9)
        assertEquals(-0.2, s.regenKwh, 1e-9)
        assertEquals(AtMinute(90.0, 0L), s.peakKw)
        assertEquals(AtMinute(-45.0, 60_000L), s.regenPeakKw)
        assertEquals(AtMinute(110, 60_000L), s.maxSpeed)
        assertEquals(0.96, s.usedKwh, 1e-9)
    }

    @Test fun `cost uses the trip's own kWh when known, the minutes otherwise`() {
        val s = TripEnergySummary.of(listOf(minute(0, regen = -0.1)))!!
        val price = TripEnergyPrice(5.0, 0.3)
        assertEquals(50.0, s.cost(10.0, price)!!, 1e-9)
        assertEquals((0.43 - 0.1) * 5.0, s.cost(null, price)!!, 1e-9)
        assertEquals(0.5, s.regenSaving(price)!!, 1e-9)
        assertNull(s.cost(10.0, null))
    }

    @Test fun `no minutes, no summary`() {
        assertNull(TripEnergySummary.of(emptyList()))
    }
}
