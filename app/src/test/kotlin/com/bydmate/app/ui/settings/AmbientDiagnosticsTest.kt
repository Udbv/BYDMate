package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** #4: the ambient section must show what the Tang L reports, sentinels included. */
class AmbientDiagnosticsTest {

    @Test fun `every row is a tx 5 read on the Light or Setting device`() {
        val items = AmbientDiagnostics.batchItems()
        assertEquals(9, items.size)
        assertEquals(setOf(5), items.map { it.tx }.toSet())
        assertEquals(setOf(1004, 1023), items.map { it.dev }.toSet())
    }

    @Test fun `values and sentinels are printed verbatim with their address`() {
        val readings = MutableList(9) { 0 to 1 }
        readings[1] = 0 to 4
        readings[5] = -10011 to 0
        val lines = AmbientDiagnostics.format(readings)
        assertEquals("interior_brightness[dev=1023 fid=522414]=4", lines[1])
        assertEquals("main_switch[dev=1004 fid=1060110406]=(status=-10011)", lines[5])
    }

    @Test fun `a missing or short reply is one line`() {
        assertEquals(listOf("(unavailable)"), AmbientDiagnostics.format(null))
        assertEquals(listOf("(unavailable)"), AmbientDiagnostics.format(listOf(0 to 1)))
    }
}
