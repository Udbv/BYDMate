package com.bydmate.app.domain.calculator

import com.bydmate.app.data.remote.DiParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerHistoryTest {

    private fun data(power: Double? = null, batteryPowerW: Double? = null) = DiParsData(
        speed = null, soc = null, mileage = null, power = power,
        chargeGunState = null, maxBatTemp = null, avgBatTemp = null,
        minBatTemp = null, chargingStatus = null, batteryCapacityKwh = null,
        totalElecConsumption = null, voltage12v = null, maxCellVoltage = null,
        minCellVoltage = null, exteriorTemp = null, gear = null, powerState = null,
        insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null,
        sunroof = null, trunk = null, hood = null, seatbeltFL = null,
        lockFL = null, tirePressFL = null, tirePressFR = null,
        tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
        batteryPowerW = batteryPowerW,
    )

    @Test
    fun `battery power wins over the whole-kW motor reading`() {
        assertEquals(42.5, powerKw(data(power = 40.0, batteryPowerW = 42_500.0))!!, 1e-9)
        assertEquals(-12.0, powerKw(data(power = -12.0))!!, 1e-9)
        assertNull(powerKw(data()))
    }

    @Test
    fun `implausible readings are dropped`() {
        assertNull(powerKw(data(power = 5_000.0)))
        assertNull(powerKw(data(batteryPowerW = -1_000_000.0)))
    }

    @Test
    fun `old samples fall out of the window`() {
        var h = emptyList<PowerSample>()
        for (t in 0L..130L) h = appendPowerSample(h, PowerSample(t * 1_000L, t.toDouble()))
        assertEquals(121, h.size)
        assertEquals(10_000L, h.first().atMs)
    }

    @Test
    fun `a gap or a clock jump back starts a fresh graph`() {
        val h = listOf(PowerSample(0L, 1.0), PowerSample(1_000L, 2.0))
        assertEquals(listOf(PowerSample(20_000L, 3.0)), appendPowerSample(h, PowerSample(20_000L, 3.0)))
        assertEquals(listOf(PowerSample(500L, 3.0)), appendPowerSample(h, PowerSample(500L, 3.0)))
        assertEquals(3, appendPowerSample(h, PowerSample(2_000L, 3.0)).size)
    }

    @Test
    fun `scale has floors and rounds to 10 kW`() {
        assertEquals(PowerScale(20.0, 10.0), powerScale(emptyList()))
        assertEquals(
            PowerScale(90.0, 40.0),
            powerScale(listOf(PowerSample(0, 83.0), PowerSample(1, -31.5), PowerSample(2, 5.0))),
        )
    }

    @Test
    fun `zero line sits proportionally and values clamp to the box`() {
        val s = PowerScale(topKw = 30.0, bottomKw = 10.0)
        assertEquals(0.75f, s.yFraction(0.0), 1e-6f)
        assertEquals(0f, s.yFraction(500.0), 1e-6f)
        assertEquals(1f, s.yFraction(-500.0), 1e-6f)
    }

    @Test
    fun `newest sample is at the right edge`() {
        assertEquals(1f, powerXFraction(100_000L, 100_000L), 1e-6f)
        assertEquals(0.5f, powerXFraction(40_000L, 100_000L), 1e-6f)
        assertEquals(0f, powerXFraction(0L, 200_000L), 1e-6f)
    }
}
