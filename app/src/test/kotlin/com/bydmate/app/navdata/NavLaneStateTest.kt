package com.bydmate.app.navdata

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLaneStateTest {

    @After fun tearDown() = NavLaneState.clear()

    private fun lanes(n: Int) = NavLanes((0 until n).map { NavLanes.Lane(setOf(NavLanes.Dir.STRAIGHT)) })

    @Test fun `a fresh strip reads back`() {
        NavLaneState.update(lanes(3), nowMs = 1_000)
        assertEquals(3, NavLaneState.current(nowMs = 1_000).lanes.size)
        assertEquals(3, NavLaneState.current(nowMs = 1_000 + NavLaneState.TTL_MS).lanes.size)
    }

    @Test fun `a stale strip reads as none`() {
        NavLaneState.update(lanes(3), nowMs = 1_000)
        assertTrue(NavLaneState.current(nowMs = 1_000 + NavLaneState.TTL_MS + 1).isEmpty)
    }

    @Test fun `an empty update clears immediately rather than ageing out`() {
        NavLaneState.update(lanes(2), nowMs = 1_000)
        NavLaneState.update(NavLanes.NONE, nowMs = 1_100)
        assertTrue(NavLaneState.current(nowMs = 1_100).isEmpty)
    }

    @Test fun `clear wipes the strip`() {
        NavLaneState.update(lanes(4), nowMs = 1_000)
        NavLaneState.clear()
        assertTrue(NavLaneState.current(nowMs = 1_000).isEmpty)
    }

    @Test fun `labels map onto directions`() {
        assertEquals(setOf(NavLanes.Dir.LEFT), WazeLaneReader.directionsOf("←"))
        assertEquals(setOf(NavLanes.Dir.RIGHT), WazeLaneReader.directionsOf("→"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT), WazeLaneReader.directionsOf("↑"))
        assertEquals(
            setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT),
            WazeLaneReader.directionsOf("↑→"),
        )
        assertTrue(WazeLaneReader.directionsOf(null).isEmpty())
        assertTrue(WazeLaneReader.directionsOf("  ").isEmpty())
    }
}
