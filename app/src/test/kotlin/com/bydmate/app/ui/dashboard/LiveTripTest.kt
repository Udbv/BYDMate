package com.bydmate.app.ui.dashboard

import com.bydmate.app.data.local.entity.TripMinuteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The drive under way on Главная (#42). */
class LiveTripTest {

    private fun minute(ts: Long, motors: Double, regen: Double, speed: Int = 60) = TripMinuteEntity(
        minuteTs = ts, samples = 30, motorsKwh = motors, frontKwh = null, rearKwh = null,
        regenKwh = regen, climateKwh = 0.02, otherKwh = 0.01, peakKw = 40.0, minKw = -20.0,
        avgSpeed = speed.toDouble(), maxSpeed = speed, soc = 70,
    )

    @Test fun `no session or no distance yet is no live trip`() {
        assertNull(LiveTrip.of(LiveSession(null, 12.0, 20.0, 5), 0, emptyList()))
        assertNull(LiveTrip.of(LiveSession(0, 0.05, 20.0, 5), 60_000, emptyList()))
        assertNull(LiveTrip.of(LiveSession(0, null, 20.0, 5), 60_000, emptyList()))
    }

    @Test fun `the row carries the session's km and consumption, the minutes its regeneration`() {
        val start = 1_000_000L
        val minutes = listOf(minute(start, 0.3, -0.05), minute(start + 60_000, 0.25, -0.10))
        val live = LiveTrip.of(LiveSession(start, 15.0, 22.0, -3), start + 20 * 60_000, minutes)!!
        assertEquals(start, live.trip.startTs)
        assertEquals(15.0, live.trip.distanceKm!!, 1e-9)
        assertEquals(3.3, live.trip.kwhConsumed!!, 1e-9)
        assertEquals(22.0, live.trip.kwhPer100km!!, 1e-9)
        assertEquals(-0.15, live.regenKwh!!, 1e-9)
        assertEquals(-3, live.extTemp)
    }

    @Test fun `without the session consumption the minutes give the energy, charging minutes left out`() {
        val start = 0L
        val charging = minute(60_000, 0.0, -1.0, speed = 0)
        val live = LiveTrip.of(LiveSession(start, 2.0, null, null), 120_000, listOf(minute(0, 0.4, -0.1), charging))!!
        assertEquals(0.4 + 0.02 + 0.01 - 0.1, live.trip.kwhConsumed!!, 1e-9)
        assertEquals(-0.1, live.regenKwh!!, 1e-9)
    }
}
