package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.bydmate.app.data.local.entity.TripPointEntity

/** GPS altitude at a trip's two ends, metres: each the mean of the fixes in its first or last 30 s. */
data class TripEndsAltitude(
    val tripId: Long,
    val startAlt: Double?,
    val endAlt: Double?,
)

@Dao
interface TripPointDao {
    @Insert
    suspend fun insertAll(points: List<TripPointEntity>)

    @Query("SELECT * FROM trip_points WHERE trip_id = :tripId ORDER BY timestamp ASC")
    suspend fun getByTripId(tripId: Long): List<TripPointEntity>

    @Query("SELECT * FROM trip_points WHERE trip_id IN (:tripIds) ORDER BY trip_id, timestamp ASC")
    suspend fun getByTripIds(tripIds: List<Long>): List<TripPointEntity>

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

    @Query("SELECT COUNT(*) FROM trip_points")
    suspend fun getCount(): Int

    @Query("SELECT * FROM trip_points ORDER BY trip_id, timestamp ASC")
    suspend fun getAll(): List<TripPointEntity>

    /**
     * Delete old points keeping every Nth one per trip.
     * Points older than [cutoff] are thinned to ~1 per [intervalMs].
     */
    @Query("""
        DELETE FROM trip_points
        WHERE timestamp < :cutoff
        AND id NOT IN (
            SELECT MIN(id) FROM trip_points
            WHERE timestamp < :cutoff
            GROUP BY trip_id, (timestamp / :intervalMs)
        )
    """)
    suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int

    @Query("SELECT * FROM trip_points WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getByTimeRange(from: Long, to: Long): List<TripPointEntity>

    @Query("UPDATE trip_points SET trip_id = :tripId WHERE timestamp BETWEEN :from AND :to AND trip_id = 0")
    suspend fun attachToTrip(tripId: Long, from: Long, to: Long): Int

    @Insert
    suspend fun insert(point: TripPointEntity): Long

    @Query("DELETE FROM trip_points WHERE trip_id = :tripId")
    suspend fun deleteByTripId(tripId: Long): Int
}
