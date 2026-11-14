package com.bydmate.app.data.trips

import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToLong

/** TripRecorder's arithmetic of one trip: energy, distance, odometer and the DM-i fuel columns. */
internal object TripMath {

    private const val TAG = "TripRecorder"

    /** Upper bound of a single trip's distance; above it the odometer delta is a
     *  scale change or a bad baseline, not a drive. */
    const val MAX_PLAUSIBLE_TRIP_KM = 1500.0

    /** Startup guard: the odometer fid reads 0 before the car reports it. */
    const val MIN_VALID_ODOMETER_KM = 1.0

    /**
     * BMS lifetime-consumption counter (0.1 kWh granularity) beats the integer SOC
     * delta (1% SOC = ~0.7 kWh quantum — issue #53: dashes on short trips, inflated
     * per-100km on others). Negative/zero delta (counter reset or unsupported fid)
     * falls back to the SOC estimate. A DM-i hybrid gets no SOC estimate: the engine charges
     * the pack on the way, so a SOC delta is not what the trip drew (null instead).
     */
    @Suppress("LongParameterList") // both counters at both ends, plus the battery and the car type
    fun kwh(startElec: Double?, endElec: Double?, startSoc: Int?, endSoc: Int?, cap: Double, hybrid: Boolean): Double? {
        if (startElec != null && endElec != null) {
            val elecDelta = endElec - startElec
            if (elecDelta > 0) return elecDelta
        }
        if (hybrid) return null
        val socDelta = (startSoc ?: 0) - (endSoc ?: 0)
        return if (socDelta > 0) socDelta / 100.0 * cap else null
    }

    /**
     * Odometer delta of one trip, or null when the two readings cannot belong to the same
     * drive: the odometer scale changed under an open trip (the firmware catalog resolved
     * mid-session and moved the odometer fid) or the baseline came from a startup race.
     * The trip itself is still recorded, only the distance is dropped.
     */
    fun plausibleDistance(start: Double?, end: Double?): Double? {
        if (start == null || end == null) return null
        val delta = (end - start).coerceAtLeast(0.0)
        if (delta > MAX_PLAUSIBLE_TRIP_KM) {
            Log.w(TAG, "trip distance implausible: start=$start end=$end delta=$delta → dropped")
            return null
        }
        return delta
    }

    /**
     * Odometer at both ends of a trip, rounded to 0.1 km. A reading under 1 km is the startup
     * zero, not the odometer; a pair further apart than a plausible drive belongs to two
     * different odometer scales, so neither end is trusted.
     */
    fun odometerPair(start: Double?, end: Double?): Pair<Double?, Double?> {
        val s = start?.takeIf { it >= MIN_VALID_ODOMETER_KM }
        val e = end?.takeIf { it >= MIN_VALID_ODOMETER_KM }
        if (s != null && e != null && abs(e - s) > MAX_PLAUSIBLE_TRIP_KM) return null to null
        return s?.let(::round1) to e?.let(::round1)
    }

    fun per100(kwh: Double?, distance: Double?): Double? =
        if (kwh != null && distance != null && distance > 0) kwh / distance * 100.0 else null

    fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

    /**
     * DM-i fuel columns of one trip from the counters at its two edges: raw readings as read,
     * litres only for a delta in [0, [MAX_TRIP_LITERS]], engine km only for a delta in
     * [0, distance + 1]. A missing edge leaves the derived value null. Not HYBRID: all null.
     */
    @Suppress("LongParameterList") // two counters at two edges, the distance and the car type
    fun fuelColumns(
        startFuel: Double?,
        endFuel: Double?,
        startHev: Double?,
        endHev: Double?,
        distanceKm: Double?,
        hybrid: Boolean,
    ): TripFuel {
        if (!hybrid) return TripFuel()
        return TripFuel(
            fuelLiters = TripFuel.liters(startFuel, endFuel),
            hevKm = TripFuel.hevKm(startHev, endHev, distanceKm),
            fuelTotalStart = startFuel,
            fuelTotalEnd = endFuel,
            mileageHevStart = startHev,
            mileageHevEnd = endHev,
        )
    }
}
