package com.bydmate.app.helper.push

import org.junit.Assert.assertEquals
import org.junit.Test

/** The swinging door must keep every point a straight-line reading of the file would miss. */
class LineCompressorTest {

    private fun run(tolerance: Double, samples: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
        val c = LineCompressor<Pair<Long, Double>>(tolerance)
        val out = samples.mapNotNull { (t, v) -> c.offer(t, v, t to v) }.toMutableList()
        c.flush()?.let { out += it }
        return out
    }

    @Test fun `a straight ramp keeps only its ends`() {
        val ramp = (0..20).map { it * 50L to it * 0.5 }
        assertEquals(listOf(0L to 0.0, 1000L to 10.0), run(0.01, ramp))
    }

    @Test fun `a corner keeps the point where the slope changes`() {
        val up = (0..10).map { it * 10L to it.toDouble() }
        val flat = (11..20).map { it * 10L to 10.0 }
        assertEquals(listOf(0L to 0.0, 100L to 10.0, 200L to 10.0), run(0.01, up + flat))
    }

    @Test fun `noise inside the tolerance is dropped and a spike is kept`() {
        val noisy = listOf(0L to 0.0, 10L to 0.02, 20L to -0.02, 30L to 0.01, 40L to 1.0, 50L to 0.0, 60L to 0.01)
        assertEquals(listOf(0L to 0.0, 30L to 0.01, 40L to 1.0, 50L to 0.0, 60L to 0.01), run(0.05, noisy))
    }

    @Test fun `every rebuilt point stays within the tolerance`() {
        val tol = 0.1
        val samples = (0..500).map { it * 20L to kotlin.math.sin(it / 15.0) * 3 + (it % 7) * 0.01 }
        val kept = run(tol, samples)
        for ((t, v) in samples) {
            val i = kept.indexOfLast { it.first <= t }
            val (t0, v0) = kept[i]
            val rebuilt = if (i == kept.lastIndex) v0 else {
                val (t1, v1) = kept[i + 1]
                v0 + (v1 - v0) * (t - t0) / (t1 - t0)
            }
            assert(kotlin.math.abs(rebuilt - v) <= tol + 1e-9) { "t=$t v=$v rebuilt=$rebuilt" }
        }
        assert(kept.size < samples.size / 3) { "kept ${kept.size} of ${samples.size}" }
    }
}
