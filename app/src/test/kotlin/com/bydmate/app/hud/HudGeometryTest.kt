package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guide line / guide point for the AR-HUD road-info frame (f30/f31). These reach the HUD
 * service only; BYDMate sends nothing to the services the driving computer consumes.
 */
class HudGeometryTest {

    @Test fun `guide line is ten points along the heading`() {
        val line = HudGeometry.guideLine(11, 50.0, 30.0, 0.0)
        assertEquals(10, line.count { it == '[' } - 1)
        assertTrue(line.startsWith("[[30.0,50.0,0],"))
        assertTrue(line.endsWith("]"))
    }

    @Test fun `turns bend the line and straight does not`() {
        val straight = HudGeometry.guideLine(11, 50.0, 30.0, 0.0)
        val left = HudGeometry.guideLine(1, 50.0, 30.0, 0.0)
        val right = HudGeometry.guideLine(2, 50.0, 30.0, 0.0)
        assertNotEquals(straight, left)
        assertNotEquals(straight, right)
        assertNotEquals(left, right)
    }

    @Test fun `guide point projects ahead and defaults a zero distance`() {
        assertEquals("30.0,50.00045045045045,0", HudGeometry.guidePoint(0, 11, 50.0, 30.0, 0.0))
        val far = HudGeometry.guidePoint(500, 11, 50.0, 30.0, 0.0)
        assertTrue(far.endsWith(",0"))
        assertNotEquals(HudGeometry.guidePoint(0, 11, 50.0, 30.0, 0.0), far)
    }

    @Test fun `guide point bearing follows the donor table`() {
        assertEquals(-14.25, HudGeometry.guidePointBearing(0.0, 1), 1e-9)
        assertEquals(45.0, HudGeometry.guidePointBearing(0.0, 2), 1e-9)
        assertEquals(45.0, HudGeometry.guidePointBearing(0.0, 9), 1e-9)
        assertEquals(0.0, HudGeometry.guidePointBearing(0.0, 11), 1e-9)
    }
}
