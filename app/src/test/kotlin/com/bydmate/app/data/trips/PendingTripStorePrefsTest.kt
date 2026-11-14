package com.bydmate.app.data.trips

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.entity.TripEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shadow trips survive a process restart (the head unit dies with the car) with every field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PendingTripStorePrefsTest {

    private fun store() = PendingTripStorePrefs(
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(PendingTripStorePrefs.PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val trip = TripEntity(
        startTs = 1_000L, endTs = 2_000L, distanceKm = 29.2, kwhConsumed = 10.0, kwhPer100km = 34.2,
        socStart = 99, socEnd = 88, exteriorTemp = -1, exteriorTempEnd = 0,
        odometerStartKm = 35193.4, odometerEndKm = 35222.6, source = TripSource.NATIVE_FALLBACK,
    )

    @Test
    fun `a shadow trip comes back from disk as it was stored`() {
        store().add(trip)
        val back = store().all().single()
        assertEquals(trip, back)
        assertNull(back.fuelLiters)
    }

    @Test
    fun `the same start replaces, remove drops, the list is bounded`() {
        val s = store()
        s.add(trip)
        s.add(trip.copy(distanceKm = 30.0))
        assertEquals(30.0, s.all().single().distanceKm!!, 1e-9)
        s.remove(1_000L)
        assertEquals(0, s.all().size)
        repeat(PendingTripStorePrefs.MAX_PENDING + 5) { s.add(trip.copy(startTs = it.toLong())) }
        assertEquals(PendingTripStorePrefs.MAX_PENDING, s.all().size)
        assertEquals(5L, s.all().first().startTs)
    }
}
