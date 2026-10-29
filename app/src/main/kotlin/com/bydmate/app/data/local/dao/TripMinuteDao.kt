package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bydmate.app.data.local.entity.TripMinuteEntity

/** A trip's weather inputs from its minutes: see tripWeather(). */
data class TripWeatherRow(
    val tripId: Long,
    val wetMinutes: Int,
    val knownMinutes: Int,
    val extTemp: Double?,
)

@Dao
interface TripMinuteDao {
    /** A minute written twice (a restart inside it) keeps the later, fuller row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(minute: TripMinuteEntity)

    @Query("SELECT * FROM trip_minutes WHERE minute_ts BETWEEN :from AND :to ORDER BY minute_ts ASC")
    suspend fun getInRange(from: Long, to: Long): List<TripMinuteEntity>

    @Query("SELECT * FROM trip_minutes WHERE minute_ts = :minuteTs")
    suspend fun get(minuteTs: Long): TripMinuteEntity?

    @Query("DELETE FROM trip_minutes WHERE minute_ts < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

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
}
