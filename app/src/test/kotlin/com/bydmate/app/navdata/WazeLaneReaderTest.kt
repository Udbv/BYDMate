package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lane labels as Waze writes them into each lane cell's content description, matched against the
 * checked-in language packs. The car runs Waze in Ukrainian, so Ukrainian is the case that has to
 * work; the others are covered so a pack edit cannot silently break them.
 */
class WazeLaneReaderTest {

    @Before fun setUp() = NaviPhraseFixtures.load()

    private fun dirs(label: String?) = WazeLaneReader.directionsOf(label)

    @Test fun `ukrainian single-direction lanes`() {
        assertEquals(setOf(NavLanes.Dir.LEFT), dirs("ліворуч"))
        assertEquals(setOf(NavLanes.Dir.RIGHT), dirs("праворуч"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT), dirs("прямо"))
        assertEquals(setOf(NavLanes.Dir.UTURN_LEFT), dirs("розворот"))
    }

    @Test fun `ukrainian multi-direction lanes`() {
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), dirs("прямо / праворуч"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.LEFT), dirs("прямо / ліворуч"))
        assertEquals(
            setOf(NavLanes.Dir.LEFT, NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT),
            dirs("ліворуч / прямо / праворуч"),
        )
    }

    @Test fun `a left lane is never read as a right one`() {
        val left = dirs("ліворуч")
        assertTrue(NavLanes.Dir.RIGHT !in left)
        val right = dirs("праворуч")
        assertTrue(NavLanes.Dir.LEFT !in right)
    }

    @Test fun `english and russian lanes`() {
        assertEquals(setOf(NavLanes.Dir.LEFT), dirs("left"))
        assertEquals(setOf(NavLanes.Dir.RIGHT), dirs("right"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), dirs("straight / right"))
        assertEquals(setOf(NavLanes.Dir.LEFT), dirs("налево"))
        assertEquals(setOf(NavLanes.Dir.RIGHT), dirs("направо"))
    }

    @Test fun `arrow characters work when there is no text`() {
        assertEquals(setOf(NavLanes.Dir.LEFT), dirs("←"))
        assertEquals(setOf(NavLanes.Dir.RIGHT), dirs("→"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT), dirs("↑"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), dirs("↑→"))
    }

    @Test fun `an unlabelled lane yields no directions rather than a guess`() {
        assertTrue(dirs(null).isEmpty())
        assertTrue(dirs("").isEmpty())
        assertTrue(dirs("   ").isEmpty())
        assertTrue(dirs("lane_3").isEmpty())
    }

    @Test fun `case and separators do not matter`() {
        assertEquals(setOf(NavLanes.Dir.LEFT), dirs("ЛІВОРУЧ"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), dirs("Прямо, Праворуч"))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), dirs("прямо|праворуч"))
    }
}
