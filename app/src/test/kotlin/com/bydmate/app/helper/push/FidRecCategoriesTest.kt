package com.bydmate.app.helper.push

import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.hma.HmaSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder's «Світло/HMA» category covers every signal the HMA watch reads. */
class FidRecCategoriesTest {

    private val watchFields = listOf("lightHigh", "lightLow", "lightLevel", "wiperRelay", "autoWipers")

    @Test fun `every HMA watch signal is in the category`() {
        val missing = HmaSignals.SYMBOLS.filter { it !in FID_REC_HMA_SYMBOLS }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `the FidMap fields the watch lays them against are in the category too`() {
        val missing = watchFields.mapNotNull { FidMap.byField.getValue(it).symbol }.filter { it !in FID_REC_HMA_SYMBOLS }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `a recording of all devices reaches every device of the category`() {
        val devices = HmaSignals.ALL.map { it.device } + watchFields.map { FidMap.byField.getValue(it).device }
        val missing = devices.distinct().filter { it !in FID_PUSH_DEVICE_CLASSES }
        assertEquals(emptyList<Int>(), missing)
    }

    @Test fun `a signal is discrete or analogue, never both`() {
        assertEquals(emptySet<String>(), FID_REC_HMA_DISCRETE intersect FID_REC_HMA_ANALOGUE)
    }

    @Test fun `the HMA states and the lamp are written on every change`() {
        listOf("Adas.ADAS_HMA_STATE", "Adas.ADAS_HMA_STATE_HAL", "Adas.ADAS_HMA_HIGH_BEAM_ASSIST_GRAY",
            "Light.LIGHT_HIGH_BEAM_LIGHT").forEach { assertTrue(it, it in FID_REC_HMA_DISCRETE) }
    }

    @Test fun `no write address is in the category`() {
        assertTrue(FID_REC_HMA_SYMBOLS.none { it == "Adas.ADAS_HMA_STATE_SET" || it.contains("HEADLIGHT_CONTROL_SET") })
    }

    @Test fun `the header names the category`() {
        assertTrue(fidRecHmaHeader().startsWith("hma=${FID_REC_HMA_SYMBOLS.size} signals (Світло/HMA)"))
    }
}
