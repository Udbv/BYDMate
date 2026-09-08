package com.bydmate.app.hud

import com.bydmate.app.navdata.NavLaneCodes
import com.bydmate.app.navdata.NavLanes
import com.bydmate.app.navdata.NavLanes.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLaneWriterTest {

    private fun lane(vararg dirs: Dir, take: Dir? = null) =
        NavLanes.Lane(directions = dirs.toSet(), recommended = take)

    private fun writesOf(lanes: NavLanes): Map<Int, Int> =
        HudLaneWriter.buildWrites(lanes).toMap()

    @Test fun `feature ids match BYD's own catalogue`() {
        assertEquals(427_827_416, HudLaneWriter.FID_LANE_COUNT)
        assertEquals(427_827_288, HudLaneWriter.fidGlyph(0))
        assertEquals(427_827_296, HudLaneWriter.fidLineType(0))
        assertEquals(427_827_300, HudLaneWriter.fidRecommended(0))
        // Slots are 16 apart.
        assertEquals(427_827_288 + 7 * 16, HudLaneWriter.fidGlyph(7))
        assertEquals(427_827_296 + 7 * 16, HudLaneWriter.fidLineType(7))
        assertEquals(427_827_300 + 7 * 16, HudLaneWriter.fidRecommended(7))
    }

    @Test fun `every write set covers the count and all eight slots exactly once`() {
        val w = HudLaneWriter.buildWrites(NavLanes(listOf(lane(Dir.STRAIGHT))))
        assertEquals(1 + 8 * 3, w.size)
        assertEquals(w.size, w.map { it.first }.toSet().size)
        assertEquals(HudLaneWriter.FID_LANE_COUNT, w.first().first)
    }

    @Test fun `three lanes with the middle one recommended`() {
        val lanes = NavLanes(
            listOf(
                lane(Dir.LEFT),
                lane(Dir.STRAIGHT, take = Dir.STRAIGHT),
                lane(Dir.RIGHT),
            ),
            distanceMeters = 200,
        )
        val m = writesOf(lanes)
        assertEquals(3, m[HudLaneWriter.FID_LANE_COUNT])
        // Slot 0: plain left. Donor plain glyph = code + 1, left code = 1.
        assertEquals(NavLaneCodes.LEFT + 1, m[HudLaneWriter.fidGlyph(0)])
        assertEquals(HudLaneWriter.LINE_TYPE_PLAIN, m[HudLaneWriter.fidLineType(0)])
        assertEquals(HudLaneWriter.RECOMMENDED_NO, m[HudLaneWriter.fidRecommended(0)])
        // Slot 1: recommended straight. Donor active glyph = dir + 26, straight dir = 0.
        assertEquals(26, m[HudLaneWriter.fidGlyph(1)])
        assertEquals(HudLaneWriter.LINE_TYPE_ON_ROUTE, m[HudLaneWriter.fidLineType(1)])
        assertEquals(HudLaneWriter.RECOMMENDED_YES, m[HudLaneWriter.fidRecommended(1)])
        // Slot 2: plain right, code 3.
        assertEquals(NavLaneCodes.RIGHT + 1, m[HudLaneWriter.fidGlyph(2)])
        assertEquals(HudLaneWriter.LINE_TYPE_PLAIN, m[HudLaneWriter.fidLineType(2)])
    }

    @Test fun `unused slots are switched off`() {
        val m = writesOf(NavLanes(listOf(lane(Dir.STRAIGHT))))
        for (slot in 1 until NavLanes.MAX_LANES) {
            assertEquals("slot $slot glyph", HudLaneWriter.GLYPH_NONE, m[HudLaneWriter.fidGlyph(slot)])
            assertEquals("slot $slot line type", HudLaneWriter.LINE_TYPE_UNUSED, m[HudLaneWriter.fidLineType(slot)])
            assertEquals("slot $slot recommended", HudLaneWriter.RECOMMENDED_ABSENT, m[HudLaneWriter.fidRecommended(slot)])
        }
    }

    @Test fun `no lanes clears the strip and blanks every slot`() {
        val m = writesOf(NavLanes.NONE)
        assertEquals(0, m[HudLaneWriter.FID_LANE_COUNT])
        for (slot in 0 until NavLanes.MAX_LANES) {
            assertEquals(HudLaneWriter.RECOMMENDED_ABSENT, m[HudLaneWriter.fidRecommended(slot)])
        }
    }

    @Test fun `a multi-direction lane uses the combined glyph for the direction taken`() {
        // Straight + right is code 4; taking the right gives the donor's combined glyph 54.
        val m = writesOf(NavLanes(listOf(lane(Dir.STRAIGHT, Dir.RIGHT, take = Dir.RIGHT))))
        assertEquals(54, m[HudLaneWriter.fidGlyph(0)])
        assertEquals(HudLaneWriter.LINE_TYPE_ON_ROUTE, m[HudLaneWriter.fidLineType(0)])
        // Taking the straight from the same lane gives 53.
        val straight = writesOf(NavLanes(listOf(lane(Dir.STRAIGHT, Dir.RIGHT, take = Dir.STRAIGHT))))
        assertEquals(53, straight[HudLaneWriter.fidGlyph(0)])
    }

    @Test fun `a multi-direction lane nobody takes falls back to its plain glyph`() {
        val m = writesOf(NavLanes(listOf(lane(Dir.STRAIGHT, Dir.RIGHT))))
        assertEquals(NavLaneCodes.STRAIGHT_RIGHT + 1, m[HudLaneWriter.fidGlyph(0)])
        assertEquals(HudLaneWriter.LINE_TYPE_PLAIN, m[HudLaneWriter.fidLineType(0)])
    }

    @Test fun `a direction with no combined glyph falls back rather than blanking`() {
        // Code 2 (straight+left) has glyphs for straight and left only; a right would be absurd
        // input, and must not produce -1 on a lane the panel is told is valid.
        assertEquals(-1, HudLaneWriter.complexGlyph(2, NavLaneCodes.DIR_RIGHT))
        assertEquals(NavLaneCodes.STRAIGHT_LEFT + 1, HudLaneWriter.glyphFor(2, NavLaneCodes.DIR_RIGHT))
    }

    @Test fun `more lanes than slots are dropped, not wrapped`() {
        val many = NavLanes((0 until 12).map { lane(Dir.STRAIGHT) })
        val m = writesOf(many)
        assertEquals(NavLanes.MAX_LANES, m[HudLaneWriter.FID_LANE_COUNT])
        for (slot in 0 until NavLanes.MAX_LANES) {
            assertEquals(HudLaneWriter.RECOMMENDED_NO, m[HudLaneWriter.fidRecommended(slot)])
        }
    }

    @Test fun `direction sets map onto the donor lane codes`() {
        assertEquals(NavLaneCodes.STRAIGHT, NavLaneCodes.codeFor(setOf(Dir.STRAIGHT)))
        assertEquals(NavLaneCodes.LEFT, NavLaneCodes.codeFor(setOf(Dir.LEFT)))
        assertEquals(NavLaneCodes.RIGHT, NavLaneCodes.codeFor(setOf(Dir.RIGHT)))
        assertEquals(NavLaneCodes.STRAIGHT_LEFT, NavLaneCodes.codeFor(setOf(Dir.STRAIGHT, Dir.LEFT)))
        assertEquals(NavLaneCodes.STRAIGHT_RIGHT, NavLaneCodes.codeFor(setOf(Dir.STRAIGHT, Dir.RIGHT)))
        assertEquals(NavLaneCodes.LEFT_RIGHT, NavLaneCodes.codeFor(setOf(Dir.LEFT, Dir.RIGHT)))
        assertEquals(NavLaneCodes.STRAIGHT_LEFT_RIGHT, NavLaneCodes.codeFor(setOf(Dir.LEFT, Dir.STRAIGHT, Dir.RIGHT)))
        assertEquals(NavLaneCodes.UTURN_LEFT, NavLaneCodes.codeFor(setOf(Dir.UTURN_LEFT)))
        assertEquals(NavLaneCodes.UTURN_LEFT_STRAIGHT, NavLaneCodes.codeFor(setOf(Dir.UTURN_LEFT, Dir.STRAIGHT)))
        assertEquals(NavLaneCodes.EMPTY, NavLaneCodes.codeFor(emptySet()))
    }

    @Test fun `only the lanes the route takes are marked recommended`() {
        val m = writesOf(
            NavLanes(listOf(lane(Dir.LEFT), lane(Dir.STRAIGHT, take = Dir.STRAIGHT), lane(Dir.RIGHT)))
        )
        assertEquals(HudLaneWriter.RECOMMENDED_NO, m[HudLaneWriter.fidRecommended(0)])
        assertEquals(HudLaneWriter.RECOMMENDED_YES, m[HudLaneWriter.fidRecommended(1)])
        assertEquals(HudLaneWriter.RECOMMENDED_NO, m[HudLaneWriter.fidRecommended(2)])
    }

    @Test fun `the donor fallback marks every present lane instead`() {
        val lanes = NavLanes(listOf(lane(Dir.LEFT), lane(Dir.STRAIGHT, take = Dir.STRAIGHT)))
        val m = HudLaneWriter.buildWrites(lanes, donorRecommendedFlag = true).toMap()
        assertEquals(HudLaneWriter.RECOMMENDED_PRESENT, m[HudLaneWriter.fidRecommended(0)])
        assertEquals(HudLaneWriter.RECOMMENDED_PRESENT, m[HudLaneWriter.fidRecommended(1)])
        // Unused slots stay absent either way.
        assertEquals(HudLaneWriter.RECOMMENDED_ABSENT, m[HudLaneWriter.fidRecommended(2)])
    }

    @Test fun `an unreadable lane still occupies its slot`() {
        // directions empty -> code EMPTY; the slot stays valid so the strip keeps its geometry.
        val m = writesOf(NavLanes(listOf(lane(Dir.LEFT), NavLanes.Lane(emptySet()))))
        assertEquals(2, m[HudLaneWriter.FID_LANE_COUNT])
        assertEquals(HudLaneWriter.RECOMMENDED_NO, m[HudLaneWriter.fidRecommended(1)])
        assertTrue(m[HudLaneWriter.fidGlyph(1)] != null)
    }
}
