package com.bydmate.app.navdata.waze

import com.bydmate.app.navdata.NavLaneState
import com.bydmate.app.navdata.WazeVisualManeuverReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane pixel path on synthetic strips built out of the signature table's own shapes.
 *
 * Waze paints the lane strip as light arrows on a dark bar, so the fixtures do the same: the
 * reference bits are rendered as bright ink on black, and a lane the route does not take is drawn
 * dimmer, which is what the donor's column score keys off to decide which lanes are
 * "front". No `android.graphics` anywhere - the whole path is `IntArray` in, arrays out.
 */
class WazeLaneSegmenterTest {

    private val density = 1.0f
    private val side = 60
    private val gap = 30
    private val origin = WazeLaneSegmenter.Box(100, 200, 0, 0)

    private fun bitsOf(name: String): String =
        WazeArrowTable.entries.entries.first { it.value == name }.key.asString()

    /** Renders one reference shape as ink of [level] brightness on black. */
    private fun lane(name: String, level: Int): IntArray {
        val rendered = ArrowFixtures.render(bitsOf(name), side)
        val ink = 0xFF000000.toInt() or (level shl 16) or (level shl 8) or level
        return IntArray(rendered.size) { if (rendered[it] == ArrowFixtures.INK) ink else BLACK }
    }

    /** Lays the lanes out left to right on a black bar, [gap] pixels apart. */
    private fun strip(lanes: List<IntArray>): Triple<IntArray, Int, Int> {
        val w = lanes.size * side + (lanes.size - 1) * gap
        val px = IntArray(w * side) { BLACK }
        lanes.forEachIndexed { index, lane ->
            val x0 = index * (side + gap)
            for (y in 0 until side) {
                System.arraycopy(lane, y * side, px, y * w + x0, side)
            }
        }
        return Triple(px, w, side)
    }

    private fun rectFor(w: Int, h: Int) =
        WazeLaneSegmenter.Box(origin.left, origin.top, origin.left + w, origin.top + h)

    @Test
    fun `three lane strip yields left forward right with the bright lane on route`() {
        val (px, w, h) = strip(
            listOf(
                lane("big_trans_direction_left", DIM),
                lane("big_trans_direction_forward", BRIGHT),
                lane("big_trans_direction_right", DIM),
            ),
        )
        val rect = rectFor(w, h)
        val segments = WazeLaneSegmenter.segment(px, w, h, rect, density)
        assertEquals(3, segments.size)
        val result = WazeLaneSegmenter.process(px, w, h, rect, segments)
        assertNotNull(result)
        assertEquals(listOf(1, 0, 3), result!!.codes.toList())
        assertEquals(listOf(255, 0, 255), result.fronts.toList())
        // The single on-route lane is the one whose crop becomes the maneuver arrow.
        assertEquals(1, result.mainArrowIndex)
        assertNotNull(result.mainArrow)
    }

    @Test
    fun `a nine lane strip is segmented into nine lanes`() {
        val (px, w, h) = strip(List(9) { lane("big_trans_direction_forward", BRIGHT) })
        val rect = rectFor(w, h)
        val segments = WazeLaneSegmenter.segment(px, w, h, rect, density)
        assertEquals(9, segments.size)
        val result = WazeLaneSegmenter.process(px, w, h, rect, segments)
        assertNotNull(result)
        assertEquals(9, result!!.codes.size)
        assertEquals(9, result.fronts.size)
        // Every lane is equally bright, so every lane is on route and the first is the arrow.
        assertEquals(0, result.mainArrowIndex)
    }

    @Test
    fun `a strip too faint to be lane artwork is refused`() {
        val w = 200
        val h = 60
        val faint = 0xFF000000.toInt() or (13 shl 16) or (13 shl 8) or 13
        val px = IntArray(w * h) { BLACK }
        for (y in 0 until h) for (x in 40 until 120) px[y * w + x] = faint
        val rect = rectFor(w, h)
        val segments = WazeLaneSegmenter.segment(px, w, h, rect, density)
        assertTrue("a faint block still segments", segments.isNotEmpty())
        assertTrue(segments.all { it.score <= 50f })
        assertNull(WazeLaneSegmenter.process(px, w, h, rect, segments))
    }

    @Test
    fun `an empty strip produces no lanes`() {
        val w = 200
        val h = 60
        val px = IntArray(w * h) { BLACK }
        val rect = rectFor(w, h)
        val segments = WazeLaneSegmenter.segment(px, w, h, rect, density)
        assertTrue(segments.isEmpty())
        assertNull(WazeLaneSegmenter.process(px, w, h, rect, segments))
    }

    @Test
    fun `bounds outside the screenshot are rejected before any cropping`() {
        val inside = NavLaneState.ContainerBounds(0, 10, 20, 110, 80)
        assertTrue(WazeVisualManeuverReader.isValidBounds(inside, 1920, 1080))
        assertFalse(WazeVisualManeuverReader.isValidBounds(inside, 100, 1080))
        assertFalse(WazeVisualManeuverReader.isValidBounds(inside, 1920, 60))
        assertFalse(
            WazeVisualManeuverReader.isValidBounds(
                NavLaneState.ContainerBounds(0, -1, 20, 110, 80),
                1920,
                1080,
            ),
        )
        assertFalse(
            WazeVisualManeuverReader.isValidBounds(
                NavLaneState.ContainerBounds(0, 10, 20, 10, 80),
                1920,
                1080,
            ),
        )
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()

        /** A lane the route takes: Waze draws it at full brightness. */
        const val BRIGHT = 255

        /** A lane the route does not take: Waze dims it, which drops it below the 0.9 front cut. */
        const val DIM = 180
    }
}
