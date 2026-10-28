package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsumptionBoundsCacheTest {

    private val day = ConsumptionBoundsCache.REFRESH_MS
    private var stored = ""
    private var tripCount = 40
    private val trips = mockk<TripRepository>().also {
        coEvery { it.getTripCount() } answers { tripCount }
        coEvery { it.getConsumptionBounds(any()) } returns 22.0..38.0
    }
    private val settings = mockk<SettingsRepository>().also {
        coEvery { it.getString(ConsumptionBoundsCache.KEY, any()) } answers { stored }
        coEvery { it.setString(ConsumptionBoundsCache.KEY, any()) } answers { stored = secondArg() }
    }
    private val cache = ConsumptionBoundsCache(trips, settings)

    @Test fun `computed once, then kept across restarts until a day or five trips pass`() = runBlocking {
        assertEquals(22.0..38.0, cache.get(now = 0))
        ConsumptionBoundsCache(trips, settings).get(now = day - 1)
        tripCount += 4
        cache.get(now = 1000)
        coVerify(exactly = 1) { trips.getConsumptionBounds(any()) }
        tripCount += 1
        cache.get(now = 2000)
        coVerify(exactly = 2) { trips.getConsumptionBounds(any()) }
        cache.get(now = 2000 + day)
        coVerify(exactly = 3) { trips.getConsumptionBounds(any()) }
    }

    @Test fun `a short history is remembered too`() = runBlocking {
        coEvery { trips.getConsumptionBounds(any()) } returns null
        assertNull(cache.get(now = 0))
        assertNull(cache.get(now = 1))
        coVerify(exactly = 1) { trips.getConsumptionBounds(any()) }
    }
}
