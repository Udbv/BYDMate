package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bydmate.app.data.local.entity.TripMinuteEntity

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
}
