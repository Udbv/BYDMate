package com.bydmate.app.hma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The journal line: one per change, a fixed greppable shape, the context, never a position. */
class HmaJournalTest {

    private val night = HmaReading(
        hmaState = 1, hmaStateHal = 1, gray = 0, highBeam = 0, indicator = 0, knob = 2,
        lightLevel = 1, lightIntensity = 120.0, rainFall = 0.0, wiperRelay = 0, wiperLevel = 0,
        autoWipers = 1, glassHumidity = 55.0, glassTemp = 3.5, speedKph = 72,
    )

    @Test fun `the line has the tag word, the event and the whole context in a fixed order`() {
        assertEquals(
            "HMA state PASSIVE->ACTIVE | hma=PASSIVE hal=1 plain=1 gray=0 beam=0 ind=0 knob=2 light=1 " +
                "lux=120 rain=0 wiper=0/0 auto=1 glass=55/3.5 err=- v=72",
            HmaJournal.line("state PASSIVE->ACTIVE", night),
        )
    }

    @Test fun `unknown values are dashes, so an early line still parses`() {
        assertEquals(
            "HMA beam -->1 | hma=- hal=- plain=- gray=- beam=- ind=- knob=- light=- lux=- rain=- " +
                "wiper=-/- auto=- glass=-/- err=- v=-",
            HmaJournal.line("beam -->1", HmaReading()),
        )
    }

    @Test fun `no line ever carries a position`() {
        val line = HmaJournal.line("state PASSIVE->ACTIVE", night)
        listOf("lat", "lon", "location", "gps").forEach { assertFalse(it, line.contains(it, ignoreCase = true)) }
    }

    @Test fun `each tracked change is one event`() {
        val next = night.copy(hmaStateHal = 5, highBeam = 1, gray = 1, clusterFault = 1, knob = 4, rainError = 1)
        assertEquals(
            listOf(
                "state PASSIVE->CAMERA_BLOCKED",
                "beam 0->1",
                "gray 0->1",
                "cluster-fault -->1",
                "knob 2->4",
                "sensor-error -->rain",
            ),
            HmaJournal.changes(night, next),
        )
    }

    @Test fun `context fields alone are not events`() {
        val next = night.copy(lightLevel = 5, lightIntensity = 9000.0, speedKph = 30, wiperRelay = 1, glassHumidity = 90.0)
        assertEquals(emptyList<String>(), HmaJournal.changes(night, next))
    }

    @Test fun `the plain copy changing under an unchanged HAL copy is no state event`() {
        assertEquals(emptyList<String>(), HmaJournal.changes(night, night.copy(hmaState = 3)))
    }

    @Test fun `the line is greppable by its tag`() {
        assertEquals("HmaWatch", HmaJournal.TAG)
        assertTrue(HmaJournal.line("x", night).startsWith("HMA "))
    }
}
