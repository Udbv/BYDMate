package com.bydmate.app.data.roadpoints

import com.bydmate.app.data.local.entity.RoadPointEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The marked places for Waze's map editors (#22): a plain list the owner forwards to the Ukrainian
 * editor community, each point with a link that opens Waze Map Editor right there, and the same
 * points as GeoJSON for a WME script layer. Rejected points stay out. Only places and courses go
 * out: no tracks, no trip times.
 */
object RoadPointExport {
    private const val WME_ZOOM = 20

    /** Waze Map Editor at the point; Ukraine is in the «rest of world» server. */
    fun wmeLink(lat: Double, lon: Double): String =
        "https://www.waze.com/uk/editor?env=row&lat=%.6f&lon=%.6f&zoomLevel=%d".format(Locale.ROOT, lat, lon, WME_ZOOM)

    fun exportable(points: List<RoadPointEntity>): List<RoadPointEntity> =
        points.filter { it.status != RoadPointStatus.REJECTED.name }

    fun text(points: List<RoadPointEntity>): String = buildString {
        val list = exportable(points)
        appendLine("Точки для Waze Map Editor (BYDMate): ${list.size}")
        appendLine()
        list.groupBy { RoadPointType.of(it.type) ?: RoadPointType.OTHER }.forEach { (type, group) ->
            appendLine("${title(type)} — ${group.size}")
            group.forEach { p ->
                appendLine(
                    "• %.6f, %.6f · напрямок %s · проїздів %d · %s · %s".format(
                        Locale.ROOT, p.lat, p.lon, RoadPoints.compass(p.heading), p.passes, day(p.lastTs), status(p.status),
                    ),
                )
                appendLine("  ${wmeLink(p.lat, p.lon)}")
                p.note?.takeIf { it.isNotBlank() }?.let { appendLine("  $it") }
            }
            appendLine()
        }
    }

    fun geoJson(points: List<RoadPointEntity>): String {
        val features = JSONArray()
        exportable(points).forEach { p ->
            val type = RoadPointType.of(p.type) ?: RoadPointType.OTHER
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lon).put(p.lat)))
                    .put(
                        "properties",
                        JSONObject()
                            .put("type", type.name)
                            .put("heading", p.heading ?: JSONObject.NULL)
                            .put("passes", p.passes)
                            .put("status", p.status)
                            .put("date", day(p.lastTs))
                            .put("note", p.note ?: JSONObject.NULL)
                            .put("wme", wmeLink(p.lat, p.lon)),
                    ),
            )
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString(2)
    }

    fun title(type: RoadPointType): String = when (type) {
        RoadPointType.SPEED_BUMP -> "Лежачі поліцейські"
        RoadPointType.TRAFFIC_LIGHT -> "Світлофори"
        RoadPointType.LANES -> "Смуги"
        RoadPointType.OTHER -> "Інше"
    }

    /**
     * The review status in words, so the editors know which points the driver checked by eye and
     * which are a single unreviewed mark.
     */
    fun status(status: String): String = when (RoadPointStatus.entries.firstOrNull { it.name == status }) {
        RoadPointStatus.CONFIRMED -> "підтверджено"
        RoadPointStatus.REJECTED -> "відхилено"
        RoadPointStatus.IN_WAZE -> "вже у Waze"
        RoadPointStatus.CANDIDATE, null -> "не перевірено"
    }

    private fun day(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ts))
}
