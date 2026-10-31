package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trip_points",
    indices = [Index("trip_id"), Index("timestamp")]
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id") val tripId: Long,
    val timestamp: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "speed_kmh") val speedKmh: Double? = null,
    /** Metres above sea level as the fix reported it; the forecast's climbs come from it. */
    val altitude: Double? = null,
    /**
     * Outside temperature at this point, °C, and whether the wipers were running (1/0): written
     * on every point, not only on a change, because thinning old tracks keeps one point in 15 s
     * and would drop the point that carried the change.
     */
    @ColumnInfo(name = "ext_temp") val extTemp: Int? = null,
    @ColumnInfo(name = "wiping") val wiping: Int? = null,
)
