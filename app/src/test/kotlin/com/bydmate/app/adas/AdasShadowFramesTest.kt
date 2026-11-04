package com.bydmate.app.adas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The idle frames byte for byte as the factory map's protobuf writes them. */
class AdasShadowFramesTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** uint32 -1: 0xFFFFFFFF, five varint bytes. */
    private val none = intArrayOf(0xFF, 0xFF, 0xFF, 0xFF, 0x0F)

    @Test fun `navigation status is the bus enum, 101 navigating and 100 idle`() {
        assertEquals(101, AdasShadowFrames.NAVI_ACTIVE)
        assertEquals(100, AdasShadowFrames.NAVI_STOPPED)
        // NavigationStatus_LinkInfoNotify{1 {4 NavigationStatus}}
        assertArrayEquals(b(0x0A, 0x02, 0x20, 0x64), AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_STOPPED).bytes)
        assertArrayEquals(b(0x0A, 0x02, 0x20, 0x65), AdasShadowFrames.naviStatus(AdasShadowFrames.NAVI_ACTIVE).bytes)
    }

    @Test fun `a uint32 -1 is a five byte varint`() {
        assertArrayEquals(b(0x08, *none), Proto().varint(1, AdasShadowFrames.U32_NONE).bytes())
    }

    @Test fun `idle traffic info has the jam distance and road class at -1, the rest left out`() {
        val inner = b(0x10, *none) + b(0x98, 0x01, *none)
        assertArrayEquals(b(0x0A, inner.size) + inner, AdasShadowFrames.idleTrafficInfo().bytes)
    }

    @Test fun `the all-zero traffic info the clear used to send is an empty struct`() {
        assertArrayEquals(b(0x0A, 0x00), AdasShadowFrames.trafficInfo(0, 0).bytes)
    }

    @Test fun `idle location puts the car on path -1 with every index, offset and ownership -1`() {
        val here = RoutePoint(1.0, 2.0)
        val one = b(0, 0, 0, 0, 0, 0, 0xF0, 0x3F)
        val two = b(0, 0, 0, 0, 0, 0, 0x00, 0x40)
        val minusOne = b(0, 0, 0, 0, 0, 0, 0xF0, 0xBF)
        val inner = b(0x09) + one + b(0x11) + two + b(0x19) + one + b(0x21) + two +
            b(0x39) + minusOne +
            b(0x48, *none) + b(0x50, *none) + b(0x58, *none) + b(0x60, *none) + b(0x68, *none)
        assertEquals(75, inner.size)
        assertArrayEquals(b(0x0A, inner.size) + inner, AdasRouteFrames.idleLocation(here).bytes)
        assertEquals(AdasRouteFrames.TOPIC_VEHICLE_LOCATION, AdasRouteFrames.idleLocation(here).topic)
    }

    @Test fun `one clearing round, in order, with and without a position`() {
        val here = RoutePoint(1.0, 2.0)
        assertEquals(
            listOf(
                AdasShadowFrames.TOPIC_NAVI_STATUS, AdasRouteFrames.TOPIC_VEHICLE_LOCATION,
                AdasShadowFrames.TOPIC_TRAFFIC_INFO, AdasShadowFrames.TOPIC_ACTION_CAMERA,
            ),
            AdasParkedTest.clearingFrames(here).map { it.topic },
        )
        assertEquals(
            "no location at 0/0 without a position",
            listOf(AdasShadowFrames.TOPIC_NAVI_STATUS, AdasShadowFrames.TOPIC_TRAFFIC_INFO, AdasShadowFrames.TOPIC_ACTION_CAMERA),
            AdasParkedTest.clearingFrames(null).map { it.topic },
        )
    }
}
