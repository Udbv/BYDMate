package com.bydmate.app.cluster

import com.bydmate.app.data.remote.DiParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #5: what the cluster power card shows, and where its window goes. */
class ClusterPowerCardTest {

    private fun data(batteryPowerW: Double?, front: Float? = null, rear: Float? = null) = DiParsData(
        speed = null, soc = null, mileage = null, power = null,
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
        batteryPowerW = batteryPowerW, motorCurrentFront = front, motorCurrentRear = rear,
    )

    @Test fun `the battery power is cut between the axles in the motor current ratio`() {
        // Tang L cruise: front motor nearly idle, rear carrying the car.
        val state = powerCardState(
            data(40_000.0, 10f, 90f),
        )
        assertEquals(40.0, state.totalKw!!, 0.01)
        assertEquals(10, state.frontPercent)
        assertEquals(4.0, state.frontKw!!, 0.01)
        assertEquals(36.0, state.rearKw!!, 0.01)
    }

    @Test fun `regeneration turns the card green and keeps the split`() {
        val state = powerCardState(
            data(-25_000.0, -5f, -45f),
        )
        assertTrue(state.regen)
        assertEquals(-22.5, state.rearKw!!, 0.01)
    }

    @Test fun `no motor pair gives no split and standing still is idle`() {
        assertNull(powerCardState(data(1_000.0)).frontPercent)
        assertTrue(powerCardState(data(500.0, 0f, 0.1f)).idle)
    }

    @Test fun `the window stays on the panel whatever the percentages`() {
        assertEquals(CardGeometry(1344, 180, 499, 360), cardGeometry(1920, 720, CardLayout(70, 25, 26, 50)))
        // Pushed past the right and bottom edges: clamped back on.
        val g = cardGeometry(1920, 720, CardLayout(100, 100, 30, 30))
        assertEquals(1920 - g.width, g.x)
        assertEquals(720 - g.height, g.y)
    }
}
