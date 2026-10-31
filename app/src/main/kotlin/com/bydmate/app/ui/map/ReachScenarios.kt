package com.bydmate.app.ui.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far the charge reaches in three driving scenarios, road km:
 * - [heavyKm]: the worst 100 km stretch of the drive history (cold, fast, loaded);
 * - [normalKm]: the app's own range estimate (history blended with the drive now);
 * - [ecoKm]: the best stretch of the history.
 * Without enough history the outer two are the normal range ±[FALLBACK_HEAVY] / [FALLBACK_ECO].
 */
data class ReachScenarios(val heavyKm: Double, val normalKm: Double, val ecoKm: Double) {

    fun band(km: Double): Band = when {
        km <= heavyKm -> Band.SURE
        km <= normalKm -> Band.LIKELY
        km <= ecoKm -> Band.ECO_ONLY
        else -> Band.OUT
    }

    /** Green: even in the heavy case; yellow: as usual; orange: only driving economically; red: not without charging. */
    enum class Band { SURE, LIKELY, ECO_ONLY, OUT }

    companion object {
        const val FALLBACK_HEAVY = 0.8
        const val FALLBACK_ECO = 1.15

        /**
         * From the range estimate, the energy left and the history's best..worst kWh/100. The
         * normal case stays the estimate; the outer two come from the energy and the bounds,
         * kept on their side of it.
         */
        fun of(normalKm: Double, remainKwh: Double?, bounds: ClosedFloatingPointRange<Double>?): ReachScenarios {
            val energy = remainKwh?.takeIf { it > 0 }
            val history = bounds?.takeIf { it.start > 0 }
            if (energy == null || history == null) {
                return ReachScenarios(normalKm * FALLBACK_HEAVY, normalKm, normalKm * FALLBACK_ECO)
            }
            val heavy = energy / history.endInclusive * PER_KM
            val eco = energy / history.start * PER_KM
            return ReachScenarios(minOf(heavy, normalKm), normalKm, maxOf(eco, normalKm))
        }
    }
}

/** A point of a route, degrees. */
data class LatLon(val lat: Double, val lon: Double)

/** A stretch of the route in one [ReachScenarios.Band]. */
data class RoutePiece(val band: ReachScenarios.Band, val points: List<LatLon>)

/**
 * Cuts a route where each scenario's charge runs out, so each piece can take its band's colour:
 * the cut points are interpolated on the segment that crosses the limit.
 */
object RouteBands {

    fun split(route: List<LatLon>, s: ReachScenarios): List<RoutePiece> {
        if (route.size < 2) return emptyList()
        val limits = listOf(s.heavyKm, s.normalKm, s.ecoKm)
        val pieces = mutableListOf<RoutePiece>()
        var current = mutableListOf(route.first())
        var band = s.band(0.0)
        var km = 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val len = haversineKm(a.lat, a.lon, b.lat, b.lon)
            for (limit in limits.filter { it > km && it < km + len }.sorted()) {
                val cut = lerp(a, b, (limit - km) / len)
                current += cut
                pieces += RoutePiece(band, current)
                band = s.band(limit + EPS_KM)
                current = mutableListOf(cut)
            }
            current += b
            km += len
        }
        pieces += RoutePiece(band, current)
        return pieces
    }

    /** Where each scenario's charge runs out along the route, if it does before its end. */
    fun limitPoints(route: List<LatLon>, s: ReachScenarios): Map<ReachScenarios.Band, LatLon> {
        val out = linkedMapOf<ReachScenarios.Band, LatLon>()
        var km = 0.0
        val limits = listOf(
            ReachScenarios.Band.SURE to s.heavyKm,
            ReachScenarios.Band.LIKELY to s.normalKm,
            ReachScenarios.Band.ECO_ONLY to s.ecoKm,
        )
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val len = haversineKm(a.lat, a.lon, b.lat, b.lon)
            for ((band, limit) in limits) {
                if (band !in out && limit > km && limit <= km + len) out[band] = lerp(a, b, (limit - km) / len)
            }
            km += len
        }
        return out
    }

    private fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    private const val EPS_KM = 1e-6
}

/** Great-circle distance, km. */
fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
}

private const val EARTH_RADIUS_KM = 6371.0
private const val PER_KM = 100.0
