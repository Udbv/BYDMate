package com.bydmate.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who knew the limit first over a drive: Waze, or the car's camera. */
class AdasLimitEpisodesTest {

    private val lines = mutableListOf<String>()
    private val e = AdasLimitEpisodes { lines += it }
    private var t = 0L

    /** [seconds] of readings every 2 s at [kph]. */
    private fun drive(seconds: Int, kph: Int, waze: Int?, car: Int?) {
        repeat(seconds / 2) {
            e.add(t, kph, waze, car)
            t += 2_000
        }
    }

    @Test fun `leaving a town Waze knows first and the camera catches up`() {
        drive(20, 50, 50, 50)
        drive(60, 72, 90, 50)          // past the town board: Waze 90, the camera still on 50
        drive(20, 90, 90, 90)
        e.finish(t)
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].startsWith("limit episode wazeFirst waze=90 car=50 lasted 1.2km"))
        val s = e.summary()
        assertTrue(s, s.contains("wazeFirst=1(1.2km,60s)"))
        assertTrue(s, s.contains("differ=1.2"))
    }

    @Test fun `a sign Waze does not know yet is the camera first`() {
        drive(10, 90, 90, 90)
        drive(10, 70, 90, 70)          // works: a temporary 70 Waze lacks
        drive(10, 70, 70, 70)
        e.finish(t)
        assertTrue(e.summary(), e.summary().contains("carFirst=1"))
    }

    @Test fun `an episode ends unresolved when one side goes silent or the drive ends`() {
        drive(10, 50, 50, 50)
        drive(10, 50, 90, 50)
        drive(10, 50, 90, null)
        drive(10, 50, 50, 50)
        drive(10, 50, 70, 50)
        e.finish(t)
        val s = e.summary()
        assertTrue(s, s.contains("waze>lost=2"))
        assertTrue(s, s.contains("wazeOnly="))
    }

    @Test fun `standing still adds no distance`() {
        drive(30, 0, 90, 50)
        e.finish(t)
        assertTrue(e.summary(), e.summary().startsWith("km agree=0.0 differ=0.0"))
    }
}
