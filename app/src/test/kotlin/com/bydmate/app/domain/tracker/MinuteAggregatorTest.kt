package com.bydmate.app.domain.tracker

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MinuteAggregatorTest {

    private fun data(
        kw: Double,
        front: Float? = 30f,
        rear: Float? = 70f,
        compressorW: Int? = 1200,
        lvAmps: Int? = 50,
    ) = diParsData(power = kw, voltage12v = 13.8, speed = 60, soc = 80).copy(
        motorCurrentFront = front, motorCurrentRear = rear, compressorW = compressorW, lvCurrentA = lvAmps,
    )

    @Test fun `a minute of steady drive splits the motors' energy by the axle currents`() {
        val agg = MinuteAggregator()
        for (s in 0 until 60) assertNull(agg.add(s * 1000L, data(30.0)))
        val row = agg.add(60_000L, data(30.0))!!
        assertEquals(0L, row.minuteTs)
        assertEquals(60, row.samples)
        val other = 50 * 13.8 / 1000 / 0.92
        val motors = (30.0 - 1.2 - other) / 60
        assertEquals(motors, row.motorsKwh, 1e-6)
        assertEquals(motors * 0.3, row.frontKwh!!, 1e-6)
        assertEquals(motors * 0.7, row.rearKwh!!, 1e-6)
        assertEquals(1.2 / 60, row.climateKwh, 1e-6)
        assertEquals(other / 60, row.otherKwh, 1e-6)
        assertEquals(0.0, row.regenKwh, 1e-9)
        assertEquals(60.0, row.avgSpeed!!, 1e-9)
    }

    @Test fun `regen goes below zero and the extremes are kept`() {
        val agg = MinuteAggregator()
        agg.add(0L, data(80.0))
        agg.add(1000L, data(-40.0))
        agg.add(2000L, data(-40.0))
        val row = agg.flush()!!
        assertEquals(80.0, row.peakKw, 1e-9)
        assertEquals(-40.0, row.minKw, 1e-9)
        assertEquals(true, row.regenKwh < 0)
        assertNull(agg.flush())
    }

    @Test fun `wiper ticks and the outside temperature are kept per minute`() {
        val agg = MinuteAggregator()
        agg.add(0L, data(30.0).copy(wiperRelay = 0, exteriorTemp = 2))
        agg.add(1000L, data(30.0).copy(wiperRelay = 1, exteriorTemp = 4))
        agg.add(2000L, data(30.0).copy(wiperRelay = 2, exteriorTemp = null))
        val row = agg.flush()!!
        assertEquals(2, row.wiperSamples)
        assertEquals(3.0, row.extTemp!!, 1e-9)
    }

    @Test fun `a car that does not report the wipers leaves them unknown`() {
        val agg = MinuteAggregator()
        agg.add(0L, data(30.0).copy(wiperRelay = null, exteriorTemp = null))
        val row = agg.flush()!!
        assertNull(row.wiperSamples)
        assertNull(row.extTemp)
    }

    @Test fun `a car without the current pair leaves the axles unknown`() {
        val agg = MinuteAggregator()
        agg.add(0L, data(30.0, front = null, rear = null))
        val row = agg.flush()!!
        assertNull(row.frontKwh)
        assertNotNull(row.motorsKwh)
    }

    @Test fun `a gap counts as one tick, not as power held`() {
        val agg = MinuteAggregator()
        agg.add(0L, data(36.0, compressorW = null, lvAmps = null))
        agg.add(40_000L, data(36.0, compressorW = null, lvAmps = null))
        val row = agg.flush()!!
        assertEquals(36.0 * (1_000 + 5_000) / 3_600_000.0, row.motorsKwh, 1e-9)
    }

    @Test fun `a plugged car is charging, not driving`() {
        assertEquals(true, isPluggedIn(data(-40.0).copy(chargeGunState = 2)))
        assertEquals(true, isPluggedIn(data(-40.0).copy(bmsState = 1)))
        assertEquals(false, isPluggedIn(data(-40.0).copy(chargeGunState = 1, bmsState = 2)))
    }

    @Test fun `a standing minute that took energy was a charger, a moving one was regen`() {
        val agg = MinuteAggregator()
        for (s in 0 until 60) agg.add(s * 1000L, data(-40.0).copy(speed = 0))
        assertEquals(true, agg.flush()!!.looksLikeCharging())
        for (s in 0 until 60) agg.add(s * 1000L, data(-40.0).copy(speed = 30))
        assertEquals(false, agg.flush()!!.looksLikeCharging())
    }
}
