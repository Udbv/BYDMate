package com.bydmate.app.data.automation

import com.bydmate.app.data.automation.AmbientNightController.Companion.MAX_ATTEMPTS
import com.bydmate.app.data.automation.AmbientNightController.Companion.RETRY_MS
import com.bydmate.app.data.automation.AmbientNightController.Companion.SETTLE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientNightStepTest {

    private val session = 1_000L

    private fun step(
        state: AmbientNightState,
        now: Long,
        powerOn: Boolean? = true,
        sessionId: Long? = session,
        handled: Long? = null,
        dark: Boolean? = true,
    ) = ambientNightStep(state, powerOn, sessionId, handled, dark, now)

    @Test
    fun `fires once after the settle delay when dark at power-on`() {
        val armed = step(AmbientNightState(), now = 0L)
        assertFalse(armed.fire)
        assertFalse(step(armed.state, now = SETTLE_MS - 1).fire)

        val fired = step(armed.state, now = SETTLE_MS)
        assertTrue(fired.fire)

        val done = ambientNightAfterDispatch(fired.state, success = true)
        assertFalse(step(done, now = SETTLE_MS + 60_000L).fire)
    }

    @Test
    fun `waits while it is light and fires when it gets dark on the way`() {
        var s = step(AmbientNightState(), now = 0L, dark = false).state
        s = step(s, now = 60_000L, dark = false).also { assertFalse(it.fire) }.state
        s = step(s, now = 120_000L, dark = null).also { assertFalse(it.fire) }.state
        assertTrue(step(s, now = 180_000L, dark = true).fire)
    }

    @Test
    fun `already handled session never fires again after a process restart`() {
        val armed = step(AmbientNightState(), now = 0L, handled = session)
        assertFalse(armed.fire)
        assertFalse(step(armed.state, now = SETTLE_MS * 10, handled = session).fire)
    }

    @Test
    fun `power-off re-arms and clears the persisted marker`() {
        val fired = step(step(AmbientNightState(), now = 0L).state, now = SETTLE_MS)
        val done = ambientNightAfterDispatch(fired.state, success = true)

        val off = step(done, now = SETTLE_MS + 1, powerOn = false)
        assertFalse(off.fire)
        assertEquals(HandledChange.CLEAR, off.persist)

        // Same widget session survives a short stop; the car dropped the light, so fire again.
        val rearmed = step(off.state, now = 100_000L)
        assertFalse(rearmed.fire)
        assertTrue(step(rearmed.state, now = 100_000L + SETTLE_MS).fire)
    }

    @Test
    fun `failed write retries with a gap and gives up after max attempts`() {
        var s = step(AmbientNightState(), now = 0L).state
        var now = SETTLE_MS
        repeat(MAX_ATTEMPTS) { attempt ->
            val r = step(s, now = now)
            assertTrue("attempt ${attempt + 1}", r.fire)
            s = ambientNightAfterDispatch(r.state, success = false)
            assertFalse(step(s, now = now + RETRY_MS - 1).fire)
            now += RETRY_MS
        }
        assertFalse(step(s, now = now + RETRY_MS * 10).fire)
    }

    @Test
    fun `unknown power state or no session does nothing`() {
        assertFalse(step(AmbientNightState(), now = SETTLE_MS * 2, powerOn = null).fire)
        assertFalse(step(AmbientNightState(), now = SETTLE_MS * 2, sessionId = null).fire)
    }
}
