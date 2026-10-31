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
}
