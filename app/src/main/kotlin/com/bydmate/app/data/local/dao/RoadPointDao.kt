package com.bydmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.bydmate.app.data.local.entity.RoadPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RoadPointDao {
    @Insert
    suspend fun insert(point: RoadPointEntity): Long

    @Update
    suspend fun update(point: RoadPointEntity)

    @Query("SELECT * FROM road_points ORDER BY ts DESC")
    fun observeAll(): Flow<List<RoadPointEntity>>

    @Query("SELECT * FROM road_points ORDER BY ts")
    suspend fun all(): List<RoadPointEntity>

    /** Points of [type] inside a lat/lon box: the candidates a new mark may merge into. */
    @Query(
        "SELECT * FROM road_points WHERE type = :type AND lat BETWEEN :latMin AND :latMax " +
            "AND lon BETWEEN :lonMin AND :lonMax",
    )
    suspend fun inBox(type: String, latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): List<RoadPointEntity>

    @Query("UPDATE road_points SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM road_points WHERE id = :id")
    suspend fun delete(id: Long)
}
