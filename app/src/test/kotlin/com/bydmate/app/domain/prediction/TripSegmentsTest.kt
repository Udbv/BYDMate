package com.bydmate.app.domain.prediction

import com.bydmate.app.data.local.entity.TripMinuteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripSegmentsTest {

    private fun minute(i: Int, kwh: Double, speed: Double, temp: Double? = null, wipers: Int? = 0) = TripMinuteEntity(
        minuteTs = i * 60_000L, samples = 60, motorsKwh = kwh, frontKwh = null, rearKwh = null, regenKwh = 0.0,
        climateKwh = 0.0, otherKwh = 0.0, peakKw = 0.0, minKw = 0.0, avgSpeed = speed, maxSpeed = null, soc = null,
        wiperSamples = wipers, extTemp = temp,
    )

    private val overall = TripConditions(0.0, TripWeather.DRY, 60.0)

    @Test
    fun `minutes keep their own temperature and are scaled to the drive's totals`() {
        val minutes = (0 until 10).map { minute(it, 0.2, 60.0, temp = -5.0 + it) }
        val segs = TripSegments.of(distanceKm = 11.0, kwh = 2.2, overall = overall, minutes = minutes, altitudes = emptyList())
        assertEquals(10, segs.size)
        assertEquals(11.0, segs.sumOf { it.km }, 1e-9)
        assertEquals(2.2, segs.sumOf { it.kwh }, 1e-9)
        assertEquals(-5.0, segs.first().conditions.tempC!!, 1e-9)
        assertEquals(4.0, segs.last().conditions.tempC!!, 1e-9)
        val p = TripSegments.profile(segs)
        assertEquals(-5.0, p.tempMinC!!, 1e-9)
        assertEquals(4.0, p.tempMaxC!!, 1e-9)
    }

    @Test
    fun `minutes that disagree with the drive's counters fall back to one segment`() {
        val minutes = (0 until 10).map { minute(it, 0.2, 60.0) }
        val segs = TripSegments.of(distanceKm = 40.0, kwh = 2.0, overall = overall, minutes = minutes, altitudes = emptyList())
        assertEquals(1, segs.size)
        assertEquals(overall, segs.single().conditions)
    }

    @Test
    fun `wipers within two minutes make a minute wet, snow when cold`() {
        val minutes = (0 until 8).map { minute(it, 0.2, 60.0, temp = 0.0, wipers = if (it == 3) 2 else 0) }
        val segs = TripSegments.of(8.0, 1.6, overall, minutes, emptyList())
        assertEquals(TripWeather.SNOW, segs[1].conditions.weather)
        assertEquals(TripWeather.SNOW, segs[5].conditions.weather)
        assertEquals(TripWeather.DRY, segs[0].conditions.weather)
        assertEquals(TripWeather.DRY, segs[6].conditions.weather)
    }

    @Test
    fun `a pass up and down is climbed and descended, not netted out`() {
        val minutes = (0 until 9).map { minute(it, 0.2, 60.0) }
        // 0, 50, 100 ... 200 at minute 4, then back down to 0.
        val alts = (0 until 9).map { MinuteAltitude(it * 60_000L, (if (it <= 4) it else 8 - it) * 50.0) }
        val segs = TripSegments.of(9.0, 1.8, overall, minutes, alts)
        val p = TripSegments.profile(segs)
        assertTrue(p.ascentM > 120)
        assertTrue(p.descentM < -120)
        // Up costs more than down gives back.
        assertTrue(segs.sumOf { it.climbKwh } > 0)
    }

    @Test
    fun `no track height leaves the minutes without a climb`() {
        val minutes = (0 until 3).map { minute(it, 0.2, 60.0) }
        assertTrue(TripSegments.minuteClimbs(minutes, emptyList()).all { it == null })
        assertNull(TripSegments.profile(TripSegments.of(3.0, 0.6, TripConditions(null, null, null), minutes, emptyList())).tempMinC)
    }
}
