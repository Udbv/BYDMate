package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** One-pedal research: the pedal and regen settings must reach the dump verbatim, sentinels included. */
class PedalDiagnosticsTest {

    @Test fun `rows are int reads on the Energy, Engine, Setting and Adas devices`() {
        val items = PedalDiagnostics.batchItems()
        assertEquals(22, items.size)
        assertEquals(setOf(5), items.map { it.tx }.toSet())
        assertEquals(setOf(1006, 1012, 1023, 1038), items.map { it.dev }.toSet())
    }

    @Test fun `e-pedal config and state are printed with their address`() {
        val lines = PedalDiagnostics.format(listOf(0 to 0, -10011 to 0) + List(20) { 0 to 1 })
        assertEquals("e_pedal_config[dev=1023 fid=888143928]=0", lines[0])
        assertEquals("e_pedal_state[dev=1023 fid=888143930]=(status=-10011)", lines[1])
        assertEquals("avh_state[dev=1038 fid=304087110]=1", lines.last())
    }

    @Test fun `a missing reply is one line`() {
        assertEquals(listOf("(unavailable)"), PedalDiagnostics.format(null))
    }
}
