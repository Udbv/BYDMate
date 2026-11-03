package com.bydmate.app.adas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CruiseAdviceRuleTest {

    private fun cruise(kph: Int?, mode: Int? = 2) = AdasCarReading(accMode = mode, cruiseKph = kph)

    @Test fun `a limit well under the set cruise is said with both numbers in Russian`() {
        val advice = CruiseAdviceRule().decide(0L, cruise(70), 50, voiceBusy = false)
        assertNotNull(advice)
        assertEquals("Ограничение 50, круиз 70", advice!!.phrase)
    }

    @Test fun `a limit less than five under the cruise, equal or above says nothing`() {
        val rule = CruiseAdviceRule()
        assertNull(rule.decide(0L, cruise(54), 50, false))
        assertNull(rule.decide(0L, cruise(50), 50, false))
        assertNull(rule.decide(0L, cruise(40), 50, false))
        assertNotNull(rule.decide(0L, cruise(55), 50, false))
    }

    @Test fun `no cruise, cruise off or no Waze limit says nothing`() {
        val rule = CruiseAdviceRule()
        assertNull(rule.decide(0L, null, 50, false))
        assertNull(rule.decide(0L, cruise(null), 50, false))
        assertNull(rule.decide(0L, cruise(90, mode = CruiseAdviceRule.ACC_OFF), 50, false))
        assertNull(rule.decide(0L, cruise(90), 0, false))
        assertNull(rule.decide(0L, cruise(90), null, false))
    }

    @Test fun `an unanswered ACC mode falls back to the set speed`() {
        assertTrue(CruiseAdviceRule.engaged(cruise(90, mode = null)))
        assertFalse(CruiseAdviceRule.engaged(cruise(null, mode = 3)))
        assertNotNull(CruiseAdviceRule().decide(0L, cruise(90, mode = null), 50, false))
    }

    @Test fun `the same limit is said once, then again only after a minute`() {
        val rule = CruiseAdviceRule()
        assertNotNull(rule.decide(0L, cruise(70), 50, false))
        assertNull(rule.decide(2_000L, cruise(70), 50, false))
        assertNull(rule.decide(59_999L, cruise(70), 50, false))
        assertNotNull(rule.decide(60_000L, cruise(70), 50, false))
    }

    @Test fun `a new limit is said at once, but not within the spacing of the last advice`() {
        val rule = CruiseAdviceRule()
        assertNotNull(rule.decide(0L, cruise(90), 70, false))
        assertNull(rule.decide(4_000L, cruise(90), 50, false))
        val advice = rule.decide(CruiseAdviceRule.MIN_SPACING_MS, cruise(90), 50, false)
        assertEquals(50, advice?.limitKph)
    }

    @Test fun `a live voice session holds the advice until it ends`() {
        val rule = CruiseAdviceRule()
        assertNull(rule.decide(0L, cruise(70), 50, voiceBusy = true))
        assertNotNull(rule.decide(2_000L, cruise(70), 50, voiceBusy = false))
    }

    @Test fun `reset starts a new drive with nothing said`() {
        val rule = CruiseAdviceRule()
        assertNotNull(rule.decide(0L, cruise(70), 50, false))
        rule.reset()
        assertNotNull(rule.decide(1_000L, cruise(70), 50, false))
    }

    @Test fun `the advisor decodes the two replies and drops failed or out of range ones`() {
        val r = CruiseAdvisor.decode(listOf(0 to 2, 0 to 110))
        assertEquals(2, r.accMode)
        assertEquals(110, r.cruiseKph)
        val bad = CruiseAdvisor.decode(listOf(1 to 2, 0 to 0))
        assertNull(bad.accMode)
        assertNull(bad.cruiseKph)
        assertEquals(2, CruiseAdvisor.ITEMS.size)
    }
}
