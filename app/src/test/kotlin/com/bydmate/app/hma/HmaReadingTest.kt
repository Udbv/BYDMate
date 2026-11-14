package com.bydmate.app.hma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HMA state values as the BYD SDK defines them, and how a pushed value lands in a reading. */
class HmaReadingTest {

    @Test fun `the six SDK states parse by their code`() {
        assertEquals(
            listOf(HmaMode.OFF, HmaMode.PASSIVE, HmaMode.STANDBY, HmaMode.ACTIVE, HmaMode.FAULT, HmaMode.CAMERA_BLOCKED),
            (0..5).map { HmaMode.of(it) },
        )
        assertNull(HmaMode.of(6))
        assertNull(HmaMode.of(null))
    }

    @Test fun `only OFF counts as switched off`() {
        assertFalse(HmaMode.OFF.enabled)
        HmaMode.entries.filter { it != HmaMode.OFF }.forEach { assertTrue(it.name, it.enabled) }
    }

    @Test fun `labels name known states, number unknown ones and dash an absent one`() {
        assertEquals("CAMERA_BLOCKED", HmaMode.label(5))
        assertEquals("9", HmaMode.label(9))
        assertEquals("-", HmaMode.label(null))
    }

    @Test fun `the HAL copy wins over the plain state, the plain one fills in when it is absent`() {
        assertEquals(3, HmaReading(hmaState = 1, hmaStateHal = 3).effectiveState)
        assertEquals(1, HmaReading(hmaState = 1).effectiveState)
        assertEquals(HmaMode.CAMERA_BLOCKED, HmaReading(hmaStateHal = 5).mode)
        assertNull(HmaReading().mode)
    }

    @Test fun `a pushed signal lands in its own field`() {
        val r = HmaReading()
            .with(HmaSignals.HMA_STATE, 2, 0.0)
            .with(HmaSignals.HMA_GRAY, 1, 0.0)
            .with(HmaSignals.HMA_CLUSTER_FAULT, 1, 0.0)
            .with(HmaSignals.HEADLIGHT_KNOB, 2, 0.0)
            .with(HmaSignals.RAIN_ERROR, 1, 0.0)
            .with(HmaSignals.GLASS_HUMIDITY, 0, 127.5)
            .with(HmaSignals.LIGHT_INTENSITY, 1234, 0.0)
            .with(HmaReading.HIGH_BEAM_FIELD, 1, 0.0)
        assertEquals(2, r.hmaState)
        assertEquals(1, r.gray)
        assertEquals(1, r.clusterFault)
        assertEquals(2, r.knob)
        assertEquals(1, r.rainError)
        assertEquals(127.5, r.glassHumidity!!, 0.0)
        assertEquals(1234.0, r.lightIntensity!!, 0.0)
        assertEquals(1, r.highBeam)
    }

    @Test fun `a sentinel reads as absent, not as a state`() {
        val r = HmaReading(hmaState = 3).with(HmaSignals.HMA_STATE, 0xFFFF, 0.0)
        assertNull(r.hmaState)
    }

    @Test fun `an unknown key changes nothing`() {
        val r = HmaReading(hmaState = 3)
        assertEquals(r, r.with("somethingElse", 5, 0.0))
    }

    @Test fun `raised sensor errors are named, cleared ones are not`() {
        assertEquals(listOf("rain", "mist"), HmaReading(rainError = 1, illuminanceError = 0, mistError = 2).sensorErrors)
        assertEquals(emptyList<String>(), HmaReading().sensorErrors)
    }

    @Test fun `numbers are short and locale free`() {
        assertEquals("12", HmaReading.fmt(12.0))
        assertEquals("12.5", HmaReading.fmt(12.5))
        assertEquals("-", HmaReading.fmt(null))
    }
}
