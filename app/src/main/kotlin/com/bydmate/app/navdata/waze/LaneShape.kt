package com.bydmate.app.navdata.waze

import com.bydmate.app.navdata.NavLanes
import kotlin.math.abs

/**
 * Reads a Waze lane arrow by its shape when no drawable signature matches it.
 *
 * Waze's lane arrows are thin strokes on the dark strip: a stem coming up from the bottom and a
 * head at the end of each branch (the 2026-10-31 photo: two straight lanes, a 1 px divider between
 * them). [WazeArrowTable] carries the big manoeuvre arrows only, so every lane classified as
 * "unknown" on three drives in a row and the HUD never got a strip. Here the arrow is measured
 * instead: where the stem stands at the bottom, whether the topmost ink sits over it (straight),
 * and whether a branch reaches out to the left or right in the upper half (a turn).
 *
 * Conservative on purpose: a crop whose ink does not reach most of its height is the strip
 * still sliding in (most crops of those drives were the top half of an arrow), and anything
 * reaching down beside the stem is a U-turn or a shape not read here; both return null, and the
 * strip then stays off the glass rather than showing a wrong arrow.
 */
object LaneShape {

    fun directions(w: Int, h: Int, px: IntArray): Set<NavLanes.Dir>? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        val ink = inkMask(w, h, px) ?: return null
        val box = ink.box ?: return null
        val height = box.bottom - box.top + 1
        if (height < h * MIN_HEIGHT_FRACTION) return null
        if (ink.count < w * h * MIN_INK_FRACTION) return null

        val stemX = ink.centroidX(box.bottom - (height * STEM_BAND).toInt(), box.bottom) ?: return null
        if (hookedLow(ink, box, height, stemX)) return null
        val dirs = linkedSetOf<NavLanes.Dir>()
        // Straight: the very top of the arrow is its head, over the stem.
        val tipX = ink.centroidX(box.top, box.top + (height * TIP_BAND).toInt())
        if (tipX != null && abs(tipX - stemX) <= height * STRAIGHT_TOLERANCE) dirs += NavLanes.Dir.STRAIGHT
        dirs += turns(ink, box, height, stemX)
        return dirs.takeIf { it.isNotEmpty() }
    }

    /** A branch out beside the stem in the upper half is a turn that way. */
    private fun turns(ink: Ink, box: Box, height: Int, stemX: Double): List<NavLanes.Dir> {
        val reach = height * ARM_REACH
        val upperBottom = box.top + height / 2
        return listOfNotNull(
            NavLanes.Dir.LEFT.takeIf { ink.anyIn(box.top, upperBottom, 0, (stemX - reach).toInt()) },
            NavLanes.Dir.RIGHT.takeIf { ink.anyIn(box.top, upperBottom, (stemX + reach).toInt(), ink.w - 1) },
        )
    }

    /** Ink low down and away from the stem: a U-turn hook or something not read here. */
    private fun hookedLow(ink: Ink, box: Box, height: Int, stemX: Double): Boolean {
        val reach = height * ARM_REACH
        val lowTop = box.bottom - (height * STEM_BAND).toInt()
        return ink.anyIn(lowTop, box.bottom, 0, (stemX - reach).toInt()) ||
            ink.anyIn(lowTop, box.bottom, (stemX + reach).toInt(), ink.w - 1)
    }

    /** The ink pixels: those that stand out from the crop's border (the strip's background). */
    private fun inkMask(w: Int, h: Int, px: IntArray): Ink? {
        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) {
            border += luma(px[x])
            border += luma(px[(h - 1) * w + x])
        }
        for (y in 0 until h) {
            border += luma(px[y * w])
            border += luma(px[y * w + w - 1])
        }
        border.sort()
        val background = border[border.size / 2]
        val mask = BooleanArray(w * h) { abs(luma(px[it]) - background) > INK_CONTRAST }
        return Ink(w, h, mask)
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * LUMA_R + g * LUMA_G + b * LUMA_B) / LUMA_SUM
    }

    private class Ink(val w: Int, val h: Int, val mask: BooleanArray) {
        val count = mask.count { it }

        val box: Box? = run {
            var top = -1
            var bottom = -1
            for (y in 0 until h) {
                if ((0 until w).any { mask[y * w + it] }) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            if (top < 0) null else Box(top, bottom)
        }

        fun centroidX(fromY: Int, toY: Int): Double? {
            var sum = 0.0
            var n = 0
            for (y in fromY.coerceAtLeast(0)..toY.coerceAtMost(h - 1)) {
                for (x in 0 until w) if (mask[y * w + x]) { sum += x; n++ }
            }
            return if (n == 0) null else sum / n
        }

        fun anyIn(fromY: Int, toY: Int, fromX: Int, toX: Int): Boolean {
            for (y in fromY.coerceAtLeast(0)..toY.coerceAtMost(h - 1)) {
                for (x in fromX.coerceAtLeast(0)..toX.coerceAtMost(w - 1)) if (mask[y * w + x]) return true
            }
            return false
        }
    }

    private data class Box(val top: Int, val bottom: Int)

    /** Ink that differs from the background by this much luma is part of the arrow. */
    private const val INK_CONTRAST = 60
    /** A whole arrow spans most of its cell; less is the strip sliding in. */
    private const val MIN_HEIGHT_FRACTION = 0.55
    private const val MIN_INK_FRACTION = 0.01
    /** Bottom part of the arrow taken as its stem. */
    private const val STEM_BAND = 0.25
    /** Top part of the arrow taken as its tip. */
    private const val TIP_BAND = 0.12
    /** A tip within this share of the arrow's height from the stem points straight on. */
    private const val STRAIGHT_TOLERANCE = 0.12
    /** A branch reaching this share of the height beside the stem is a turn. */
    private const val ARM_REACH = 0.28
    private const val LUMA_R = 299
    private const val LUMA_G = 587
    private const val LUMA_B = 114
    private const val LUMA_SUM = 1000
}
