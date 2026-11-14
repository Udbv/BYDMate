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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passive mode (the energydata file is trusted to bring the trips): TripRecorder never writes the
 * trips table, but keeps its own trip as a shadow (2026-11-14 missing trips), persisted in
 * last_state like an active one so a head-unit restart mid-drive resumes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripRecorderPassiveTest {

    private val shadows = mutableListOf<TripEntity>()

    private fun recorder(tripDao: TripDao, lastState: LastStateDao, now: () -> Long = { 1_000L }) = TripRecorder(
        tripDao, lastState,
        mockk<EnergyDataReader> { every { isAvailable() } returns true },
        mockk<EnergyDataDeadDetector>(relaxed = true),
        batteryCapacityKwh = { 72.9 },
        now = now,
        shadowSink = { shadows += it },
    )

    @Test fun `passive mode never inserts trip rows, the closed trip goes to the shadow sink`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true)
        coEvery { lastState.openTrip(any(), any(), any(), any(), any(), any()) } returns 1
        val recorder = recorder(tripDao, lastState)

        recorder.consume(diParsData(powerState = 1, soc = 80, mileage = 100.0))   // ON-idle
        recorder.consume(diParsData(powerState = 2, soc = 80, mileage = 100.0))   // DRIVE
        recorder.consume(diParsData(powerState = 2, soc = 70, mileage = 110.0))   // driving
        recorder.consume(diParsData(powerState = 1, soc = 70, mileage = 110.0))   // back to ON-idle

        coVerify(exactly = 0) { tripDao.insert(any()) }
        assertEquals(1, shadows.size)
        assertEquals(TripSource.NATIVE_FALLBACK, shadows[0].source)
        assertEquals(10.0, shadows[0].distanceKm!!, 1e-9)
        assertEquals(100.0, shadows[0].odometerStartKm!!, 1e-9)
        coVerify(exactly = 1) { lastState.openTrip(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { lastState.clearOpenTrip() }
    }

    @Test fun `a stale open trip found at cold start in passive mode becomes a shadow`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                ts = 1_000_000L, soc = 77, mileage = 35249.6, openTripId = 9L, tripStartTs = 900_000L,
                tripStartSoc = 87, tripStartMileage = 35222.6,
            )
        }
        recorder(tripDao, lastState, now = { 1_000_000L + 23 * 60_000L }).reconcileColdStart()

        coVerify(exactly = 0) { tripDao.insert(any()) }
        assertEquals(1, shadows.size)
        assertEquals(27.0, shadows[0].distanceKm!!, 1e-6)
        assertEquals(900_000L, shadows[0].startTs)
        coVerify(exactly = 1) { lastState.clearOpenTrip() }
    }

    @Test fun `a recent open trip found at cold start in passive mode goes on`() = runTest {
        val tripDao = mockk<TripDao>(relaxed = true)
        val lastState = mockk<LastStateDao>(relaxed = true) {
            coEvery { getCurrent() } returns LastStateEntity(
                ts = 1_000_000L, soc = 88, mileage = 35222.6, openTripId = 9L, tripStartTs = 900_000L,
                tripStartSoc = 99, tripStartMileage = 35193.4,
            )
        }
        val rec = recorder(tripDao, lastState, now = { 1_000_000L + 60_000L })
        rec.reconcileColdStart()
        assertTrue(rec.open != null)
        coVerify(exactly = 0) { lastState.clearOpenTrip() }
        assertTrue(shadows.isEmpty())
    }
}
