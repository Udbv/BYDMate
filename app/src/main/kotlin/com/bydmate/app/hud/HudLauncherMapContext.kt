package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub
import kotlin.random.Random

/**
 * The "launcher map is navigating" context on the SOME/IP bus, next to the HUD road-info frame.
 *
 * On the DiLink 150 the factory map (`SomeIPMatrixManager`) tells the ADAS / scene-rendering
 * side (`com.byd.sr`, GritPlayer) about the route through the NavigationStatus_LinkInfo (7),
 * SdMapInform (0x8202), Obstacle_LaneLine (0xC), PilotStatus_AlarmInfo (0xD), PlanningLine (0xE)
 * and HeaderInfo (0xF) services. openbyd 2.4.3's default strategy (`LauncherMapCnStrategy`,
 * sniffed from the real map) fires the same events with constant session markers, and that is
 * the configuration the user saw working on the Tang L glass. This is a port of it with
 * BYDMate's guidance values; docs/investigations/tang-l-hud-someip.md.
 */
class HudLauncherMapContext(
    private val sink: HudEventSink,
    private val vehicle: () -> HudVehicleState.Fix? = { HudVehicleState.fix },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val routeIdSource: () -> Long = { Random.nextLong(1_000_000_000L, 10_000_000_000L) },
) {
    companion object {
        private const val TAG = "HudLauncherMapCtx"
        // topic = 0x4 | service(16) | instance(16) | event(16)
        const val TOPIC_NAV_STATUS = 0x4000700078001L          // NavigationStatus_LinkInfoNotify
        const val TOPIC_TRAFFIC_INFO = 0x4000700078003L        // TrafficInfoNotify
        const val TOPIC_NAVI_ACTION_CAMERA = 0x482028202800BL  // SdMapInform naviActionAndCameraNotify
        const val TOPIC_LANES = 0x482028202800CL               // SdMapInform nextIntersectionLanesInfoNotify
        const val TOPIC_LANELINE_8001 = 0x4000c000c8001L
        const val TOPIC_ROUTING_STATUS = 0x4000c000c8003L
        const val TOPIC_MANEUVER_STATUS_1 = 0x4000d000d8001L
        const val TOPIC_MANEUVER_STATUS_2 = 0x4000d000d8002L
        const val TOPIC_PILOT_8005 = 0x4000d000d8005L
        const val TOPIC_ROUTE_METADATA = 0x4000e000e8001L
        const val TOPIC_HEADER_INFO = 0x4000f000f8003L

        val TOPICS: List<Long> = listOf(
            TOPIC_NAV_STATUS, TOPIC_TRAFFIC_INFO, TOPIC_NAVI_ACTION_CAMERA, TOPIC_LANES,
            TOPIC_LANELINE_8001, TOPIC_ROUTING_STATUS, TOPIC_MANEUVER_STATUS_1, TOPIC_MANEUVER_STATUS_2,
            TOPIC_PILOT_8005, TOPIC_ROUTE_METADATA, TOPIC_HEADER_INFO,
        )

        /** Gateway start key of a topic's service: 0xB << 48 | (service << 16 | instance) << 16. */
        fun serviceKeyOf(topic: Long): Long = (((topic ushr 16) and 0xFFFFFFFFL) shl 16) or 0xB000000000000L

        /** Services to open before firing, in topic order, without duplicates. */
        val SERVICE_KEYS: List<Long> = TOPICS.map(::serviceKeyOf).distinct()

        // Session markers exactly as sniffed from the factory map (openbyd LauncherMapCnStrategy).
        private const val NAV_STATE_NAVIGATING = 101L
        private const val LANELINE_MARKER = 2641158014L
        private const val ROUTING_MARKER = 1729875789L
        private const val MANEUVER_MARKER_1 = 3592003832L
        private const val MANEUVER_MARKER_2 = 3817498742L
        private const val PILOT_MARKER = 4073768758L
        private val HEADER_CONSTANTS = doubleArrayOf(
            1161.2184496889508, 971.1426529964466, -19.15885124372106,
            0.0014609250661213498, -1.5712258405572703, 0.0037437084083233626,
        )

        /** Gaode maneuver code -> SdMapInform "main action" (openbyd mapManeuverToMainAction). */
        fun mainAction(gaode: Int): Int = when (gaode) {
            1 -> 2
            2 -> 3
            3, 4 -> 4
            5, 6 -> 5
            7 -> 6
            8 -> 7
            9 -> 8
            10 -> 9
            11, 12 -> 1
            else -> 0
        }
    }

    private var routeId = 0L
    @Volatile var eventsSent: Long = 0L
        private set
    @Volatile var lastRc: Int = 0
        private set

    private fun fire(topic: Long, inner: ByteArray) {
        val rc = sink.fireEvent(topic, ProtoWire.wrap(inner))
        eventsSent++
        lastRc = rc
    }

    /** One guidance tick: the eleven events the launcher map emits while navigating. */
    fun send(s: NavGuidanceHub.Snapshot, counter: Int) {
        if (routeId == 0L) {
            routeId = routeIdSource()
            Log.i(TAG, "session start routeId=$routeId keys=${SERVICE_KEYS.joinToString { "0x" + it.toString(16) }}")
        }
        val fix = vehicle()
        val lon = fix?.lon ?: 0.0
        val lat = fix?.lat ?: 0.0
        val nowUs = nowMs() * 1000.0

        fire(TOPIC_NAV_STATUS, ProtoWire.message { ProtoWire.varint(it, 4, NAV_STATE_NAVIGATING) })
        fire(TOPIC_NAVI_ACTION_CAMERA, ProtoWire.message {
            ProtoWire.varint(it, 1, s.maneuverGaode.toLong())
            ProtoWire.varint(it, 2, mainAction(s.maneuverGaode).toLong())
            ProtoWire.varint(it, 3, 0L)
            ProtoWire.varint(it, 4, s.distanceMeters.toLong())
        })
        fire(TOPIC_TRAFFIC_INFO, ProtoWire.message {
            ProtoWire.varint(it, 17, s.totalDistMeters.toLong())
            ProtoWire.varint(it, 18, s.etaSeconds.toLong())
            ProtoWire.double(it, 11, lon)
            ProtoWire.double(it, 12, lat)
        })
        fire(TOPIC_LANELINE_8001, ProtoWire.message { ProtoWire.varint(it, 1, LANELINE_MARKER) })
        fire(TOPIC_ROUTING_STATUS, ProtoWire.message { ProtoWire.varint(it, 1, ROUTING_MARKER) })
        fire(TOPIC_MANEUVER_STATUS_1, ProtoWire.message {
            ProtoWire.varint(it, 1, MANEUVER_MARKER_1)
            ProtoWire.varint(it, 5, 1L)
            ProtoWire.double(it, 12, 5.0)
            ProtoWire.double(it, 13, 2.2)
        })
        fire(TOPIC_MANEUVER_STATUS_2, ProtoWire.message { ProtoWire.varint(it, 1, MANEUVER_MARKER_2) })
        fire(TOPIC_PILOT_8005, ProtoWire.message {
            ProtoWire.varint(it, 1, PILOT_MARKER)
            ProtoWire.varint(it, 3, 1L)
        })
        fire(TOPIC_ROUTE_METADATA, ProtoWire.message {
            ProtoWire.varint(it, 1, routeId)
            ProtoWire.varint(it, 3, 1L)
            ProtoWire.double(it, 4, nowUs)
        })
        fire(TOPIC_HEADER_INFO, ProtoWire.message { out ->
            ProtoWire.bytes(out, 1, ProtoWire.message {
                ProtoWire.varint(it, 1, routeId)
                ProtoWire.varint(it, 2, counter.toLong())
                ProtoWire.double(it, 3, nowUs)
            })
            ProtoWire.bytes(out, 3, ProtoWire.message {
                HEADER_CONSTANTS.forEachIndexed { i, v -> ProtoWire.double(it, i + 1, v) }
            })
            ProtoWire.varint(out, 7, 7L)
        })
    }

    /** Route ended: the three "navigation over" events, then a fresh route id next time. */
    fun stop() {
        if (routeId == 0L) return
        val nowUs = nowMs() * 1000.0
        fire(TOPIC_MANEUVER_STATUS_1, ProtoWire.message {
            ProtoWire.varint(it, 1, MANEUVER_MARKER_1)
            ProtoWire.varint(it, 5, 0L)
            ProtoWire.double(it, 12, 0.0)
            ProtoWire.double(it, 13, 0.0)
        })
        fire(TOPIC_PILOT_8005, ProtoWire.message {
            ProtoWire.varint(it, 1, PILOT_MARKER)
            ProtoWire.varint(it, 3, 0L)
        })
        fire(TOPIC_ROUTE_METADATA, ProtoWire.message {
            ProtoWire.varint(it, 1, routeId)
            ProtoWire.varint(it, 3, 0L)
            ProtoWire.double(it, 4, nowUs)
        })
        Log.i(TAG, "session stop routeId=$routeId events=$eventsSent lastRc=$lastRc")
        routeId = 0L
    }
}
