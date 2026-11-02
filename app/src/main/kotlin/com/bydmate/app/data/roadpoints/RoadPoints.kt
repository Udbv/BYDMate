package com.bydmate.app.data.roadpoints

import com.bydmate.app.data.local.entity.RoadPointEntity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What a marked place is; [id] is what the voice dictionary and the export say. */
enum class RoadPointType(val id: String) {
    SPEED_BUMP("speed_bump"),
    TRAFFIC_LIGHT("traffic_light"),
    LANES("lanes"),
    OTHER("other"),
    ;

    companion object {
        fun of(id: String): RoadPointType? = entries.firstOrNull { it.id == id || it.name == id }
    }
}

/** Where a point is on its way to Waze; only marking exists so far, the rest is for the review. */
enum class RoadPointStatus { CANDIDATE, CONFIRMED, REJECTED, IN_WAZE }

/**
 * Marking rules (#22): a mark within [MERGE_RADIUS_M] of a point of the same type, on the same
 * course (within [MERGE_HEADING_DEG], or either course unknown), is the same place driven past
 * again: its pass count goes up instead of a second point. A speed bump marked on the way there and
 * on the way back stays two points: Waze's hazards have a direction.
 */
object RoadPoints {
    const val MERGE_RADIUS_M = 15.0
    const val MERGE_HEADING_DEG = 30.0
    private const val EARTH_M = 6_371_000.0
    private const val M_PER_DEG_LAT = 111_320.0
    private const val FULL_CIRCLE = 360.0
    private const val HALF_CIRCLE = 180.0

    /** The point a new mark joins, or null when it is a new place. */
    fun mergeTarget(lat: Double, lon: Double, heading: Double?, near: List<RoadPointEntity>): RoadPointEntity? =
        near.filter { distanceM(lat, lon, it.lat, it.lon) <= MERGE_RADIUS_M && sameCourse(heading, it.heading) }
            .minByOrNull { distanceM(lat, lon, it.lat, it.lon) }

    /** The lat/lon box that holds every point within [MERGE_RADIUS_M], for the DAO query. */
    fun box(lat: Double, lon: Double): DoubleArray {
        // With a margin: the box is a coarse filter, [mergeTarget] measures the real distance.
        val r = MERGE_RADIUS_M * BOX_MARGIN
        val dLat = r / M_PER_DEG_LAT
        val dLon = r / (M_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(MIN_COS))
        return doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
    }

    fun sameCourse(a: Double?, b: Double?): Boolean {
        if (a == null || b == null) return true
        val d = abs(a - b) % FULL_CIRCLE
        return minOf(d, FULL_CIRCLE - d) <= MERGE_HEADING_DEG
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Course in words for the export: «північ», «південний схід»… */
    fun compass(heading: Double?): String {
        if (heading == null) return "—"
        val names = listOf("Пн", "ПнСх", "Сх", "ПдСх", "Пд", "ПдЗх", "Зх", "ПнЗх")
        val i = Math.floorMod(((heading + HALF_CIRCLE / names.size) / (FULL_CIRCLE / names.size)).toInt(), names.size)
        return names[i]
    }

    private const val MIN_COS = 0.01
    private const val BOX_MARGIN = 1.2
}
