package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** #8: speed and torque per axle must reach the dump verbatim, sentinels included. */
class MotorsDiagnosticsTest {

    @Test fun `rows are reads on the Engine, Gb and Power devices, the -10013 torques as floats`() {
        val items = MotorsDiagnostics.batchItems()
        assertEquals(10, items.size)
        assertEquals(listOf(5, 5, 7, 5, 7, 7, 7, 5, 5, 5), items.map { it.tx })
        assertEquals(setOf(1005, 1012, 1039), items.map { it.dev }.toSet())
    }

    @Test fun `front and rear speed and torque are printed with their address`() {
        val lines = MotorsDiagnostics.format(listOf(0 to 4200, 0 to 4100, 0 to java.lang.Float.floatToIntBits(35.5f), 65535 to 0) + List(6) { 0 to 0 })
        assertEquals("front_speed[dev=1012 fid=1141899272]=4200", lines[0])
        assertEquals("rear_speed[dev=1012 fid=621805576]=4100", lines[1])
        assertEquals("front_torque[dev=1012 fid=1141899288]=35.5", lines[2])
        assertEquals("rear_torque[dev=1012 fid=621805592]=(status=65535)", lines[3])
    }

    @Test fun `a missing reply is one line`() {
        assertEquals(listOf("(unavailable)"), MotorsDiagnostics.format(null))
    }
}
