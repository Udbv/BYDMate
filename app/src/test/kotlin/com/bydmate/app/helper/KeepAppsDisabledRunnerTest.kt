package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAppsDisabledRunnerTest {

    private var now = 0L
    private val queued = mutableListOf<Runnable>()
    private val calls = mutableListOf<String>()
    private val shell = KeptDisabledApps.Shell { script, _ ->
        calls += script
        0 to KeptDisabledApps.ALLOWLIST.joinToString("\n") { "package:${it.pkg}" }
    }
    private val runner = KeepAppsDisabledRunner(shell, { queued += it }, { now }, {})

    @Test fun `a valid request is answered at once and runs on the worker`() {
        assertEquals(KeptDisabledApps.STATUS_STARTED, runner.request(0b11111, 0))
        assertTrue("nothing ran on the binder thread", calls.isEmpty())
        queued.single().run()
        assertEquals(listOf(KeptDisabledApps.LIST_DISABLED), calls)
    }

    @Test fun `bits outside the allowlist are refused without queuing`() {
        assertEquals(KeptDisabledApps.STATUS_REFUSED, runner.request(1 shl 7, 0))
        assertEquals(KeptDisabledApps.STATUS_REFUSED, runner.request(1, 1))
        assertTrue(queued.isEmpty())
    }

    @Test fun `repeats are throttled until a minute passes or the choice changes`() {
        assertEquals(KeptDisabledApps.STATUS_STARTED, runner.request(1, 0))
        now = 10_000L
        assertEquals(KeptDisabledApps.STATUS_THROTTLED, runner.request(1, 0))
        assertEquals(KeptDisabledApps.STATUS_STARTED, runner.request(0, 1))
        now = 80_000L
        assertEquals(KeptDisabledApps.STATUS_STARTED, runner.request(0, 1))
        assertEquals(3, queued.size)
    }

    @Test fun `a failing pass does not escape the worker`() {
        val boom = KeepAppsDisabledRunner({ _, _ -> error("pm died") }, { it.run() }, { 0L }, {})
        assertEquals(KeptDisabledApps.STATUS_STARTED, boom.request(1, 0))
    }
}
