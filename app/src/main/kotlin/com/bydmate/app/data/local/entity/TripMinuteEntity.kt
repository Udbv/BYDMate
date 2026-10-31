package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One clock minute of a car that was on: where the battery's energy went and the extremes within
 * it. Keyed by the minute's start, like trip points by time: a trip's minutes are the ones between
 * its start and end, whatever the trip's id turns out to be. Energies in kWh over the minute.
 *
 * - [frontKwh] / [rearKwh]: traction of each axle (the motors' share split by their currents);
 *   null when the car reports no split, the traction then sits in [motorsKwh] only.
 * - [regenKwh]: energy the motors returned, ≤ 0.
 * - [climateKwh]: the compressor; [otherKwh]: the 12 V side of the DC-DC.
 * - [peakKw] / [minKw]: highest and lowest battery power seen, [maxSpeed] likewise.
 * - [wiperSamples]: ticks with the wipers running; null when the car did not report them.
 * - [extTemp]: mean outside temperature, °C.
 */
@Entity(tableName = "trip_minutes")
data class TripMinuteEntity(
    @PrimaryKey @ColumnInfo(name = "minute_ts") val minuteTs: Long,
    @ColumnInfo(name = "samples") val samples: Int,
    @ColumnInfo(name = "motors_kwh") val motorsKwh: Double,
    @ColumnInfo(name = "front_kwh") val frontKwh: Double?,
    @ColumnInfo(name = "rear_kwh") val rearKwh: Double?,
    @ColumnInfo(name = "regen_kwh") val regenKwh: Double,
    @ColumnInfo(name = "climate_kwh") val climateKwh: Double,
    @ColumnInfo(name = "other_kwh") val otherKwh: Double,
    @ColumnInfo(name = "peak_kw") val peakKw: Double,
    @ColumnInfo(name = "min_kw") val minKw: Double,
    @ColumnInfo(name = "avg_speed") val avgSpeed: Double?,
    @ColumnInfo(name = "max_speed") val maxSpeed: Int?,
    @ColumnInfo(name = "soc") val soc: Int?,
    @ColumnInfo(name = "wiper_samples") val wiperSamples: Int? = null,
    @ColumnInfo(name = "ext_temp") val extTemp: Double? = null,
)
