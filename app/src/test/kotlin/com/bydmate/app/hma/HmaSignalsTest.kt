package com.bydmate.app.hma

import com.bydmate.app.data.nativestack.FidCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The signal table: read-only symbols, Tang L numbers, and how a catalog confirms them. */
class HmaSignalsTest {

    @Test fun `no signal is a write address`() {
        HmaSignals.ALL.forEach { signal ->
            assertFalse(signal.symbol, signal.symbol == "Adas.ADAS_HMA_STATE_SET")
            assertFalse(signal.symbol, signal.symbol.contains("HEADLIGHT_CONTROL_SET"))
            assertFalse(signal.symbol, signal.fid == 944767008 || signal.fid == 1276153912)
        }
    }

    @Test fun `keys, symbols and fids are unique`() {
        assertEquals(HmaSignals.ALL.size, HmaSignals.ALL.map { it.key }.toSet().size)
        assertEquals(HmaSignals.ALL.size, HmaSignals.ALL.map { it.symbol }.toSet().size)
        assertEquals(HmaSignals.ALL.size, HmaSignals.ALL.map { it.fid }.toSet().size)
    }

    @Test fun `the HMA state and its HAL copy are the first two, so a tight cap keeps them`() {
        assertEquals(listOf(HmaSignals.HMA_STATE, HmaSignals.HMA_STATE_HAL), HmaSignals.ALL.take(2).map { it.key })
        val two = HmaSignals.confirmed(tangL()::fidOf, emptySet(), room = 2)
        assertEquals(listOf(828375048, 725614600), two.map { it.fid })
    }

    @Test fun `every signal carries the Tang L catalog number`() {
        val dump = listOf(File("../docs/investigations/tang-l-fid-dump-20261024.txt"),
            File("docs/investigations/tang-l-fid-dump-20261024.txt")).firstOrNull { it.isFile }
        assumeTrue("Tang L fid dump not in this checkout", dump != null)
        val catalog = FidCatalog.parse(dump!!.readText())
        HmaSignals.ALL.forEach { assertEquals(it.symbol, it.fid, catalog.fidOf(it.symbol)) }
    }

    @Test fun `a catalog confirms its own numbers`() {
        val moved = HmaSignals.ALL.associate { it.symbol to it.fid + 1 }
        val got = HmaSignals.confirmed({ moved[it] }, emptySet(), room = 100)
        assertEquals(HmaSignals.ALL.map { it.fid + 1 }, got.map { it.fid })
        assertEquals(HmaSignals.ALL, got.map { it.signal })
    }

    @Test fun `a symbol this catalog lacks is skipped`() {
        val only = mapOf("Adas.ADAS_HMA_STATE" to 828375048)
        val got = HmaSignals.confirmed({ only[it] }, emptySet(), room = 100)
        assertEquals(listOf(HmaSignals.HMA_STATE), got.map { it.signal.key })
    }

    @Test fun `a number someone else already holds is left to them`() {
        val got = HmaSignals.confirmed(tangL()::fidOf, setOf(828375048), room = 100)
        assertTrue(got.none { it.fid == 828375048 })
        assertEquals(HmaSignals.ALL.size - 1, got.size)
    }

    @Test fun `no room, no signal`() {
        assertEquals(emptyList<HmaSubscription>(), HmaSignals.confirmed(tangL()::fidOf, emptySet(), room = 0))
    }

    private fun tangL() = FidCatalog(HmaSignals.ALL.associate { it.symbol to it.fid }, emptyMap())
}
