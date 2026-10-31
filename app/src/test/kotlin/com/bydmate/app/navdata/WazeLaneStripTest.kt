package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane strip geometry from the 2026-10-25 drive: dividers are not lanes, a sliding strip is not read. */
class WazeLaneStripTest {

    private fun rect(s: String) = s.split(",").map { it.toInt() }.let { NavLaneState.LaneRect(it[0], it[1], it[2], it[3]) }

    private fun cells(vararg r: String) = r.map { WazeLaneReader.Cell(rect(it), null, false) }

    @Test fun `the 1-px dividers between lanes are dropped`() {
        // count=7 on the car: four 78-px lanes and three dividers.
        val raw = cells(
            "80,84,158,158", "239,132,240,158", "321,84,399,158", "480,132,481,158",
            "562,84,640,158", "721,132,722,158", "803,84,881,158",
        )
        assertEquals(listOf(80, 321, 562, 803), WazeLaneReader.lanesOnly(raw).map { it.left })
    }

    @Test fun `a single lane is kept`() {
        assertEquals(1, WazeLaneReader.lanesOnly(cells("120,84,198,162")).size)
    }

    @Test fun `cells reaching past a sliding container are not settled`() {
        val container = rect("0,84,960,113")
        val lanes = listOf(rect("39,86,117,164"), rect("200,86,278,164"))
        assertFalse(WazeVisualManeuverReader.treeCellsSettled(container, lanes))
    }

    @Test fun `cut-short cells are not settled, full square ones are`() {
        val container = rect("0,84,960,180")
        assertFalse(WazeVisualManeuverReader.treeCellsSettled(container, listOf(rect("80,84,158,104"))))
        assertTrue(WazeVisualManeuverReader.treeCellsSettled(container, listOf(rect("39,93,117,171"))))
    }
}
