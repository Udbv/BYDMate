package com.bydmate.app.gnss

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One position fix as [GnssPlausibility] judges it.
 *
 * [atMs] is when the fix arrived, on the monotonic clock the car's speed is stamped with too
 * (never the GPS time: a spoofer can move that as well). [fixTimeMs] is the time the fix itself
 * carries, only compared with the previous fix's. [accuracyM] is null when the fix reports none.
 */
data class GnssFix(
    val atMs: Long,
    val fixTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val provider: String = "gps",
) {
    /** The fix's own error radius as the checks count it: capped, a default when unreported. */
    val errorM: Double
        get() = accuracyM?.toDouble()?.coerceIn(0.0, ACCURACY_CAP_M) ?: ACCURACY_UNKNOWN_M

    companion object {
        /** An error radius counts at most this much: a wider one is judged on its own. */
        const val ACCURACY_CAP_M = 200.0
        const val ACCURACY_UNKNOWN_M = 50.0
    }
}

/** A state change of the guard: [suspect] true pauses, false resumes; [reason] has no coordinates. */
data class GnssTransition(val suspect: Boolean, val reason: String)

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance between two fixes, metres. */
internal fun distanceMeters(a: GnssFix, b: GnssFix): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}
