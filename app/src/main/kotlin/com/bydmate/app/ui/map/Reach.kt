package com.bydmate.app.ui.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far the car gets from here on the map. The range is road kilometres; a road is longer than
 * the straight line by [DETOUR] on average, so the reach circle is the range over it. The inner
 * circle keeps [RESERVE] of the range back: a detour, a headwind, a closed charger.
 */
data class Reach(val rangeKm: Double) {
    val reachKm: Double get() = rangeKm / DETOUR
    val safeKm: Double get() = reachKm * (1 - RESERVE)

    /** Road kilometres to a point [straightKm] away as the crow flies. */
    fun roadKm(straightKm: Double): Double = straightKm * DETOUR

    fun verdict(straightKm: Double): Verdict = when {
        straightKm <= safeKm -> Verdict.EASY
        straightKm <= reachKm -> Verdict.TIGHT
        else -> Verdict.OUT
    }

    enum class Verdict { EASY, TIGHT, OUT }

    companion object {
        /** Road length over straight distance on Ukrainian intercity and city roads, roughly. */
        const val DETOUR = 1.25
        const val RESERVE = 0.15
    }
}

/** Great-circle distance, km. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
}

private const val EARTH_RADIUS_KM = 6371.0
