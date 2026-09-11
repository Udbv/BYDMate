package com.bydmate.app.navdata.waze

/**
 * Turns a rectangle of ARGB pixels into openbyd's 15x15 arrow signature and finds the nearest
 * reference shape. Straight port of the donor's `un0.b` (signature) and `un0.d` (matcher).
 *
 * Deliberately free of `android.graphics`: it takes a plain `IntArray` of ARGB, so the same code
 * runs on the accessibility screenshot in the car and on decoded PNG files in a JVM unit test. That
 * is what lets the 45 reference signatures be verified without a device.
 *
 * What it does, in the donor's order:
 *  1. gray = (R + G + B) / 3 for every pixel, ignoring any colour weighting;
 *  2. if the first pixel is translucent the image is treated as having an alpha channel, and each
 *     pixel is composited over a flat fill - white when the artwork reads as light, 47 when dark,
 *     which is how a white arrow on transparency keeps its contrast against a dark panel;
 *  3. contrast below 30 levels means "no shape here" and answers null;
 *  4. a threshold at [f] of the value range splits foreground from background, inverted when the
 *     image is mostly bright and inversion is allowed;
 *  5. the rectangle is divided into 15x15 cells on exact integer boundaries `(k * side) / 15`, and
 *     a cell becomes a set bit when more than half its pixels are foreground.
 */
object ArrowSignature {

    /** A matched reference shape and how many of the 225 cells differed. */
    data class Match(val name: String, val hamming: Int)

    /** Below this many levels of range the crop carries no shape at all (blank or uniform). */
    private const val MIN_CONTRAST = 30

    /** Value a bright image is composited over; dark artwork uses [DARK_FILL] instead. */
    private const val LIGHT_FILL = 255
    private const val DARK_FILL = 47

    /** Mid-grey: above it an image counts as "mostly bright". */
    private const val MID = 128

    /** Exact hit aside, this many differing cells still counts as the same arrow. */
    private const val MAX_HAMMING = 4

    /**
     * Tolerance once the crop has been re-aligned by up to two cells in each direction.
     *
     * The donor accepts 18. On the Tang L (2026-09-11 drive, 105 px arrows) every true match came
     * in at 0-8 and every false one - a shrinking arrow mid-animation or a partial crop read as a
     * roundabout - at 11-14, so the line is drawn at 9. A wrong arrow on the glass costs more than
     * a missed frame: the next capture is two seconds away.
     */
    private const val MAX_SHIFTED_HAMMING = 9

    /** Cells the shifted search tries in each axis. */
    private const val MAX_SHIFT = 2

    /**
     * Signature of a `width` x `height` ARGB rectangle, or null when it holds no usable shape.
     *
     * @param f fraction of the value range used as the foreground threshold (the donor classifies
     *   arrows at 0.8 and lane cells at 0.3/0.8).
     * @param invertAllowed lets a mostly bright image flip to "dark ink on light paper".
     */
    fun compute(
        width: Int,
        height: Int,
        px: IntArray,
        f: Float,
        invertAllowed: Boolean,
    ): BitGrid225? {
        if (width <= 0 || height <= 0) return null
        val count = width * height
        if (px.size < count) return null

        // The donor samples only the first pixel: a screenshot crop is fully opaque, a decoded
        // drawable is not, and that single test is what separates the two paths below.
        val hasAlpha = px.isNotEmpty() && ((px[0] ushr 24) and 0xFF) < 255

        val gray = IntArray(count)
        val alpha = if (hasAlpha) IntArray(count) else gray
        var opaquePixels = 0
        var opaqueSum = 0L
        for (i in 0 until count) {
            val color = px[i]
            val value = (((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)) / 3
            gray[i] = value
            if (hasAlpha) {
                val a = (color ushr 24) and 0xFF
                alpha[i] = a
                if (a > 0) {
                    opaquePixels++
                    opaqueSum += value.toLong()
                }
            }
        }

        // "Is this artwork light or dark?" - measured on the median when every pixel is opaque,
        // and on the mean of the opaque pixels only when parts of the image are transparent, so a
        // large transparent margin cannot drag the answer towards black.
        val lightImage = if (!hasAlpha || opaquePixels >= count) {
            median(count, gray) >= MID
        } else {
            !(opaquePixels > 0 && opaqueSum.toFloat() / opaquePixels >= MID.toFloat())
        }

        val values: IntArray
        var minValue = 255
        var maxValue = 0
        if (hasAlpha) {
            values = IntArray(count)
            val fill = if (lightImage) LIGHT_FILL else DARK_FILL
            for (i in 0 until count) {
                val a = alpha[i]
                val composited = ((255 - a) * fill + gray[i] * a) / 255
                values[i] = composited
                if (composited > maxValue) maxValue = composited
                if (composited < minValue) minValue = composited
            }
        } else {
            values = gray
            for (i in 0 until count) {
                val v = gray[i]
                if (v > maxValue) maxValue = v
                if (v < minValue) minValue = v
            }
        }

        val range = maxValue - minValue
        if (range < MIN_CONTRAST) return null

        val invert = invertAllowed && median(count, values) >= MID
        val threshold = minValue + if (invert) ((1.0f - f) * range).toInt() else (range * f).toInt()

        val grid = BitGrid225(BitGrid225.SIZE)
        var bit = 0
        for (row in 0 until BitGrid225.SIDE) {
            val top = (row * height) / BitGrid225.SIDE
            val bottom = minOf(((row + 1) * height) / BitGrid225.SIDE, height)
            for (column in 0 until BitGrid225.SIDE) {
                val left = (column * width) / BitGrid225.SIDE
                val right = minOf(((column + 1) * width) / BitGrid225.SIDE, width)
                val cellPixels = (bottom - top) * (right - left)
                var foreground = 0
                for (y in top until bottom) {
                    val rowStart = y * width
                    for (x in left until right) {
                        val v = values[rowStart + x]
                        if (invert) {
                            if (v < threshold) foreground++
                        } else if (v >= threshold) {
                            foreground++
                        }
                    }
                }
                if (foreground > (cellPixels * 0.5f).toInt()) grid.set(bit)
                bit++
            }
        }
        return grid
    }

    /**
     * Nearest reference shape for [signature], or null when nothing is close enough.
     *
     * Three passes, exactly as the donor: an exact map hit; then the minimum Hamming distance over
     * every same-length entry, accepted at 4 or less; then the same search over 24 copies of the
     * signature shifted by up to two cells in each direction, carrying the first pass's minimum
     * forward and accepted at 18 or less. The shift pass is what absorbs a crop that is a few
     * pixels off-centre, which is the normal case when the arrow bounds come from a11y.
     */
    fun match(signature: BitGrid225, table: Map<BitGrid225, String>): Match? {
        table[signature]?.let { return Match(it, 0) }

        val length = signature.length
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for ((reference, name) in table) {
            if (reference.length != length) continue
            val distance = signature.hamming(reference)
            if (distance < bestDistance) {
                bestDistance = distance
                best = name
            }
        }
        if (bestDistance <= MAX_HAMMING) return best?.let { Match(it, bestDistance) }

        val variants = ArrayList<BitGrid225>(24)
        for (dy in -MAX_SHIFT..MAX_SHIFT) {
            for (dx in -MAX_SHIFT..MAX_SHIFT) {
                if (dx == 0 && dy == 0) continue
                val shifted = BitGrid225(length)
                for (i in 0 until length) {
                    val sourceX = (i % BitGrid225.SIDE) - dx
                    val sourceY = (i / BitGrid225.SIDE) - dy
                    if (sourceX < 0 || sourceX >= BitGrid225.SIDE) continue
                    if (sourceY < 0 || sourceY >= BitGrid225.SIDE) continue
                    val source = sourceY * BitGrid225.SIDE + sourceX
                    if (source in 0 until length && signature[source]) shifted.set(i)
                }
                variants += shifted
            }
        }
        for ((reference, name) in table) {
            if (reference.length != length) continue
            for (variant in variants) {
                val distance = variant.hamming(reference)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = name
                }
            }
        }
        return if (bestDistance <= MAX_SHIFTED_HAMMING) best?.let { Match(it, bestDistance) } else null
    }

    /** Histogram median of the first [count] entries, clamped to 0..255 (the donor's `un0.a`). */
    private fun median(count: Int, values: IntArray): Int {
        val histogram = IntArray(256)
        for (i in 0 until count) {
            val v = values[i].coerceIn(0, 255)
            histogram[v]++
        }
        val target = (count / 2) + 1
        var seen = 0
        for (level in 0 until 256) {
            seen += histogram[level]
            if (seen >= target) return level
        }
        return 0
    }
}
