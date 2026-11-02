package com.bydmate.app.data.roadpoints

import com.bydmate.app.data.local.entity.RoadPointEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Мої точки» (#22): when a mark is a place driven past again, and what the export says. */
class RoadPointsTest {

    private fun point(lat: Double, lon: Double, heading: Double?, id: Long = 1, status: String = "CANDIDATE") =
        RoadPointEntity(id, 0, 0, lat, lon, heading, "SPEED_BUMP", 1, status)

    /** ~1 m of latitude. */
    private val m = 1.0 / 111_320.0

    @Test fun `a mark within 15 m on the same course is the same bump`() {
        val known = point(50.0, 30.0, 90.0)
        assertEquals(known, RoadPoints.mergeTarget(50.0 + 10 * m, 30.0, 100.0, listOf(known)))
        assertNull(RoadPoints.mergeTarget(50.0 + 20 * m, 30.0, 90.0, listOf(known)))
    }

    @Test fun `the way back is another point, an unknown course merges`() {
        val known = point(50.0, 30.0, 90.0)
        assertNull(RoadPoints.mergeTarget(50.0, 30.0, 270.0, listOf(known)))
        assertEquals(known, RoadPoints.mergeTarget(50.0, 30.0, null, listOf(known)))
        assertTrue(RoadPoints.sameCourse(350.0, 10.0))
        assertFalse(RoadPoints.sameCourse(0.0, 45.0))
    }

    @Test fun `the box holds every point of the merge radius`() {
        val b = RoadPoints.box(50.0, 30.0)
        assertTrue(RoadPoints.distanceM(50.0, 30.0, b[1], 30.0) >= RoadPoints.MERGE_RADIUS_M - 0.01)
        assertTrue(RoadPoints.distanceM(50.0, 30.0, 50.0, b[3]) >= RoadPoints.MERGE_RADIUS_M - 0.01)
    }

    @Test fun `the course in words`() {
        assertEquals("Пн", RoadPoints.compass(0.0))
        assertEquals("Сх", RoadPoints.compass(90.0))
        assertEquals("ПдЗх", RoadPoints.compass(225.0))
        assertEquals("Пн", RoadPoints.compass(355.0))
        assertEquals("—", RoadPoints.compass(null))
    }

    @Test fun `the export lists the points with a WME link, rejected ones left out`() {
        val points = listOf(point(50.4501, 30.5234, 90.0), point(50.0, 30.0, null, id = 2, status = "REJECTED"))
        val text = RoadPointExport.text(points)
        assertTrue(text, text.contains("Точки для Waze Map Editor (BYDMate): 1"))
        assertTrue(text, text.contains("Лежачі поліцейські — 1"))
        assertTrue(text, text.contains("https://www.waze.com/uk/editor?env=row&lat=50.450100&lon=30.523400&zoomLevel=20"))
        val json = JSONObject(RoadPointExport.geoJson(points))
        assertEquals(1, json.getJSONArray("features").length())
        val f = json.getJSONArray("features").getJSONObject(0)
        assertEquals(30.5234, f.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 1e-9)
        assertEquals("SPEED_BUMP", f.getJSONObject("properties").getString("type"))
    }

    @Test fun `the voice says what became of the mark`() {
        assertEquals("Отметил: лежачий", RoadPointPhrases.answer(MarkResult.Added(RoadPointType.SPEED_BUMP)))
        assertEquals("Светофор, уже отмечен: проезд 2", RoadPointPhrases.answer(MarkResult.Passed(RoadPointType.TRAFFIC_LIGHT, 2)))
    }
}
