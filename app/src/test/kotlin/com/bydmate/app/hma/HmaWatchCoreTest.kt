package com.bydmate.app.hma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch end to end without Android: pushes and ticks in, journal lines and warnings out. */
class HmaWatchCoreTest {

    private val core = HmaWatchCore(gate = HmaWarningGate(holdMs = 10_000, gapMs = 5_000))

    private fun tick(
        now: Long,
        carOn: Boolean = true,
        trip: Long? = 1L,
        speed: Int? = 60,
        light: Int? = 1,
        beam: Int? = 0,
        warn: Boolean = true,
    ) = core.onTick(carOn, trip, beam, light, 0, 1, speed, warn, now)

    @Test fun `a state change is one journal line, a repeat is none`() {
        tick(0)
        val first = core.onSignal(HmaSignals.HMA_STATE_HAL, 1, 0.0, 100)
        val repeat = core.onSignal(HmaSignals.HMA_STATE_HAL, 1, 0.0, 200)
        val change = core.onSignal(HmaSignals.HMA_STATE_HAL, 3, 0.0, 300)

        assertEquals(1, first.lines.size)
        assertTrue(first.lines.single().startsWith("HMA state -->PASSIVE | "))
        assertEquals(emptyList<String>(), repeat.lines)
        assertTrue(change.lines.single().startsWith("HMA state PASSIVE->ACTIVE | "))
        assertTrue(change.lines.single().endsWith(" v=60"))
    }

    @Test fun `a pushed high beam flash and the tick that confirms it make two lines, not four`() {
        tick(0, beam = 0)
        val on = core.onSignal(HmaReading.HIGH_BEAM_FIELD, 1, 0.0, 100)
        val tickOn = tick(200, beam = 1)
        val off = core.onSignal(HmaReading.HIGH_BEAM_FIELD, 0, 0.0, 300)
        val tickOff = tick(400, beam = 0)

        assertTrue(on.lines.single().startsWith("HMA beam 0->1"))
        assertEquals(emptyList<String>(), tickOn.lines)
        assertTrue(off.lines.single().startsWith("HMA beam 1->0"))
        assertEquals(emptyList<String>(), tickOff.lines)
    }

    @Test fun `a blocked camera held while driving is warned once`() {
        tick(0)
        core.onSignal(HmaSignals.HMA_STATE_HAL, 5, 0.0, 0)
        assertEquals(emptySet<HmaReason>(), tick(1_000).warn)
        assertEquals(setOf(HmaReason.CAMERA_BLOCKED), tick(11_000).warn)
        assertEquals(emptySet<HmaReason>(), tick(60_000).warn)
    }

    @Test fun `the next trip may warn again`() {
        core.onSignal(HmaSignals.HMA_STATE_HAL, 5, 0.0, 0)
        tick(0, trip = 1L)
        assertEquals(setOf(HmaReason.CAMERA_BLOCKED), tick(10_000, trip = 1L).warn)
        tick(20_000, trip = 2L)
        assertEquals(setOf(HmaReason.CAMERA_BLOCKED), tick(30_000, trip = 2L).warn)
    }

    @Test fun `with the switch off the journal runs and nothing is warned`() {
        tick(0, warn = false)
        val line = core.onSignal(HmaSignals.HMA_STATE_HAL, 5, 0.0, 0)
        assertEquals(1, line.lines.size)
        assertEquals(emptySet<HmaReason>(), tick(11_000, warn = false).warn)
        assertEquals(emptySet<HmaReason>(), tick(30_000, warn = false).warn)
    }

    @Test fun `with the car off nothing is warned`() {
        core.onSignal(HmaSignals.HMA_STATE_HAL, 5, 0.0, 0)
        tick(0, carOn = false)
        assertEquals(emptySet<HmaReason>(), tick(20_000, carOn = false).warn)
    }

    @Test fun `a flapping signal is capped per minute and the cut is reported`() {
        val capped = HmaWatchCore(maxLinesPerMinute = 3)
        val written = (0 until 10).flatMap { i ->
            capped.onSignal(HmaReading.HIGH_BEAM_FIELD, i % 2, 0.0, i * 100L).lines
        }
        assertEquals(3, written.size)
        // The last flap left the lamp on; the next change opens a new minute.
        val later = capped.onSignal(HmaReading.HIGH_BEAM_FIELD, 0, 0.0, 70_000)
        assertEquals("HMA journal suppressed=7 lines in the last minute", later.lines.first())
        assertTrue(later.lines[1].startsWith("HMA beam 1->0"))
    }
}
