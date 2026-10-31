package com.bydmate.app.data.automation

import com.bydmate.app.data.vehicle.WriteAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #3: the timer/hand split, replayed on the Tang L recording of 2026-10-25 (driver seat). Mode
 * 1 = off, 2..6 = programs; level 0 = off, 1..3.
 */
class MassageKeeperTest {

    private fun at(hms: String): Long {
        val (h, m, s) = hms.split(':').map(String::toInt)
        return ((h * 60L + m) * 60L + s) * 1000L
    }

    /** Feeds (time, mode, level) reads in order and returns every restore asked for. */
    private fun replay(vararg reads: Triple<String, Int, Int>): List<Pair<String, Pair<Int, Int>>> {
        var state = MassageSeatState()
        val restores = mutableListOf<Pair<String, Pair<Int, Int>>>()
        for ((time, mode, level) in reads) {
            val step = massageStep(state, mode, level, at(time))
            state = step.state
            step.restore?.let { restores += time to it }
        }
        return restores
    }

    @Test fun `the 15-minute timer counted from the last change brings the same mode and level back`() {
        // 11:52 on and adjusted to wave/3 at 11:52:15; the timer ended it at 12:07:13.
        val restores = replay(
            Triple("11:52:00", 5, 2),
            Triple("11:52:04", 5, 3),
            Triple("11:52:15", 6, 3),
            Triple("12:00:00", 6, 3),
            Triple("12:07:15", 1, 0),
        )
        assertEquals(listOf("12:07:15" to (6 to 3)), restores)
    }

    @Test fun `a hand switching it off a few seconds after turning it on is left alone`() {
        // 14:05:20 on, adjusted, 14:05:25 off by hand.
        val restores = replay(
            Triple("14:05:20", 6, 3),
            Triple("14:05:23", 6, 3),
            Triple("14:05:25", 1, 0),
            Triple("14:30:00", 1, 0),
        )
        assertEquals(emptyList<Pair<String, Pair<Int, Int>>>(), restores)
    }

    @Test fun `a restored massage runs another full cycle and is restored again`() {
        val restores = replay(
            Triple("13:35:56", 6, 3),
            Triple("13:50:56", 1, 0),   // timer -> restore
            Triple("13:51:01", 6, 3),   // back on: the timer starts over
            Triple("14:00:00", 1, 0),   // 9 min later: the driver, not the timer
        )
        assertEquals(listOf("13:50:56" to (6 to 3)), restores)
    }

    @Test fun `a restore that does not take is retried, then given up`() {
        val restores = replay(
            Triple("12:18:46", 6, 3),
            Triple("12:33:46", 1, 0),
            Triple("12:33:51", 1, 0),
            Triple("12:33:56", 1, 0),
            Triple("12:34:01", 1, 0),
            Triple("12:34:06", 1, 0),
        )
        assertEquals(MassageKeeper.MAX_ATTEMPTS, restores.size)
    }

    @Test fun `a failed read changes nothing`() {
        val on = massageStep(MassageSeatState(), 6, 3, at("12:00:00")).state
        val step = massageStep(on, null, 3, at("12:20:00"))
        assertEquals(on, step.state)
        assertNull(step.restore)
    }

    @Test fun `only the program and intensity ranges the keeper writes are allowed, off never`() {
        val allowlist = WriteAllowlist.loadProduction { "{}" }
        listOf("driver", "passenger").forEach { seat ->
            val mode = allowlist.find("massage_mode_$seat")!!
            val level = allowlist.find("massage_level_$seat")!!
            assertEquals(2 to 6, mode.valueMin to mode.valueMax)
            assertEquals(1 to 3, level.valueMin to level.valueMax)
            assertFalse(WriteAllowlist.isBanned(mode.dev, mode.writeFid))
            assertFalse(WriteAllowlist.isBanned(level.dev, level.writeFid))
        }
    }

    @Test fun `the restore command resolves to mode then intensity on that seat`() {
        val cmd = MassageKeeper.massageCommand(driver = false, mode = 6, level = 3)
        assertEquals(
            listOf(
                com.bydmate.app.data.vehicle.CommandTranslator.Resolved("massage_mode_passenger", 6),
                com.bydmate.app.data.vehicle.CommandTranslator.Resolved("massage_level_passenger", 3),
            ),
            com.bydmate.app.data.vehicle.CommandTranslator.resolve(cmd),
        )
        // Off (mode 1) and out-of-range intensities are not commands at all.
        assertEquals(emptyList<Any>(), com.bydmate.app.data.vehicle.CommandTranslator.resolve("主驾按摩模式1强度0"))
    }
}
