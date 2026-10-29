package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/** A trip's weather inputs from its minutes: see tripWeather(). */
data class TripWeatherRow(
    val tripId: Long,
    val wetMinutes: Int,
    val knownMinutes: Int,
    val extTemp: Double?,
)

/** GPS altitude at a trip's two ends, metres: each the mean of the fixes in its first or last 30 s. */
data class TripEndsAltitude(
    val tripId: Long,
    val startAlt: Double?,
    val endAlt: Double?,
)

/** The conditions of trips the consumption forecast reads: weather from the minutes, height from the track. */
@Dao
interface TripConditionsDao {
    /**
     * Weather inputs of every finished trip since [since]: minutes with the wipers running, minutes
     * that reported the wipers at all, and the mean outside temperature. The minute holding a
     * trip's start began up to a minute before it.
     */
    @Query(
        """
        SELECT t.id AS tripId,
            (SELECT COUNT(*) FROM trip_minutes m WHERE m.minute_ts BETWEEN t.start_ts - 59999 AND t.end_ts
                AND m.wiper_samples > 0) AS wetMinutes,
            (SELECT COUNT(*) FROM trip_minutes m WHERE m.minute_ts BETWEEN t.start_ts - 59999 AND t.end_ts
                AND m.wiper_samples IS NOT NULL) AS knownMinutes,
            (SELECT AVG(m.ext_temp) FROM trip_minutes m WHERE m.minute_ts BETWEEN t.start_ts - 59999 AND t.end_ts)
                AS extTemp
        FROM trips t WHERE t.end_ts IS NOT NULL AND t.start_ts >= :since
        """
    )
    suspend fun tripWeather(since: Long): List<TripWeatherRow>

    /**
     * [TripEndsAltitude] of every trip that has fixes with an altitude. GPS height jumps by metres
     * fix to fix, so each end averages its first or last 30 s.
     */
    @Query(
        """
        SELECT e.trip_id AS tripId,
            (SELECT AVG(p.altitude) FROM trip_points p WHERE p.trip_id = e.trip_id AND p.altitude IS NOT NULL
                AND p.timestamp <= e.first_ts + 30000) AS startAlt,
            (SELECT AVG(p.altitude) FROM trip_points p WHERE p.trip_id = e.trip_id AND p.altitude IS NOT NULL
                AND p.timestamp >= e.last_ts - 30000) AS endAlt
        FROM (SELECT trip_id, MIN(timestamp) AS first_ts, MAX(timestamp) AS last_ts FROM trip_points
            WHERE altitude IS NOT NULL GROUP BY trip_id) e
        """
    )
    suspend fun endsAltitude(): List<TripEndsAltitude>
}
