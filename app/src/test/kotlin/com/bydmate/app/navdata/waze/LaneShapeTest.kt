package com.bydmate.app.navdata.waze

import com.bydmate.app.navdata.NavLanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lane arrows drawn the way Waze draws them on its dark strip (2026-10-31 photo): a white stroke
 * up from the bottom and a triangular head, 78 x 78 cells.
 */
class LaneShapeTest {

    private val size = 78
    private val dark = 0xFF101418.toInt()
    private val white = 0xFFF5F5F5.toInt()

    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0..y1) for (x in x0..x1) if (x in 0 until w && y in 0 until h) px[y * w + x] = c
        }
        /** A head pointing up with its tip at (cx, top). */
        fun headUp(cx: Int, top: Int, half: Int, len: Int, c: Int) {
            for (dy in 0 until len) {
                val hw = half * dy / len
                rect(cx - hw, top + dy, cx + hw, top + dy, c)
            }
        }
        /** A head pointing right with its tip at (right, cy). */
        fun headRight(right: Int, cy: Int, half: Int, len: Int, c: Int) {
            for (dx in 0 until len) {
                val hh = half * dx / len
                rect(right - dx, cy - hh, right - dx, cy + hh, c)
            }
        }
        fun headLeft(left: Int, cy: Int, half: Int, len: Int, c: Int) {
            for (dx in 0 until len) {
                val hh = half * dx / len
                rect(left + dx, cy - hh, left + dx, cy + hh, c)
            }
        }
    }

    private fun straight(canvas: Canvas = Canvas(size, size, dark), cx: Int = 39): Canvas = canvas.apply {
        rect(cx - 2, 16, cx + 2, 74, white)
        headUp(cx, 4, 10, 14, white)
    }

    private fun rightTurn(canvas: Canvas = Canvas(size, size, dark)): Canvas = canvas.apply {
        rect(27, 26, 31, 74, white)          // stem
        rect(27, 22, 58, 26, white)          // arm to the right
        headRight(72, 24, 10, 14, white)
    }

    private fun leftTurn(canvas: Canvas = Canvas(size, size, dark)): Canvas = canvas.apply {
        rect(46, 26, 50, 74, white)
        rect(19, 22, 50, 26, white)
        headLeft(5, 24, 10, 14, white)
    }

    @Test fun `a straight lane reads straight`() {
        val c = straight()
        assertEquals(setOf(NavLanes.Dir.STRAIGHT), LaneShape.directions(c.w, c.h, c.px))
    }

    @Test fun `turns read by the branch beside the stem`() {
        assertEquals(setOf(NavLanes.Dir.RIGHT), rightTurn().let { LaneShape.directions(it.w, it.h, it.px) })
        assertEquals(setOf(NavLanes.Dir.LEFT), leftTurn().let { LaneShape.directions(it.w, it.h, it.px) })
    }

    @Test fun `straight and right in one lane reads both`() {
        val c = Canvas(size, size, dark).apply {
            rect(28, 16, 32, 74, white)
            headUp(30, 4, 9, 12, white)
            rect(30, 40, 58, 44, white)
            headRight(72, 42, 9, 13, white)
        }
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), LaneShape.directions(c.w, c.h, c.px))
    }

    @Test fun `a dimmed lane on the strip still reads its shape`() {
        val grey = 0xFF8A8F96.toInt()
        val c = Canvas(size, size, dark).apply {
            rect(37, 16, 41, 74, grey)
            headUp(39, 4, 10, 14, grey)
        }
        assertEquals(setOf(NavLanes.Dir.STRAIGHT), LaneShape.directions(c.w, c.h, c.px))
    }

    @Test fun `the top half of a sliding strip is not an arrow yet`() {
        // The 2026-10-30/31 crops: only the upper part of the stroke inside the cell.
        val c = Canvas(size, size, dark).apply { rect(37, 0, 41, 30, white) }
        assertNull(LaneShape.directions(c.w, c.h, c.px))
    }

    @Test fun `an empty cell is nothing`() {
        val c = Canvas(size, size, dark)
        assertNull(LaneShape.directions(c.w, c.h, c.px))
    }

    @Test fun `ink low beside the stem is not read`() {
        // A U-turn hook coming back down next to the stem.
        val c = straight().apply { rect(5, 60, 12, 74, white) }
        assertNull(LaneShape.directions(c.w, c.h, c.px))
    }

    @Test fun `the 2026-10-31 strip reads left, straight, and straight with right`() {
        // A rounded left turn: the stem on the right, bending over to a head pointing left.
        val left = Canvas(size, size, dark).apply {
            rect(52, 20, 56, 74, white)
            rect(22, 14, 56, 18, white)
            headLeft(6, 16, 9, 16, white)
        }
        val grey = 0xFF9A9EA4.toInt()
        val straight = straight(Canvas(size, size, dark)).apply { for (i in px.indices) if (px[i] == white) px[i] = grey }
        // The side branch of the third lane starts below the middle of the arrow.
        val straightRight = Canvas(size, size, dark).apply {
            rect(30, 14, 34, 74, grey)
            headUp(32, 2, 9, 12, grey)
            rect(32, 44, 58, 48, grey)
            headRight(72, 46, 9, 14, grey)
        }
        assertEquals(setOf(NavLanes.Dir.LEFT), LaneShape.directions(size, size, left.px))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT), LaneShape.directions(size, size, straight.px))
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.RIGHT), LaneShape.directions(size, size, straightRight.px))
    }

    private val grey = 0xFF7A7F86.toInt()

    /** Straight and left in one lane, each branch in its own colour. */
    private fun straightLeft(straightC: Int, leftC: Int) = Canvas(size, size, dark).apply {
        rect(44, 44, 48, 74, white)          // shared stem
        rect(44, 16, 48, 44, straightC)
        headUp(46, 4, 9, 12, straightC)
        rect(18, 40, 46, 44, leftC)
        headLeft(5, 42, 9, 13, leftC)
    }

    @Test fun `the branch Waze lit is the active one`() {
        val left = straightLeft(straightC = grey, leftC = white).let { LaneShape.read(it.w, it.h, it.px) }!!
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.LEFT), left.directions)
        assertEquals(NavLanes.Dir.LEFT, left.lit)
        val straight = straightLeft(straightC = white, leftC = grey).let { LaneShape.read(it.w, it.h, it.px) }!!
        assertEquals(NavLanes.Dir.STRAIGHT, straight.lit)
    }

    @Test fun `branches lit alike name no active one`() {
        val both = straightLeft(straightC = white, leftC = white).let { LaneShape.read(it.w, it.h, it.px) }!!
        assertEquals(setOf(NavLanes.Dir.STRAIGHT, NavLanes.Dir.LEFT), both.directions)
        assertNull(both.lit)
        assertNull(straight().let { LaneShape.read(it.w, it.h, it.px) }!!.lit)
    }
}
