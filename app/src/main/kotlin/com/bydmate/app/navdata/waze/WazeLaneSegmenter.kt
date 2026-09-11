package com.bydmate.app.navdata.waze

import kotlin.math.abs

/**
 * Finds the individual lane arrows inside a screenshot crop of Waze's `laneGuidanceView` and
 * classifies each of them. Verbatim port of openbyd's `WazeArrowCaptureService`
 * `segmentLaneGuidanceView` (:686-784) and `processSegmentedLanes` (:496-662, multi-lane branch).
 *
 * Waze never puts the lane arrows into the accessibility tree - the strip is one drawable-backed
 * view - so the only way to know what the lanes allow is to look at the pixels. The donor does it
 * in two steps: a per-column "how much ink is in this column" score splits the strip into runs
 * (one per lane), then each run's square cell is reduced to the same 15x15 signature the maneuver
 * arrow uses and looked up in the same table, twice: once at a low threshold, which catches the
 * whole lane glyph (every direction the lane allows), and once at the arrow threshold, which
 * catches only the highlighted arrow inside it (the direction the route takes).
 *
 * Deliberately free of `android.graphics` so the whole thing runs in a plain JVM test: it takes
 * ARGB ints and its own [Box], and hands back arrays of codes and fronts.
 */
object WazeLaneSegmenter {

    /** A rectangle, in whatever coordinate space the caller is using. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** One lane cell found in the strip, in screen coordinates, with its column score. */
    data class Cell(val box: Box, val score: Float)

    /** The crop of the lane whose arrow becomes the maneuver arrow on the panel. */
    class Arrow(val pixels: IntArray, val width: Int, val height: Int)

    /** Outcome of one segmentation: what to send, plus the arrow to classify (null = unknown). */
    class Result(
        val codes: IntArray,
        val fronts: IntArray,
        val scores: FloatArray,
        val mainArrow: Arrow?,
        val mainArrowIndex: Int,
    )

    /** A column carrying at least this much ink is part of a lane (donor: `30.0f`). */
    private const val ACTIVE_COLUMN = 30.0f

    /** Below this peak the strip is noise, not lanes (donor: `> 50.0f` required). */
    private const val MIN_PEAK = 50.0f

    /** Threshold for the whole lane glyph, and for the highlighted arrow inside it. */
    private const val F_FULL = 0.3f
    private const val F_ACTIVE = 0.8f

    /** A lane whose geometry does not fit the crop (donor: `13` / `255`). */
    private const val CODE_INVALID = 13
    private const val FRONT_NONE = 255

    /**
     * Splits the strip into lane cells, left to right.
     *
     * @param px ARGB pixels of the crop, [w] x [h].
     * @param rect the crop's own rectangle in screen coordinates; the returned cells use it too.
     * @param density display density, for the donor's `8dp` gap/minimum and `120dp` maximum.
     */
    fun segment(px: IntArray, w: Int, h: Int, rect: Box, density: Float): List<Cell> {
        if (w <= 0 || h <= 0) return emptyList()
        if (px.size < w * h) return emptyList()
        // The donor divides by `h / 2` as an int; a one-pixel-high strip would divide by zero.
        val rows = h / 2
        if (rows <= 0) return emptyList()

        val diffs = FloatArray(w)
        var y = 0
        while (y < h) {
            val base = y * w
            for (x in 0 until w) {
                val color = px[base + x]
                diffs[x] += (
                    ((color shr 16) and 0xFF) +
                        ((color shr 8) and 0xFF) +
                        (color and 0xFF) +
                        abs(((color ushr 24) and 0xFF) - 255)
                    ).toFloat()
            }
            y += 2
        }
        for (x in 0 until w) diffs[x] = diffs[x] / rows

        val dp8 = (8.0f * density).toInt()
        val dp120 = (120.0f * density).toInt()

        val runs = ArrayList<IntArray>()
        var start = -1
        var last = -1
        for (x in 0 until w) {
            if (diffs[x] >= ACTIVE_COLUMN) {
                if (start == -1) start = x
                last = x
            } else if (start != -1 && x - last > dp8) {
                runs += intArrayOf(start, last)
                start = -1
                last = -1
            }
        }
        if (start != -1) runs += intArrayOf(start, last)

        val cells = ArrayList<Cell>(runs.size)
        for (run in runs) {
            val from = run[0]
            val to = run[1]
            val width = to - from + 1
            if (width < dp8 || width > dp120) continue
            val cx = from + (width / 2) + rect.left
            val half = h / 2
            var left = cx - half
            if (left < rect.left) left = rect.left
            if (left > rect.right) left = rect.right
            var right = cx + half
            if (right < rect.left) right = rect.left
            if (right > rect.right) right = rect.right
            var score = 0.0f
            for (x in from..to) if (diffs[x] > score) score = diffs[x]
            cells += Cell(Box(left, rect.top, right, rect.bottom), score)
        }
        return cells
    }

    /**
     * Classifies every lane cell. Returns null where the donor returns false - no cells, or a peak
     * too weak to be a lane strip - which sends the caller back to the plain arrow path.
     */
    fun process(
        px: IntArray,
        w: Int,
        h: Int,
        rect: Box,
        segments: List<Cell>,
        f: Float = 0.9f,
    ): Result? {
        if (segments.isEmpty()) return null
        var max = 0.0f
        for (cell in segments) if (cell.score > max) max = cell.score
        if (max <= MIN_PEAK) return null

        val codes = IntArray(segments.size)
        val fronts = IntArray(segments.size)
        val scores = FloatArray(segments.size)
        var mainArrow: Arrow? = null
        var mainIndex = -1

        for (i in segments.indices) {
            val cell = segments[i]
            scores[i] = cell.score
            val cw = cell.box.width
            val ch = cell.box.height
            val x0 = cell.box.left - rect.left
            val y0 = cell.box.top - rect.top
            if (cw <= 0 || ch <= 0 || x0 < 0 || y0 < 0 || x0 + cw > w || y0 + ch > h) {
                codes[i] = CODE_INVALID
                fronts[i] = FRONT_NONE
                continue
            }
            val crop = crop(px, w, x0, y0, cw, ch)
            val classified = classifyLane(cw, ch, crop)
            codes[i] = classified[0]
            if (cell.score >= max * f) {
                fronts[i] = classified[1]
                if (mainIndex == -1) {
                    mainArrow = Arrow(crop, cw, ch)
                    mainIndex = i
                }
            } else {
                fronts[i] = FRONT_NONE
            }
        }
        return Result(codes, fronts, scores, mainArrow, mainIndex)
    }

    /** `[full lane code, highlighted direction]` for one lane crop; `[0, 0]` on any failure. */
    internal fun classifyLane(w: Int, h: Int, px: IntArray): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(0, 0)
        return try {
            val full = ArrowSignature.compute(w, h, px, F_FULL, true)
            val fullName = full?.let { WazeArrowTable.match(it)?.name }
            val code = fullName?.let { WazeArrowTable.laneCodeFor(it) } ?: 0
            val active = ArrowSignature.compute(w, h, px, F_ACTIVE, true)
            val activeName = active?.let { WazeArrowTable.match(it)?.name }
            val activeCode = activeName?.let { WazeArrowTable.laneCodeFor(it) } ?: code
            intArrayOf(code, activeCode)
        } catch (_: Throwable) {
            intArrayOf(0, 0)
        }
    }

    private fun crop(px: IntArray, stride: Int, x0: Int, y0: Int, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            System.arraycopy(px, (y0 + y) * stride + x0, out, y * w, w)
        }
        return out
    }
}
