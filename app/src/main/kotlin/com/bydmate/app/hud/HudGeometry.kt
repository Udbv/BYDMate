package com.bydmate.app.hud

import kotlin.math.cos
import kotlin.math.sin

/**
 * Guide line / guide point strings for the AR-HUD road-info struct (f30 / f31). The factory map
 * sends the projected route polyline; openbyd (field-tested on the DiLink 150 glass) synthesizes
 * a short polyline ahead of the car that bends into the maneuver, which is what this reproduces:
 * ten points 22 m apart along the heading, turning 15 degrees per step after the fifth point for
 * turn codes. Coordinates are "lon,lat,0" triples like the map's own JSON.
 */
object HudGeometry {
    private const val STEP_M = 22.0
    private const val METERS_PER_DEGREE = 111_000.0

    /** Gaode codes that bend left / right (BYDMate's internal enum: 3 slight left, 4 slight right). */
    private fun turnSign(gaode: Int): Int = when (gaode) {
        1, 3, 7 -> -1            // left, slight left, sharp left
        2, 4, 8 -> 1             // right, slight right, sharp right
        else -> 0
    }

    fun guideLine(gaode: Int, lat: Double, lon: Double, headingDeg: Double): String {
        val sb = StringBuilder("[")
        var heading = Math.toRadians(headingDeg)
        var curLat = lat
        var curLon = lon
        val bend = Math.toRadians(15.0) * turnSign(gaode)
        for (i in 0 until 10) {
            sb.append('[').append(curLon).append(',').append(curLat).append(",0]")
            if (i < 9) sb.append(',')
            if (i >= 5) heading += bend
            curLat += cos(heading) * STEP_M / METERS_PER_DEGREE
            curLon += sin(heading) * STEP_M / (cos(Math.toRadians(curLat)) * METERS_PER_DEGREE)
        }
        return sb.append(']').toString()
    }

    /** Bearing of the guide point relative to the heading (openbyd `guidePointBearingHud`, keyed
     *  by the glass code: 1 left -14.25 deg, 2/3/5/7/8/9 +45 deg, else straight). */
    fun guidePointBearing(headingDeg: Double, glassCode: Int): Double = when (glassCode) {
        1 -> headingDeg - 14.25
        2, 3, 5, 7, 8, 9 -> headingDeg + 45.0
        else -> headingDeg
    }

    fun projectPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val r = Math.toRadians(bearingDeg)
        return Pair(
            lat + cos(r) * distanceM / METERS_PER_DEGREE,
            lon + sin(r) * distanceM / (cos(Math.toRadians(lat)) * METERS_PER_DEGREE),
        )
    }

    fun guidePoint(distanceM: Int, glassCode: Int, lat: Double, lon: Double, headingDeg: Double): String {
        val d = if (distanceM <= 0) 50 else distanceM
        val (pLat, pLon) = projectPoint(lat, lon, guidePointBearing(headingDeg, glassCode), d.toDouble())
        return "$pLon,$pLat,0"
    }
}
