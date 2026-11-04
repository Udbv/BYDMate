package com.bydmate.app.adas

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route offered to the driving computer in the parked test (#53). */
class AdasRouteFramesTest {

    /** Points every ~111 m north along a meridian. */
    private fun line(n: Int) = List(n) { RoutePoint(50.0 + it * 0.001, 30.0) }

    @Test fun `links are at most two hundred metres and share their ends`() {
        val links = AdasRouteFrames.links(line(11))
        assertEquals(5, links.size)
        links.zipWithNext().forEach { (a, b) -> assertEquals(a.begIdx + a.pntCount - 1, b.begIdx) }
        assertEquals(10, links.last().begIdx + links.last().pntCount - 1)
        assertTrue(links.all { it.lengthM < 250 })
        assertEquals(1112.0, links.sumOf { it.lengthM }, 5.0)
    }

    @Test fun `no route from fewer than two points`() {
        assertTrue(AdasRouteFrames.links(line(1)).isEmpty())
    }

    @Test fun `head keeps only the stretch ahead`() {
        assertEquals(10, AdasRouteFrames.head(line(100), maxM = 1_000.0).size)
        assertEquals(5, AdasRouteFrames.head(line(5), maxM = 1_000.0).size)
    }

    @Test fun `the route json is the factory map's shape`() {
        val pts = line(4)
        val links = AdasRouteFrames.links(pts)
        val detail = JSONObject(AdasRouteFrames.detail(AdasRouteFrames.route(pts, links, 7L, 333.0), 1_000L))
        assertEquals(1, detail.getInt("pathCount"))
        val payload = JSONObject(detail.getString("payload"))
        assertEquals(200, payload.getInt("code"))
        val route = payload.getJSONArray("data").getJSONObject(0)
        assertEquals(7L, route.getLong("pathID"))
        assertEquals(4, route.getInt("pointCount"))
        assertEquals(30.0, route.getJSONArray("points").getJSONObject(0).getDouble("x"), 0.0)
        assertEquals(links.size, route.getJSONArray("linkInfos").length())
    }

    @Test fun `three frames on the route, path select and location topics`() {
        val frames = AdasRouteFrames.of(line(4), 9L, 0L, RoutePoint(50.0, 30.0))
        assertEquals(
            listOf(AdasRouteFrames.TOPIC_SD_ROUTE, AdasRouteFrames.TOPIC_PATH_SELECT, AdasRouteFrames.TOPIC_VEHICLE_LOCATION),
            frames.map { it.topic },
        )
        // wrap(field 1) { varint field 3 = 9 }
        assertArrayEquals(byteArrayOf(0x0A, 0x02, 0x18, 0x09), frames[1].bytes)
    }

    @Test fun `the retraction is path 0 and a route detail with no route`() {
        val frames = AdasRouteFrames.retraction(1_000L)
        assertEquals(listOf(AdasRouteFrames.TOPIC_PATH_SELECT, AdasRouteFrames.TOPIC_SD_ROUTE), frames.map { it.topic })
        assertArrayEquals(byteArrayOf(0x0A, 0x00), frames[0].bytes)
        val json = frames[1].bytes.toString(Charsets.UTF_8).let { it.substring(it.indexOf('{')) }
        val detail = JSONObject(json)
        assertEquals(0, detail.getInt("pathCount"))
        assertEquals(0, JSONObject(detail.getString("payload")).getJSONArray("data").length())
    }

    @Test fun `only the route and the path select are sent once`() {
        assertEquals(setOf(AdasRouteFrames.TOPIC_SD_ROUTE, AdasRouteFrames.TOPIC_PATH_SELECT), AdasRouteFrames.ONCE_TOPICS)
    }

    @Test fun `proto doubles are little-endian fixed64 and strings are length-delimited utf-8`() {
        assertArrayEquals(
            byteArrayOf(0x09, 0, 0, 0, 0, 0, 0, 0xF0.toByte(), 0x3F),
            Proto().double(1, 1.0).bytes(),
        )
        assertArrayEquals(byteArrayOf(0x22, 0x02, 0xD0.toByte(), 0x96.toByte()), Proto().string(4, "Ж").bytes())
    }
}
