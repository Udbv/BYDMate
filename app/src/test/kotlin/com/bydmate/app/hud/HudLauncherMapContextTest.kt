package com.bydmate.app.hud

import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLauncherMapContextTest {

    private class RecordingSink : HudEventSink {
        val events = mutableListOf<Pair<Long, ByteArray>>()
        override fun fireEvent(topic: Long, payload: ByteArray): Int { events += topic to payload; return 0 }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun `service keys derive from the topics like the donor and the SDK`() {
        assertEquals(0xB000700070000L, HudLauncherMapContext.serviceKeyOf(HudLauncherMapContext.TOPIC_NAV_STATUS))
        assertEquals(0xB820282020000L, HudLauncherMapContext.serviceKeyOf(HudLauncherMapContext.TOPIC_NAVI_ACTION_CAMERA))
        assertEquals(
            listOf(0xB000700070000L, 0xB820282020000L, 0xB000c000c0000L, 0xB000d000d0000L, 0xB000e000e0000L, 0xB000f000f0000L),
            HudLauncherMapContext.SERVICE_KEYS,
        )
        // Never the HUD service itself: that one is opened by the controller.
        assertTrue(HudSomeIpBridge.SERVICE_ID_NAVI !in HudLauncherMapContext.SERVICE_KEYS)
    }

    @Test fun `one tick fires the eleven launcher-map events in the donor order`() {
        val sink = RecordingSink()
        val ctx = HudLauncherMapContext(sink, vehicle = { HudVehicleState.Fix(50.45, 30.52, 90.0, 40, 100, 0L) },
            nowMs = { 1_700_000_000_000L }, routeIdSource = { 4242L })
        val s = NavGuidanceHub.Snapshot(active = true, maneuverGaode = 2, distanceMeters = 80,
            road = "x", etaSeconds = 540, totalDistMeters = 6500)
        ctx.send(s, 7)
        assertEquals(
            listOf(
                HudLauncherMapContext.TOPIC_NAV_STATUS, HudLauncherMapContext.TOPIC_NAVI_ACTION_CAMERA,
                HudLauncherMapContext.TOPIC_TRAFFIC_INFO, HudLauncherMapContext.TOPIC_LANELINE_8001,
                HudLauncherMapContext.TOPIC_ROUTING_STATUS, HudLauncherMapContext.TOPIC_MANEUVER_STATUS_1,
                HudLauncherMapContext.TOPIC_MANEUVER_STATUS_2, HudLauncherMapContext.TOPIC_PILOT_8005,
                HudLauncherMapContext.TOPIC_ROUTE_METADATA, HudLauncherMapContext.TOPIC_HEADER_INFO,
            ),
            sink.events.map { it.first },
        )
        // NavigationStatus: outer field 1 wrapping { f4 = 101 }
        assertEquals("0a022065", sink.events[0].second.hex())
        // naviActionAndCamera: f1 icon 2, f2 main action 3 (right), f3 0, f4 distance 80
        assertEquals("0a08080210031800205000".substring(0, 18), sink.events[1].second.hex().substring(0, 18))
        assertEquals(10L, ctx.eventsSent)
    }

    @Test fun `stop sends the three end-of-route events once and resets the route id`() {
        val sink = RecordingSink()
        val ctx = HudLauncherMapContext(sink, vehicle = { null }, nowMs = { 0L }, routeIdSource = { 1L })
        ctx.stop()                       // nothing started yet -> nothing sent
        assertEquals(0, sink.events.size)
        ctx.send(NavGuidanceHub.Snapshot(active = true, maneuverGaode = 11, distanceMeters = 500), 1)
        sink.events.clear()
        ctx.stop()
        assertEquals(
            listOf(HudLauncherMapContext.TOPIC_MANEUVER_STATUS_1, HudLauncherMapContext.TOPIC_PILOT_8005, HudLauncherMapContext.TOPIC_ROUTE_METADATA),
            sink.events.map { it.first },
        )
        ctx.stop()
        assertEquals(3, sink.events.size)
    }

    @Test fun `main action table matches the donor`() {
        assertEquals(2, HudLauncherMapContext.mainAction(1))
        assertEquals(3, HudLauncherMapContext.mainAction(2))
        assertEquals(4, HudLauncherMapContext.mainAction(3))
        assertEquals(8, HudLauncherMapContext.mainAction(9))
        assertEquals(1, HudLauncherMapContext.mainAction(11))
        assertEquals(0, HudLauncherMapContext.mainAction(48))
    }

    @Test fun `guide line has ten points and bends for a right turn`() {
        val straight = HudGeometry.guideLine(11, 50.0, 30.0, 0.0)
        val right = HudGeometry.guideLine(2, 50.0, 30.0, 0.0)
        assertEquals(10, straight.count { it == '[' } - 1)
        assertTrue(straight.startsWith("[[30.0,50.0,0],"))
        assertTrue(straight != right)
        assertEquals("30.0,50.00045045045045,0", HudGeometry.guidePoint(0, 11, 50.0, 30.0, 0.0))
    }
}
