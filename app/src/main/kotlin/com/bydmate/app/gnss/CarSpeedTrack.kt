package com.bydmate.app.gnss

/**
 * The car's own speed (the vehicle data's km/h, polled every second or few while driving) over
 * the last minutes, so a position change can be set against how far the car could have gone.
 *
 * Every answer leans the safe way for the guard: [maxDriven] is an upper bound (the highest
 * speed seen around each second, [FALLBACK_MPS] where nothing was seen), [minSpeed] is answered
 * only when samples cover the whole stretch.
 */
class CarSpeedTrack {
    private data class Sample(val atMs: Long, val mps: Double)

    private val samples = ArrayDeque<Sample>()

    fun add(atMs: Long, kmh: Int) {
        if (kmh < 0) return
        // The clock is monotonic; a sample from before the last one means a reset: start over.
        if (samples.isNotEmpty() && atMs < samples.last().atMs) samples.clear()
        samples.addLast(Sample(atMs, kmh / KMH_PER_MPS))
        while (samples.isNotEmpty() && atMs - samples.first().atMs > KEEP_MS) samples.removeFirst()
    }

    /** At most how far the car went from [fromMs] to [toMs], metres. */
    fun maxDriven(fromMs: Long, toMs: Long): Double {
        if (toMs <= fromMs) return 0.0
        val near = samples.filter { it.atMs >= fromMs - HOLD_MS && it.atMs <= toMs + HOLD_MS }
        var metres = 0.0
        var t = fromMs
        var first = 0
        while (t < toMs) {
            val end = minOf(t + STEP_MS, toMs)
            while (first < near.size && near[first].atMs < t - HOLD_MS) first++
            var top = -1.0
            var i = first
            while (i < near.size && near[i].atMs <= end + HOLD_MS) {
                top = maxOf(top, near[i].mps)
                i++
            }
            metres += (if (top < 0) FALLBACK_MPS else top) * (end - t) / MS_PER_S
            t = end
        }
        return metres
    }

    /** The highest speed seen around [fromMs]..[toMs], m/s; null when nothing was seen. */
    fun maxSpeed(fromMs: Long, toMs: Long): Double? =
        samples.filter { it.atMs >= fromMs - HOLD_MS && it.atMs <= toMs + HOLD_MS }.maxOfOrNull { it.mps }

    /** The lowest speed over [fromMs]..[toMs], m/s, only when samples cover all of it; else null. */
    fun minSpeed(fromMs: Long, toMs: Long): Double? {
        val lastBefore = samples.lastOrNull { it.atMs <= fromMs } ?: return null
        if (fromMs - lastBefore.atMs > HOLD_MS) return null
        val span = listOf(lastBefore) + samples.filter { it.atMs > fromMs && it.atMs <= toMs }
        if (toMs - span.last().atMs > HOLD_MS) return null
        if (span.zipWithNext().any { (a, b) -> b.atMs - a.atMs > HOLD_MS }) return null
        return span.minOf { it.mps }
    }

    companion object {
        const val KMH_PER_MPS = 3.6
        /** A sample speaks for the time this close to it. */
        const val HOLD_MS = 15_000L
        /** Where no sample speaks: 200 km/h, so a missing speed never makes a fix implausible. */
        const val FALLBACK_MPS = 200 / KMH_PER_MPS
        private const val KEEP_MS = 20 * 60_000L
        private const val STEP_MS = 1_000L
        private const val MS_PER_S = 1000.0
    }
}
