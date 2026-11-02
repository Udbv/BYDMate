package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A place on the road the driver marked (#22, «Мої точки»): a speed bump, a traffic light, a lane
 * layout Waze lacks. Kept on the car and exported for Waze map editors on request; never sent
 * anywhere by itself.
 *
 * - [heading]: course over ground when marked, degrees from north; null without a GPS course.
 * - [type]: [com.bydmate.app.data.roadpoints.RoadPointType] name.
 * - [passes]: how many times it was marked within the merge radius on the same course.
 * - [status]: [com.bydmate.app.data.roadpoints.RoadPointStatus] name.
 */
@Entity(tableName = "road_points")
data class RoadPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "last_ts") val lastTs: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "heading") val heading: Double?,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "passes") val passes: Int,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "note") val note: String? = null,
)
