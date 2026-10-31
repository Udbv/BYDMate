package com.bydmate.app.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardHistoryTest {

    private fun card(kw: Double) = PowerCardState(totalKw = kw, frontPercent = 40, climateKw = 1.0, otherKw = 0.5)

    @Test fun `keeps two minutes and the trip's peaks`() {
        var h = CardHistory()
        h = h.add(card(80.0), 0)
        h = h.add(card(-30.0), 1_000)
        h = h.add(card(20.0), 2_000)
        assertEquals(80.0, h.peakKw!!, 1e-9)
        assertEquals(-30.0, h.regenPeakKw!!, 1e-9)
        assertEquals((20.0 - 1.5) * 0.4, h.samples.last().frontKw!!, 1e-9)
        // Ticks every 5 s past the window: the oldest drop out, the peaks stay.
        for (t in 7_000L..CardHistory.WINDOW_MS + 7_000L step 5_000L) h = h.add(card(10.0), t)
        assertEquals(true, h.samples.first().atMs > 0)
        assertEquals(80.0, h.peakKw!!, 1e-9)
    }

    @Test fun `a silence starts the graphs afresh`() {
        val h = CardHistory().add(card(10.0), 0).add(card(10.0), CardHistory.GAP_RESET_MS + 1)
        assertEquals(1, h.samples.size)
    }

    @Test fun `no power, nothing added`() {
        val h = CardHistory().add(PowerCardState(), 0)
        assertEquals(0, h.samples.size)
        assertNull(h.peakKw)
    }
}
