package com.bydmate.app.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The jamming/spoofing guard's core (owner, 2026-11-04). Positions are synthetic, near Kyiv. */
class GnssPlausibilityTest {

    private val changes = mutableListOf<GnssTransition>()
    private val guard = GnssPlausibility { changes += it }

    /** A car going north: [northM] metres from the start point at [atMs]. */
    private fun fix(atMs: Long, northM: Double, eastM: Double = 0.0, acc: Float? = 5f, fixTimeMs: Long = BASE_TIME + atMs) =
        GnssFix(atMs, fixTimeMs, LAT + northM / M_PER_DEG_LAT, LON + eastM / M_PER_DEG_LON, acc)

    /** Drives at [kmh] from [fromMs] for [durationMs]: car speed every second, a fix every 2 s. */
    private fun drive(fromMs: Long, durationMs: Long, kmh: Int, startM: Double, noiseM: Double = 5.0): Double {
        val rnd = Random(fromMs)
        val mps = kmh / 3.6
        var t = fromMs
        while (t <= fromMs + durationMs) {
            guard.onCarSpeed(t, kmh)
            if ((t - fromMs) % 2_000 == 0L) {
                val noise = (rnd.nextDouble() * 2 - 1) * noiseM
                guard.onFix(fix(t, startM + mps * (t - fromMs) / 1000 + noise, noise))
            }
            t += 1_000
        }
        return startM + mps * durationMs / 1000
    }

    @Test fun `a normal drive stays plausible`() {
        var at = drive(0, 120_000, 50, 0.0)
        at = drive(121_000, 300_000, 130, at, noiseM = 10.0)
        drive(422_000, 60_000, 0, at, noiseM = 8.0)
        assertFalse(guard.suspect)
        assertTrue(changes.isEmpty())
    }

    @Test fun `a jamming jump pauses, with a reason and no coordinates`() {
        drive(0, 60_000, 90, 0.0)
        guard.onCarSpeed(61_000, 90)
        guard.onFix(fix(62_000, 30_000.0, 20_000.0))
        assertTrue(guard.suspect)
        val reason = changes.single().reason
        assertTrue(reason, reason.startsWith("jump "))
        assertFalse("no coordinates in the log", reason.contains("50.") || reason.contains("30."))
    }

    @Test fun `slow drift within the accuracy is not a jump`() {
        // Parked: the fix wanders 120 m over two minutes with a 20 m error radius.
        for (i in 0..60) {
            val t = i * 2_000L
            guard.onCarSpeed(t, 0)
            guard.onFix(fix(t, i * 2.0, acc = 20f))
        }
        assertFalse(guard.suspect)
    }

    @Test fun `stationary noise is not a jump`() {
        val rnd = Random(7)
        for (i in 0..120) {
            val t = i * 2_000L
            guard.onCarSpeed(t, 0)
            guard.onFix(fix(t, (rnd.nextDouble() * 2 - 1) * 60, (rnd.nextDouble() * 2 - 1) * 60, acc = 30f))
        }
        assertFalse(guard.suspect)
    }

    @Test fun `recovery waits for 30 s of plausible fixes`() {
        drive(0, 60_000, 90, 0.0)
        guard.onFix(fix(62_000, 40_000.0))
        assertTrue(guard.suspect)
        // The spoofed position moves on consistently from where it jumped to.
        drive(63_000, 28_000, 90, 40_000.0 + 25)
        assertTrue("29 s are not enough", guard.suspect)
        drive(93_000, 4_000, 90, 40_000.0 + 25 + 30 * 25)
        assertFalse(guard.suspect)
        assertEquals(listOf(true, false), changes.map { it.suspect })
        assertTrue(changes.last().reason, changes.last().reason.startsWith("plausible for 3"))
    }

    @Test fun `a new jump during the hold-off starts it over, and no fixes means no recovery`() {
        drive(0, 30_000, 90, 0.0)
        guard.onFix(fix(32_000, 40_000.0))
        drive(34_000, 20_000, 90, 40_000.0)
        guard.onFix(fix(56_000, 0.0)) // jumps back
        drive(58_000, 20_000, 90, 0.0)
        assertTrue("20 s after the second jump", guard.suspect)
        // Jammed: the car drives on, no fix comes.
        for (t in 80_000L..200_000L step 1_000) guard.onCarSpeed(t, 90)
        assertTrue(guard.suspect)
        assertEquals(1, changes.size)
    }

    @Test fun `a tunnel without fixes is not a jump`() {
        val at = drive(0, 60_000, 90, 0.0)
        for (t in 61_000L..120_000L step 1_000) guard.onCarSpeed(t, 90)
        guard.onFix(fix(122_000, at + 62 * 25.0))
        assertFalse(guard.suspect)
    }

    @Test fun `without the car's speed only a far jump counts`() {
        guard.onFix(fix(0, 0.0))
        guard.onFix(fix(2_000, 60.0))
        guard.onFix(fix(4_000, 120.0))
        assertFalse(guard.suspect)
        guard.onFix(fix(6_000, 25_000.0))
        assertTrue(guard.suspect)
    }

    @Test fun `fixes long apart are not compared`() {
        guard.onFix(fix(0, 0.0))
        guard.onFix(fix(11 * 60_000L, 50_000.0))
        assertFalse(guard.suspect)
    }

    @Test fun `an absurd accuracy or a fix clock jump is implausible`() {
        drive(0, 20_000, 50, 0.0)
        guard.onFix(fix(22_000, 300.0, acc = 1500f))
        assertEquals("accuracy 1500 m", changes.single().reason)

        val other = GnssPlausibility { changes += it }
        other.onFix(fix(0, 0.0))
        other.onFix(fix(2_000, 10.0, fixTimeMs = BASE_TIME + 2_000 + 3_600_000))
        assertTrue(other.suspect)
        assertTrue(changes.last().reason, changes.last().reason.startsWith("fix time jumped 3600 s"))
    }

    @Test fun `a position standing still while the car drives fast is implausible`() {
        drive(0, 30_000, 60, 0.0)
        val rnd = Random(3)
        for (t in 32_000L..50_000L step 2_000) {
            guard.onCarSpeed(t - 1_000, 60)
            guard.onCarSpeed(t, 60)
            guard.onFix(fix(t, 520 + rnd.nextDouble() * 9, rnd.nextDouble() * 9))
        }
        assertTrue(guard.suspect)
        assertTrue(changes.first().reason, changes.first().reason.startsWith("position stuck"))
    }

    @Test fun `Waze's camera distance receding while driving is implausible, a passed camera is not`() {
        for (t in 0L..20_000L step 1_000) guard.onCarSpeed(t, 90)
        listOf(2500, 2400, 1500, 600, 300, 20, 28_400).forEachIndexed { i, m -> guard.onWazeCamera(i * 1_000L, m) }
        assertFalse("the next camera after a passed one", guard.suspect)
        guard.onWazeCamera(8_000, 72_400)
        assertTrue(guard.suspect)
        assertEquals("Waze camera distance 28400 -> 72400 m", changes.single().reason)
    }

    @Test fun `camera reads with the car standing or long apart are not judged`() {
        guard.onWazeCamera(0, 1_000)
        guard.onWazeCamera(1_000, 9_000)
        for (t in 2_000L..4_000L step 1_000) guard.onCarSpeed(t, 0)
        guard.onWazeCamera(3_000, 1_000)
        guard.onWazeCamera(4_000, 9_000)
        for (t in 5_000L..40_000L step 1_000) guard.onCarSpeed(t, 80)
        guard.onWazeCamera(20_000, 1_000)
        guard.onWazeCamera(35_000, 9_000)
        assertFalse(guard.suspect)
    }

    @Test fun `Waze's distance to the destination jumping is implausible, a new route is not`() {
        for (t in 0L..30_000L step 1_000) guard.onCarSpeed(t, 100)
        guard.onWazeTotal(0, 42_000, newRoute = true)
        guard.onWazeTotal(10_000, 41_700, newRoute = false)
        guard.onWazeTotal(12_000, 90_000, newRoute = true)
        assertFalse(guard.suspect)
        guard.onWazeTotal(14_000, 60_000, newRoute = false)
        assertTrue(guard.suspect)
        assertEquals("Waze distance to destination 90000 -> 60000 m", changes.single().reason)
    }

    private companion object {
        const val LAT = 50.45
        const val LON = 30.52
        const val M_PER_DEG_LAT = 111_195.0
        val M_PER_DEG_LON = 111_195.0 * kotlin.math.cos(Math.toRadians(LAT))
        const val BASE_TIME = 1_793_000_000_000L
    }
}
