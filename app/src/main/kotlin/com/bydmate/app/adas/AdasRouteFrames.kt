package com.bydmate.app.adas

import org.json.JSONArray
import org.json.JSONObject

/** A point of a route, degrees. */
data class RoutePoint(val lat: Double, val lon: Double)

/**
 * A route offered to the driving computer the way the factory map offers one (#53), built from a
 * route the app has (the map page's OSRM route; Waze does not give its line away). From
 * docs/investigations/noa-someip-contract.md §2–3 and §5:
 * - 0x002B/0x8002 `naviSDRouteNotify`: the route as JSON `NaviDataDetail{time, pathCount, length,
 *   currentIndex, payload}`, the payload `MessageSend{msg, code, data:[route], routeType,
 *   endPOIInfo, mapProvider}` with the route in the `platformb.RouteInfo` shape (its fields are
 *   the best known of the variants the map picks by ADAS type; which one this car wants is
 *   unknown);
 * - 0x002D/0x8001 `NaviPathUserSelectStsNotify`: the path id chosen;
 * - 0x8202/0x8008 `sdVehicleLocation`: the car on that path, first link.
 * Every AutoNavi identity (link ids, admin codes, URIDs) is unknown outside China and goes out
 * empty: whether the driving computer takes such a route at all is what the parked test is for.
 */
object AdasRouteFrames {

    const val TOPIC_SD_ROUTE = 0x0004002B002B8002L
    const val TOPIC_PATH_SELECT = 0x0004002D002D8001L
    const val TOPIC_VEHICLE_LOCATION = 0x0004820282028008L

    /** Links of at most this length: the factory route's links are road pieces of this order. */
    private const val LINK_M = 200.0
    private const val HEAD_M = 20_000.0
    private const val STEP_M = 100.0
    private const val M_PER_DEG_LAT = 111_195.0
    private const val NO_LINK_ID = -1L
    private const val FORMWAY_MAIN = 1
    private const val LANES = 2
    private const val OWNERSHIP = 1
    private const val CODE_OK = 200
    private const val NO_PATH = -1.0

    /**
     * Topics that carry the route itself: the factory map sends them once per route change
     * (`SDRouteUtil n.a`), not with every position. The tests send them only when their bytes
     * change, never at the frame rate (a route of up to 20 km five times a second over TCP).
     */
    val ONCE_TOPICS: Set<Long> = setOf(TOPIC_SD_ROUTE, TOPIC_PATH_SELECT)

    fun of(points: List<RoutePoint>, pathId: Long, nowMs: Long, here: RoutePoint): List<ShadowFrame> {
        val links = links(points)
        val total = links.sumOf { it.lengthM }
        val json = detail(route(points, links, pathId, total), nowMs)
        return listOf(
            ShadowFrame(
                TOPIC_SD_ROUTE, "naviSDRouteNotify", "pathID=$pathId links=${links.size} m=${total.toInt()} json=${json.length}c",
                wrap(Proto().varint(3, 1).string(4, json).bytes()),
            ),
            ShadowFrame(
                TOPIC_PATH_SELECT, "NaviPathUserSelectStsNotify", "pathID_Req=$pathId",
                wrap(Proto().varint(3, pathId).bytes()),
            ),
            location(here, pathId, link = 0, linkRemainM = (links.firstOrNull()?.lengthM ?: 0.0).toLong(), stepRemainM = total.toLong()),
        )
    }

    /** `sdVehicleLocation`: the car at [link] of step 0 of the route [pathId], as the map sends it per fix. */
    fun location(here: RoutePoint, pathId: Long, link: Int, linkRemainM: Long, stepRemainM: Long) = ShadowFrame(
        TOPIC_VEHICLE_LOCATION, "sdVehicleLocation", "pathID=$pathId step=0 link=$link linkOffset=$linkRemainM",
        wrap(
            Proto()
                .double(1, here.lat).double(2, here.lon).double(3, here.lat).double(4, here.lon)
                .double(7, pathId.toDouble())
                .varint(9, 0).varint(10, link.toLong())
                .varint(11, linkRemainM)
                .varint(12, stepRemainM)
                .varint(13, OWNERSHIP.toLong())
                .bytes(),
        ),
    )

    /**
     * `sdVehicleLocation` with no route, as the factory map sends it every second while idle
     * (`SomeIPMatrixManager` log of 4.11 21:04: `pathId:-1 curSegIdx:-1 curLinkIdx:-1 segOffset:-1
     * linkOffset:-1 … ownerShip:-1`): the position, `curSDRouteID` -1.0 and every index, offset
     * and the road ownership -1 as uint32. "No route" on the bus is these explicit -1, not fields
     * left out, so a position of ours on a path is overwritten by one on none.
     */
    fun idleLocation(here: RoutePoint) = ShadowFrame(
        TOPIC_VEHICLE_LOCATION, "sdVehicleLocation", "idle pathID=-1 step=-1 link=-1 linkOffset=-1",
        wrap(
            Proto()
                .double(1, here.lat).double(2, here.lon).double(3, here.lat).double(4, here.lon)
                .double(7, NO_PATH)
                .varint(9, AdasShadowFrames.U32_NONE).varint(10, AdasShadowFrames.U32_NONE)
                .varint(11, AdasShadowFrames.U32_NONE).varint(12, AdasShadowFrames.U32_NONE)
                .varint(13, AdasShadowFrames.U32_NONE)
                .bytes(),
        ),
    )

    /**
     * Taking our route back (experimental): `NaviPathUserSelectStsNotify{pathID_Req=0}` (proto3
     * leaves the 0 out, an empty struct) and `naviSDRouteNotify` with no route
     * (`NaviDataDetail{pathCount:0, payload: MessageSend{data:[]}}`). Neither the contract nor the
     * factory map does this: on navigation stop the map sends no route and no path select at all,
     * it relies on the idle location and status 100 and on its next route replacing the old one.
     * What the driving computer makes of these is untested, see [AdasParkedTest.retractRoute].
     */
    fun retraction(nowMs: Long): List<ShadowFrame> {
        val payload = JSONObject()
            .put("msg", "response").put("code", CODE_OK).put("data", JSONArray())
            .put("routeType", 0).put("endPOIInfo", JSONObject()).put("mapProvider", "waze")
        val json = JSONObject()
            .put("time", nowMs).put("pathCount", 0).put("length", 1).put("currentIndex", 0)
            .put("payload", payload.toString())
            .toString()
        return listOf(
            ShadowFrame(TOPIC_PATH_SELECT, "NaviPathUserSelectStsNotify", "retract pathID_Req=0", wrap(byteArrayOf())),
            ShadowFrame(
                TOPIC_SD_ROUTE, "naviSDRouteNotify", "retract pathCount=0 json=${json.length}c",
                wrap(Proto().varint(3, 1).string(4, json).bytes()),
            ),
        )
    }

    /** A straight route of [lengthM] north of [from], points every 100 m: links of 200 m. */
    fun straight(from: RoutePoint, lengthM: Int): List<RoutePoint> {
        val stepDeg = STEP_M / M_PER_DEG_LAT
        return List(lengthM / STEP_M.toInt() + 1) { RoutePoint(from.lat + it * stepDeg, from.lon) }
    }

    /** The route's first [maxM] metres: the test needs the stretch ahead, not a whole long trip. */
    fun head(points: List<RoutePoint>, maxM: Double = HEAD_M): List<RoutePoint> {
        if (points.isEmpty()) return points
        var len = 0.0
        for (i in 1 until points.size) {
            len += distanceM(points[i - 1], points[i])
            if (len >= maxM) return points.subList(0, i + 1)
        }
        return points
    }

    internal class Link(val begIdx: Int, val pntCount: Int, val lengthM: Double)

    /** The polyline cut into links of at most [LINK_M]; consecutive links share their end point. */
    internal fun links(points: List<RoutePoint>): List<Link> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Link>()
        var beg = 0
        var len = 0.0
        for (i in 1 until points.size) {
            len += distanceM(points[i - 1], points[i])
            if (len >= LINK_M || i == points.lastIndex) {
                out += Link(beg, i - beg + 1, len)
                beg = i
                len = 0.0
            }
        }
        return out
    }

    internal fun route(points: List<RoutePoint>, links: List<Link>, pathId: Long, total: Double): JSONObject {
        val linkInfos = JSONArray()
        links.forEachIndexed { i, l ->
            linkInfos.put(
                JSONObject()
                    .put("linkID", NO_LINK_ID).put("roadClass", 0).put("roadName", "").put("length", l.lengthM.toInt())
                    .put("linkType", 0).put("formway", FORMWAY_MAIN).put("hasTrafficLight", false).put("laneNum", LANES)
                    .put("hasMultiOut", false).put("mainAction", 0).put("assistantAction", 0).put("adminCode", 0)
                    .put("ownership", OWNERSHIP).put("hasParallel", false).put("direction", 0).put("speedLimit", 0)
                    .put("hasMixFork", false).put("roadDirection", 0).put("isOverHead", false).put("isAtService", false)
                    .put("relatedSegmentIndex", 0).put("pntBegIdx", l.begIdx).put("linkIndex", i).put("pntCount", l.pntCount),
            )
        }
        val segment = JSONObject()
            .put("segmentIndex", 0).put("linkCount", links.size).put("length", total.toInt())
            .put("mainAction", 0).put("assistantAction", 0).put("trafficLightNum", 0).put("linkBegIdx", 0)
        val pts = JSONArray()
        points.forEach { pts.put(JSONObject().put("x", it.lon).put("y", it.lat)) }
        return JSONObject()
            .put("pathID", pathId).put("segmentCount", 1).put("SegmentsInfo", JSONArray().put(segment))
            .put("linkCount", links.size).put("linkInfos", linkInfos)
            .put("pointCount", points.size).put("points", pts)
            .put("viaRoadInfo", JSONArray()).put("viaPointInfo", JSONArray()).put("trafficLights", JSONArray())
    }

    internal fun detail(route: JSONObject, nowMs: Long): String {
        val payload = JSONObject()
            .put("msg", "response").put("code", CODE_OK).put("data", JSONArray().put(route))
            .put("routeType", 0).put("endPOIInfo", JSONObject()).put("mapProvider", "waze")
        return JSONObject()
            .put("time", nowMs).put("pathCount", 1).put("length", 1).put("currentIndex", 0)
            .put("payload", payload.toString())
            .toString()
    }

    private fun distanceM(a: RoutePoint, b: RoutePoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = kotlin.math.sin(dp / 2).let { it * it } + kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2).let { it * it }
        return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
    }

    private fun wrap(inner: ByteArray): ByteArray = Proto().message(1, inner).bytes()
}
