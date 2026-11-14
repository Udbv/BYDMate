package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.trips.ShadowTripResolver
import com.bydmate.app.data.trips.TripSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14 missing trips: the passive device's shadow trips are resolved after every import
 * attempt, and a BYD record that turns up for a trip the app already saved takes that trip over.
 */
class HistoryImporterShadowTripTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val resolver = mockk<ShadowTripResolver>(relaxed = true)
    private var nowMs = 1_794_000_000_000L

    private fun importer() = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
        settingsRepository = settingsRepository,
        lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
        tripTombstoneDao = mockk<TripTombstoneDao>(relaxed = true),
        costCalculator = mockk(relaxed = true),
        odometerMarks = mockk(relaxed = true),
        vehicleKind = com.bydmate.app.data.nativestack.kindMonitor(),
        counterSnapshotDao = mockk(relaxed = true),
        counterSnapshotWriter = mockk(relaxed = true),
        lastStateDao = mockk(relaxed = true),
        shadowTrips = resolver,
    ).also { it.clock = { nowMs } }

    @Test
    fun `an unchanged file resolves the shadows as a good import`() = runTest {
        coEvery { settingsRepository.getLastEnergyImportTs() } returns nowMs / 1000L - 3600L
        every { energyDataReader.peekSourceChanged(any()) } returns false
        importer().syncFromEnergyData()
        coVerify(exactly = 1) { resolver.resolve(true, nowMs) }
    }

    @Test
    fun `a failed copy resolves the shadows as a failed import`() = runTest {
        coEvery { settingsRepository.getLastEnergyImportTs() } returns nowMs / 1000L - 3600L
        every { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } throws
            EnergyDataException("copy failed: FileNotFoundException in /data/files")
        coEvery { settingsRepository.isEnergyIdsRecycled() } returns true
        val result = importer().syncFromEnergyData()
        assertTrue(result.isError)
        coVerify(exactly = 1) { resolver.resolve(false, nowMs) }
    }

    @Test
    fun `a BYD record for a trip the app already saved takes it over instead of a duplicate`() = runTest {
        val nowSec = nowMs / 1000L
        // BYD counts from car-on, 9 min before the drive the app recorded from the first movement.
        val record = BydTripRecord(
            id = 777L, startTimestamp = nowSec - 3600L, endTimestamp = nowSec - 1800L,
            duration = 1800L, tripKm = 29.1, electricityKwh = 10.2,
        )
        val own = TripEntity(
            id = 55L, startTs = (nowSec - 3060L) * 1000L, endTs = (nowSec - 1790L) * 1000L, distanceKm = 29.2,
            odometerStartKm = 35193.4, odometerEndKm = 35222.6, source = TripSource.NATIVE_FALLBACK,
        )
        coEvery { settingsRepository.getLastEnergyImportTs() } returns nowSec - 7200L
        coEvery { settingsRepository.isEnergyIdsRecycled() } returns true
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        every { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(record)
        coEvery { tripDao.getByBydId(any()) } returns null
        coEvery { tripDao.getSameTripByTime(any(), any()) } returns null
        coEvery { tripDao.getOverlappingOwnTripId(record.startTimestamp * 1000L, record.endTimestamp * 1000L) } returns 55L
        coEvery { tripDao.getById(55L) } returns own

        importer().syncFromEnergyData()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
        val updated = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.updateTrip(capture(updated)) }
        assertEquals(55L, updated.captured.id)
        assertEquals(777L, updated.captured.bydId)
        assertEquals("energydata", updated.captured.source)
        assertEquals(own.startTs, updated.captured.startTs)
    }
}
