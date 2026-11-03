package com.bydmate.app.helper.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed bump candidates from the body pitch rate (#22). */
class BumpDetectorTest {

    /** Pitch samples every 30 ms from [values], starting at [t0]; the verdicts that came out. */
    private fun BumpDetector.feed(t0: Long, values: List<Double>): List<BumpDetector.Verdict> =
        values.mapIndexedNotNull { i, v -> pitch(t0 + i * 30L, v) }

    private val roughRoad = List(40) { i -> if (i % 4 < 2) 5.0 else -5.0 }
    private val bump = listOf(0.0, 4.0, 9.0, 12.0, 6.0, -4.0, -11.0, -13.0, -6.0, 0.0)

    @Test fun `ordinary roughness is not a bump`() {
        val d = BumpDetector().apply { speed(50.0) }
        assertTrue(d.feed(0, roughRoad).isEmpty())
    }

    @Test fun `a hard double swing is offered once, the rear axle's hit included`() {
        val d = BumpDetector().apply { speed(40.0) }
        val out = d.feed(0, bump + bump).filterIsInstance<BumpDetector.Verdict.Candidate>()
        assertEquals(1, out.size)
        val c = out.single()
        assertTrue(c.bump.peakToPeak >= BumpDetector.PROMPT_PP)
        assertEquals(40.0, c.bump.speedKph, 0.0)
    }

    @Test fun `a smaller swing is only noted`() {
        val d = BumpDetector().apply { speed(40.0) }
        val out = d.feed(0, bump.map { it * 0.7 })
        assertTrue(out.single() is BumpDetector.Verdict.Note)
    }

    @Test fun `standing, fast or braking hard is never a bump`() {
        assertNull(BumpDetector().feed(0, bump).firstOrNull())
        assertTrue(BumpDetector().apply { speed(2.0) }.feed(0, bump).isEmpty())
        assertTrue(BumpDetector().apply { speed(90.0) }.feed(0, bump).isEmpty())
        assertTrue(BumpDetector().apply { speed(40.0); brake(50.0) }.feed(0, bump).isEmpty())
    }

    @Test fun `the next bump after the cooldown is offered again`() {
        val d = BumpDetector().apply { speed(30.0) }
        assertEquals(1, d.feed(0, bump).filterIsInstance<BumpDetector.Verdict.Candidate>().size)
        assertEquals(1, d.feed(BumpDetector.COOLDOWN_MS + 1_000, bump).filterIsInstance<BumpDetector.Verdict.Candidate>().size)
    }
}
