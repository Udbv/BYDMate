package com.bydmate.app.data.trips

import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.diParsData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-14: the car was on for a 40-min warm-up (plugged in, odometer still) before the drive.
 * The trip starts at the first odometer movement, with the SoC and energy of the last standing tick.
 */
class TripRecorderWarmUpTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val lastState = mockk<LastStateDao>(relaxed = true).also {
        coEvery { it.openTrip(any(), any(), any(), any(), any(), any()) } returns 1
    }

    private fun recorder(clock: MutableList<Long>, energyAvailable: Boolean = false) = TripRecorder(
        tripDao, lastState,
        mockk<EnergyDataReader> { every { isAvailable() } returns energyAvailable },
        mockk<EnergyDataDeadDetector>(relaxed = true),
        batteryCapacityKwh = { 72.9 },
        now = { clock.removeAt(0) },
    )

    @Test
    fun `a warm-up before the drive is not part of the trip`() = runTest {
        val rec = recorder(mutableListOf(t0, t0 + 49 * min, t0 + 79 * min))
        rec.consume(diParsData(powerState = 2, soc = 100, mileage = 35193.4, totalElecConsumption = 9400.0, exteriorTemp = -2))
        // 40+ minutes on, the odometer still: climate draws from the battery.
        rec.consume(diParsData(powerState = 2, soc = 100, mileage = 35193.4, totalElecConsumption = 9400.4, exteriorTemp = -2))
        rec.consume(diParsData(powerState = 2, soc = 99, mileage = 35193.4, totalElecConsumption = 9400.9, exteriorTemp = -1))
        // First odometer step: the drive starts here (t0 + 49 min).
        rec.consume(diParsData(powerState = 2, soc = 99, mileage = 35193.5, totalElecConsumption = 9401.0, exteriorTemp = -1))
        rec.consume(diParsData(powerState = 2, soc = 88, mileage = 35222.6, totalElecConsumption = 9410.9, exteriorTemp = 0))
        rec.consume(diParsData(powerState = 1, soc = 88, mileage = 35222.6, totalElecConsumption = 9410.9, exteriorTemp = 0))

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(t0 + 49 * min, trip.captured.startTs)
        assertEquals(t0 + 79 * min, trip.captured.endTs)
        assertEquals(29.2, trip.captured.distanceKm!!, 1e-6)
        assertEquals(99, trip.captured.socStart)
        assertEquals(10.0, trip.captured.kwhConsumed!!, 1e-6)       // from the last standing tick, not 10.9
        assertEquals(-1, trip.captured.exteriorTemp)
        assertEquals(35193.4, trip.captured.odometerStartKm!!, 1e-6)
        // The new start is persisted, so a restart mid-drive keeps it.
        coVerify(exactly = 1) {
            lastState.openTrip(
                startTs = t0 + 49 * min, startSoc = 99, startMileage = 35193.4,
                startTotalElec = 9400.9, startExteriorTemp = -1, now = t0 + 49 * min,
            )
        }
    }

    @Test
    fun `a drive that starts right at ignition-on keeps its start`() = runTest {
        val rec = recorder(mutableListOf(t0, t0 + 30 * min))
        rec.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))
        rec.consume(diParsData(powerState = 2, soc = 79, mileage = 100.3))
        rec.consume(diParsData(powerState = 1, soc = 70, mileage = 110.0))

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(t0, trip.captured.startTs)
        assertEquals(80, trip.captured.socStart)
        coVerify(exactly = 1) { lastState.openTrip(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a warm-up across a head-unit restart still starts the trip at the first movement`() = runTest {
        // 09:00 car on (warm-up), 09:41 head-unit restart, 09:50 the car drives off.
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            ts = t0 + 40 * min, soc = 99, mileage = 35193.4, openTripId = t0, tripStartTs = t0,
            tripStartSoc = 100, tripStartMileage = 35193.4, tripStartTotalElec = 9400.0,
        )
        val rec = recorder(mutableListOf(t0 + 41 * min, t0 + 50 * min, t0 + 80 * min))
        rec.reconcileColdStart()                     // gap 1 min: resumed, not moved yet
        rec.consume(diParsData(powerState = 2, soc = 99, mileage = 35193.4, totalElecConsumption = 9400.8))
        rec.consume(diParsData(powerState = 2, soc = 99, mileage = 35193.5, totalElecConsumption = 9400.9))
        rec.consume(diParsData(powerState = 1, soc = 88, mileage = 35222.6, totalElecConsumption = 9410.8))

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(t0 + 50 * min, trip.captured.startTs)
        assertEquals(99, trip.captured.socStart)
        assertEquals(10.0, trip.captured.kwhConsumed!!, 1e-6)
    }

    @Test
    fun `a trip resumed after it already moved keeps its start`() = runTest {
        coEvery { lastState.getCurrent() } returns LastStateEntity(
            ts = t0 + 20 * min, soc = 90, mileage = 35210.0, openTripId = t0, tripStartTs = t0,
            tripStartSoc = 99, tripStartMileage = 35193.4,
        )
        val rec = recorder(mutableListOf(t0 + 21 * min, t0 + 40 * min))
        rec.reconcileColdStart()
        rec.consume(diParsData(powerState = 2, soc = 90, mileage = 35210.0))
        rec.consume(diParsData(powerState = 2, soc = 89, mileage = 35212.0))
        rec.consume(diParsData(powerState = 1, soc = 88, mileage = 35222.6))

        val trip = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(trip)) }
        assertEquals(t0, trip.captured.startTs)
        assertEquals(99, trip.captured.socStart)
    }
}
