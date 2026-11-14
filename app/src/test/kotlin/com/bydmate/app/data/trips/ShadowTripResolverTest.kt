package com.bydmate.app.data.trips

import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14 missing trips: the passive device's own trip is saved when the import brings no BYD
 * record for the drive, and dropped (never a duplicate) when one covers it.
 */
class ShadowTripResolverTest {

    private class MemoryStore : PendingTripStore {
        val trips = mutableListOf<TripEntity>()
        override fun all() = trips.toList()
        override fun add(trip: TripEntity) { trips.removeAll { it.startTs == trip.startTs }; trips += trip }
        override fun remove(startTs: Long) { trips.removeAll { it.startTs == startTs } }
    }

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L
    private val store = MemoryStore()
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val tripPointDao = mockk<TripPointDao>(relaxed = true)
    private var stored: List<TripEntity> = emptyList()
    private val resolver = ShadowTripResolver(store, tripDao, tripPointDao)

    init {
        coEvery { tripDao.getStartingBetween(any(), any()) } answers { stored }
        coEvery { tripDao.insert(any()) } returns 42L
        coEvery { tripPointDao.attachToTrip(any(), any(), any()) } returns 1680
    }

    /** Drive 1 of 2026-11-14: 09:50-10:19, 35193.4 -> 35222.6 km, SoC 99 -> 88. */
    private val shadow = TripEntity(
        startTs = t0, endTs = t0 + 30 * min, distanceKm = 29.2, kwhConsumed = 10.0,
        socStart = 99, socEnd = 88, odometerStartKm = 35193.4, odometerEndKm = 35222.6,
        source = TripSource.NATIVE_POLLING,
    )

    @Test
    fun `no BYD record after a good import saves the shadow as the app's own trip`() = runTest {
        resolver.add(shadow)
        val saved = resolver.resolve(importOk = true, nowMs = t0 + 60 * min)

        assertEquals(1, saved)
        val inserted = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(inserted)) }
        assertEquals(TripSource.NATIVE_FALLBACK, inserted.captured.source)
        assertEquals(t0, inserted.captured.startTs)
        assertEquals(29.2, inserted.captured.distanceKm!!, 1e-9)
        assertEquals(35193.4, inserted.captured.odometerStartKm!!, 1e-9)
        coVerify(exactly = 1) { tripPointDao.attachToTrip(42L, t0, t0 + 30 * min) }
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a BYD record over the same odometer range drops the shadow`() = runTest {
        stored = listOf(
            TripEntity(
                id = 7, startTs = t0 - 9 * min, endTs = t0 + 31 * min, distanceKm = 29.1,
                odometerStartKm = 35193.5, odometerEndKm = 35222.6, source = TripSource.ENERGYDATA, bydId = 501,
            ),
        )
        resolver.add(shadow)
        assertEquals(0, resolver.resolve(importOk = true, nowMs = t0 + 60 * min))
        coVerify(exactly = 0) { tripDao.insert(any()) }
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a BYD record without odometer drops the shadow by time within a few minutes`() = runTest {
        // BYD starts at car-on (a 4-min warm-up earlier), the shadow at the first movement.
        stored = listOf(
            TripEntity(id = 8, startTs = t0 - 4 * min, endTs = t0 + 29 * min, distanceKm = 29.0, source = TripSource.ENERGYDATA, bydId = 502),
        )
        resolver.add(shadow)
        resolver.resolve(importOk = true, nowMs = t0 + 60 * min)
        coVerify(exactly = 0) { tripDao.insert(any()) }
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `the drive before, an idle-drain row and a zero-km row do not cover the shadow`() = runTest {
        stored = listOf(
            // Another drive that ended three minutes before this one started.
            TripEntity(id = 9, startTs = t0 - 40 * min, endTs = t0 - 3 * min, distanceKm = 12.0, source = TripSource.ENERGYDATA),
            // BYD's zero-km record of the warm-up, overlapping in time.
            TripEntity(id = 10, startTs = t0 - 40 * min, endTs = t0 + 30 * min, distanceKm = 0.0, source = TripSource.ENERGYDATA),
        )
        resolver.add(shadow)
        assertEquals(1, resolver.resolve(importOk = true, nowMs = t0 + 60 * min))
    }

    @Test
    fun `right after the drive the shadow waits for BYD to write its row`() = runTest {
        resolver.add(shadow)
        assertEquals(0, resolver.resolve(importOk = true, nowMs = t0 + 31 * min))
        assertEquals(1, store.all().size)
        coVerify(exactly = 0) { tripDao.insert(any()) }
    }

    @Test
    fun `a failing import keeps the shadow, but not for ever`() = runTest {
        resolver.add(shadow)
        assertEquals(0, resolver.resolve(importOk = false, nowMs = t0 + 2 * 60 * min))
        assertEquals(1, store.all().size)
        assertEquals(1, resolver.resolve(importOk = false, nowMs = t0 + 30 * min + ShadowTripResolver.MAX_WAIT_MS))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `a car switched on and off without moving is no shadow`() = runTest {
        resolver.add(shadow.copy(distanceKm = 0.0, odometerEndKm = 35193.4))
        assertTrue(store.all().isEmpty())
    }
}
