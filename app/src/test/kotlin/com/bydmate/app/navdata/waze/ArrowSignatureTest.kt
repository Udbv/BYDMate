package com.bydmate.app.navdata.waze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The port is only worth anything if it reproduces openbyd's arithmetic exactly, so these tests
 * drive the real reference data: each of the 45 signatures is painted back into pixels and has to
 * come out of the classifier as itself.
 */
class ArrowSignatureTest {

    private val table = WazeArrowTable.entries

    @Test fun `the reference table is the donor's 45 entries with distinct shapes`() {
        assertEquals(45, table.size)
        assertEquals(35, table.values.toSet().size)
        assertTrue(table.keys.all { it.length == BitGrid225.SIZE })
    }

    @Test fun `every reference signature is recovered exactly from a 15 by 15 render`() {
        for ((grid, name) in table) {
            val bits = grid.asString()
            val signature = ArrowSignature.compute(15, 15, ArrowFixtures.render(bits, 15), 0.8f, true)
            assertEquals("signature for $name", bits, signature?.asString())
            assertEquals("match for $name", name, WazeArrowTable.match(signature!!)?.name)
            assertEquals("hamming for $name", 0, WazeArrowTable.match(signature)?.hamming)
        }
    }

    @Test fun `every reference signature is recovered from a full-size 183 by 183 arrow`() {
        // 183 px is the arrow's real size on the DiLink route bar, and 183 does not divide by 15:
        // cells are 12 or 13 px wide, which is exactly the integer boundary rule under test.
        for ((grid, name) in table) {
            val bits = grid.asString()
            val signature = ArrowSignature.compute(183, 183, ArrowFixtures.render(bits, 183), 0.8f, true)
            assertEquals("signature for $name", bits, signature?.asString())
            assertEquals("match for $name", name, WazeArrowTable.match(signature!!)?.name)
        }
    }

    @Test fun `an arrow cropped two cells off centre still matches`() {
        for ((grid, name) in table) {
            val bits = grid.asString()
            val shifted = ArrowSignature.compute(
                183,
                183,
                ArrowFixtures.render(bits, 183, shiftCellsX = 2, shiftCellsY = 2),
                0.8f,
                true,
            )
            val match = WazeArrowTable.match(shifted!!)
            assertNotNull("shifted $name should still find a shape", match)
            assertTrue("shifted $name within tolerance", match!!.hamming <= 18)
        }
    }

    @Test fun `a distinctive arrow keeps its own name when shifted`() {
        val forward = table.entries.first { it.value == "big_trans_direction_forward" }.key.asString()
        for (dx in -2..2) {
            for (dy in -2..2) {
                val signature = ArrowSignature.compute(
                    183,
                    183,
                    ArrowFixtures.render(forward, 183, dx, dy),
                    0.8f,
                    true,
                )
                assertEquals(
                    "shift $dx,$dy",
                    "big_trans_direction_forward",
                    WazeArrowTable.match(signature!!)?.name,
                )
            }
        }
    }

    @Test fun `a blank crop has no shape at all`() {
        val blank = IntArray(183 * 183) { ArrowFixtures.PAPER }

        assertNull(ArrowSignature.compute(183, 183, blank, 0.8f, true))
    }

    @Test fun `contrast below thirty levels is refused rather than guessed at`() {
        // Two greys 20 levels apart: a real arrow spans far more than that, and anything this flat
        // is a blank panel, a loading state or a screenshot taken mid-fade.
        val faint = IntArray(183 * 183) { index ->
            if ((index / 183) % 3 == 0) 0xFF808080.toInt() else 0xFF949494.toInt()
        }

        assertNull(ArrowSignature.compute(183, 183, faint, 0.8f, true))
    }

    @Test fun `an unrelated shape matches nothing`() {
        // Half the crop filled solid: over a hundred set cells, where the sparsest arrow has 11 and
        // the densest 64. Nothing in the table is within reach even after the shifted search.
        val half = IntArray(183 * 183) { index ->
            if (index / 183 < 91) ArrowFixtures.INK else ArrowFixtures.PAPER
        }

        val signature = ArrowSignature.compute(183, 183, half, 0.8f, true)

        assertNull(WazeArrowTable.match(signature!!))
    }

    @Test fun `drawable names map to the donor's panel glyphs`() {
        assertEquals(1, WazeArrowTable.iconFor("big_trans_direction_left"))
        assertEquals(2, WazeArrowTable.iconFor("big_trans_direction_right"))
        assertEquals(3, WazeArrowTable.iconFor("big_trans_direction_exit_left"))
        assertEquals(5, WazeArrowTable.iconFor("direction_exit_right"))
        assertEquals(9, WazeArrowTable.iconFor("big_trans_direction_u_turn"))
        assertEquals(10, WazeArrowTable.iconFor("big_trans_direction_u_turn_lhs"))
        assertEquals(10, WazeArrowTable.iconFor("car_dark_big_direction_u_turn_uk"))
        assertEquals(11, WazeArrowTable.iconFor("big_trans_direction_forward"))
        assertEquals(11, WazeArrowTable.iconFor("big_trans_direction_t_junction"))
        assertEquals(15, WazeArrowTable.iconFor("big_trans_directions_roundabout_l"))
        assertEquals(16, WazeArrowTable.iconFor("big_trans_directions_roundabout_r_lhs"))
        assertEquals(17, WazeArrowTable.iconFor("big_trans_directions_roundabout_l_lhs"))
        assertEquals(17, WazeArrowTable.iconFor("car_dark_big_directions_roundabout_r_uk"))
        assertEquals(18, WazeArrowTable.iconFor("big_trans_directions_roundabout_r"))
        assertEquals(19, WazeArrowTable.iconFor("big_trans_directions_roundabout_s_lhs"))
        assertEquals(20, WazeArrowTable.iconFor("big_trans_directions_roundabout_s"))
        assertEquals(21, WazeArrowTable.iconFor("car_dark_big_directions_roundabout_u_uk"))
        assertEquals(22, WazeArrowTable.iconFor("big_trans_directions_roundabout_u"))
        assertEquals(25, WazeArrowTable.iconFor("big_trans_directions_roundabout"))
        assertEquals(35, WazeArrowTable.iconFor("car_dark_big_directions_roundabout_uk"))
        assertEquals(45, WazeArrowTable.iconFor("big_trans_direction_stop"))
        assertEquals(48, WazeArrowTable.iconFor("big_trans_direction_end"))
        assertEquals(11, WazeArrowTable.iconFor("something_waze_added_later"))
    }

    @Test fun `drawable names map to the donor's lane glyphs`() {
        assertEquals(1, WazeArrowTable.laneCodeFor("big_trans_direction_left"))
        assertEquals(1, WazeArrowTable.laneCodeFor("big_keep_left"))
        assertEquals(2, WazeArrowTable.laneCodeFor("big_trans_direction_straight_left_exit"))
        assertEquals(3, WazeArrowTable.laneCodeFor("big_trans_direction_right"))
        assertEquals(4, WazeArrowTable.laneCodeFor("big_trans_direction_straight_right_exit"))
        assertEquals(5, WazeArrowTable.laneCodeFor("big_trans_direction_u_turn"))
        assertEquals(6, WazeArrowTable.laneCodeFor("big_trans_direction_t_junction"))
        assertEquals(8, WazeArrowTable.laneCodeFor("car_dark_big_direction_u_turn_uk"))
        assertEquals(0, WazeArrowTable.laneCodeFor("big_trans_direction_forward"))
        assertEquals(0, WazeArrowTable.laneCodeFor("big_trans_directions_roundabout"))
    }

    @Test fun `a bit grid packs high end first and measures distance by differing cells`() {
        val a = BitGrid225.parse("1".padEnd(BitGrid225.SIZE, '0'))
        val b = BitGrid225.parse("0".padEnd(BitGrid225.SIZE, '0'))

        assertTrue(a[0])
        assertTrue(!b[0])
        assertEquals(1, a.hamming(b))
        assertEquals(0, a.hamming(a))
        assertEquals(a, BitGrid225.parse(a.asString()))
        assertEquals(a.hashCode(), BitGrid225.parse(a.asString()).hashCode())
    }
}
