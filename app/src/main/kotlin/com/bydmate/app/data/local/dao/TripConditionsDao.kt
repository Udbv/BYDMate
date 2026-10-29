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

/**
 * One minute of a trip's track: mean GPS height (m) and outside temperature (°C) of the minute's
 * points, and whether the wipers ran at any of them. Each is null where the points lack it.
 */
data class TripMinuteTrack(
    val tripId: Long,
    val minuteTs: Long,
    val altitude: Double?,
    val extTemp: Double?,
    val wiping: Int?,
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

    /** [TripMinuteTrack] of every trip minute whose points carry a height, a temperature or the wipers. */
    @Query(
        """
        SELECT trip_id AS tripId, (timestamp / 60000) * 60000 AS minuteTs, AVG(altitude) AS altitude,
            AVG(ext_temp) AS extTemp, MAX(wiping) AS wiping
        FROM trip_points WHERE altitude IS NOT NULL OR ext_temp IS NOT NULL OR wiping IS NOT NULL
        GROUP BY trip_id, timestamp / 60000
        """
    )
    suspend fun minuteTracks(): List<TripMinuteTrack>
}
