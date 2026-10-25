package com.bydmate.app.helper.push

/**
 * Piecewise-linear compression of one fid's samples (a "swinging door"): a sample is dropped while
 * the straight line from the last written point to the newest sample passes within [tolerance] of
 * every sample dropped in between. So a ramp or a steady value costs two lines, while a bump, a step or a peak
 * is kept exactly. A reader rebuilds the signal by joining the written points with straight lines.
 *
 * [P] is whatever the caller writes for a sample; the compressor only decides which one, and when.
 * Not thread-safe: the recorder calls it under its own lock.
 */
internal class LineCompressor<P>(private val tolerance: Double) {

    private var anchored = false
    private var anchorT = 0L
    private var anchorV = 0.0

    private var held: P? = null
    private var heldT = 0L
    private var heldV = 0.0

    /** Bounds of the slopes from the anchor that still fit every sample since it. */
    private var low = 0.0
    private var high = 0.0

    /** When the sample that is held back arrived; null when none is held. */
    val heldAt: Long? get() = if (held != null) heldT else null

    /**
     * Feeds one sample at [t] ms with value [v]. Returns the payload to write now: the very first
     * sample, or the held one when this sample no longer fits the line through it. Otherwise null.
     */
    fun offer(t: Long, v: Double, payload: P): P? {
        if (!anchored) {
            anchor(t, v)
            return payload
        }
        val previous = held
        if (previous == null) {
            hold(t, v, payload)
            return null
        }
        val dt = (t - anchorT).coerceAtLeast(1L).toDouble()
        val newLow = maxOf(low, (v - tolerance - anchorV) / dt)
        val newHigh = minOf(high, (v + tolerance - anchorV) / dt)
        // The line written is anchor → this sample itself, so its own slope must fit the corridor
        // of every sample dropped before it, not just overlap it.
        val slope = (v - anchorV) / dt
        if (slope in newLow..newHigh) {
            low = newLow
            high = newHigh
            held = payload
            heldT = t
            heldV = v
            return null
        }
        // The line broke: the last sample that still fitted becomes the new anchor.
        anchor(heldT, heldV)
        hold(t, v, payload)
        return previous
    }

    /** Starts a new line at a point the caller wrote itself, dropping whatever was held. */
    fun restartAt(t: Long, v: Double) = anchor(t, v)

    /** Writes out the held sample, if any: the signal went quiet or the run is ending. */
    fun flush(): P? {
        val previous = held ?: return null
        anchor(heldT, heldV)
        return previous
    }

    private fun anchor(t: Long, v: Double) {
        anchored = true
        anchorT = t
        anchorV = v
        held = null
    }

    private fun hold(t: Long, v: Double, payload: P) {
        held = payload
        heldT = t
        heldV = v
        val dt = (t - anchorT).coerceAtLeast(1L).toDouble()
        low = (v - tolerance - anchorV) / dt
        high = (v + tolerance - anchorV) / dt
    }
}
