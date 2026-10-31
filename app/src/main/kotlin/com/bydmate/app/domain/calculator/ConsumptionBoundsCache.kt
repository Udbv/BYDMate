package com.bydmate.app.domain.calculator

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ConsumptionBounds] kept between app starts, recomputed only when the trip history has moved:
 * once a day, or after [TRIPS_PER_REFRESH] new trips on a busy day. The process restarts with
 * every power cycle, so without the stored copy each trip would read the whole window again.
 */
@Singleton
class ConsumptionBoundsCache @Inject constructor(
    private val trips: TripRepository,
    private val settings: SettingsRepository,
) {

    /** What was computed, when, and at which trip count; [lo]/[hi] null when the history was too short. */
    internal data class Stored(val lo: Double?, val hi: Double?, val at: Long, val tripCount: Int) {
        val bounds: ClosedFloatingPointRange<Double>? get() = if (lo != null && hi != null) lo..hi else null

        fun encode(): String = listOf(lo ?: "", hi ?: "", at, tripCount).joinToString(";")

        companion object {
            fun decode(text: String): Stored? {
                val parts = text.split(';')
                if (parts.size != FIELDS) return null
                val at = parts[2].toLongOrNull() ?: return null
                val count = parts[3].toIntOrNull() ?: return null
                return Stored(parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull(), at, count)
            }

            private const val FIELDS = 4
        }
    }

    suspend fun get(now: Long = System.currentTimeMillis()): ClosedFloatingPointRange<Double>? {
        val count = trips.getTripCount()
        val stored = Stored.decode(settings.getString(KEY, ""))
        if (stored != null && !isStale(stored, now, count)) return stored.bounds
        val bounds = trips.getConsumptionBounds(now)
        settings.setString(KEY, Stored(bounds?.start, bounds?.endInclusive, now, count).encode())
        return bounds
    }

    companion object {
        const val KEY = "range_consumption_bounds"
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
        const val TRIPS_PER_REFRESH = 5

        /** A day old, [TRIPS_PER_REFRESH] trips behind, or from a clock or a table that went back. */
        internal fun isStale(stored: Stored, now: Long, tripCount: Int): Boolean =
            now - stored.at !in 0 until REFRESH_MS ||
                tripCount - stored.tripCount !in 0 until TRIPS_PER_REFRESH
    }
}
