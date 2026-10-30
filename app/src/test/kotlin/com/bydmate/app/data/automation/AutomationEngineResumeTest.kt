package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A run with pauses that a car-off cut between steps finishes on the next start, on its own
 * clock: «Зимний старт» cut after its first minute and resumed eight minutes later goes straight
 * to the steps it would be at by now (on the 2026-10-30 drive the seat and the wheel stayed on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEngineResumeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(AutomationEngine.PREFS_NAME, Context.MODE_PRIVATE)
    private val wall = 1_793_362_660_000L

    private val steps = listOf(
        ActionDef("seat3", "seat 3"),
        ActionDef("", "1 min", "delay", "60000"),
        ActionDef("seat2", "seat 2"),
        ActionDef("wheelOff", "wheel off"),
        ActionDef("", "2 min", "delay", "120000"),
        ActionDef("seat1", "seat 1"),
        ActionDef("", "5 min", "delay", "300000"),
        ActionDef("seatOff", "seat off"),
    )

    // Its trigger stays false: only a resume can run it.
    private val rule = RuleEntity(
        id = 2, name = "winter",
        triggerLogic = "AND",
        triggers = TriggerDef.listToJson(listOf(TriggerDef("Speed", "", ">", "999", "speed"))),
        actions = ActionDef.listToJson(steps),
        cooldownSeconds = 0,
    )

    @Before fun clean() {
        prefs.edit().clear().commit()
    }

    private fun engine(dispatcher: ActionDispatcher): AutomationEngine {
        val ruleDao = mockk<RuleDao>(relaxed = true) { coEvery { getEnabled() } returns listOf(rule) }
        return AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = dispatcher,
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = context,
            appStrings = com.bydmate.app.util.AppStrings(context),
        ).apply {
            elapsedMs = { 3_600_000L }
            nowMs = { wall }
            interactiveProvider = { true }
        }
    }

    private fun dispatcher() = mockk<ActionDispatcher> {
        coEvery { dispatch(any(), any()) } returns DispatchResult(true)
    }

    private fun checkpoint(startedAgoMs: Long, nextStep: Int, savedAgoMs: Long) =
        RunCheckpointStore(prefs).save(
            RunCheckpoint(rule.id, wall - startedAgoMs, nextStep, wall - savedAgoMs, rule.actions.hashCode()),
        )

    @Test fun `a run cut in its first pause lands on the steps it would be at by now`() = runBlocking {
        checkpoint(startedAgoMs = 9 * 60_000L, nextStep = 1, savedAgoMs = 8 * 60_000L)
        val d = dispatcher()

        engine(d).evaluate(diParsData(soc = 50), null)

        coVerify(timeout = 5_000) { d.dispatch(match { it.command == "seatOff" }, any()) }
        coVerify(exactly = 0) { d.dispatch(match { it.command == "seat3" }, any()) }
        coVerify(exactly = 1) { d.dispatch(match { it.command == "wheelOff" }, any()) }
        // Every pause had already ended: none was waited.
        coVerify(exactly = 0) { d.dispatch(match { it.kind == "delay" }, any()) }
        assertTrue(RunCheckpointStore(prefs).all().isEmpty())
    }

    @Test fun `a pause still running waits only what is left`() = runBlocking {
        checkpoint(startedAgoMs = 30_000L, nextStep = 1, savedAgoMs = 29_000L)
        val d = dispatcher()

        engine(d).evaluate(diParsData(soc = 50), null)

        coVerify(timeout = 5_000) { d.dispatch(match { it.kind == "delay" && it.payload == "30000" }, any()) }
    }

    @Test fun `a run older than an hour is dropped, not finished`() = runBlocking {
        checkpoint(startedAgoMs = 3 * 3_600_000L, nextStep = 1, savedAgoMs = 3 * 3_600_000L - 60_000L)
        val d = dispatcher()

        engine(d).evaluate(diParsData(soc = 50), null)

        assertEquals(emptyList<RunCheckpoint>(), RunCheckpointStore(prefs).all())
        coVerify(exactly = 0) { d.dispatch(any(), any()) }
    }

    @Test fun `an edited rule is not resumed with the old plan`() = runBlocking {
        RunCheckpointStore(prefs).save(RunCheckpoint(rule.id, wall - 60_000L, 1, wall - 30_000L, 12345))
        val d = dispatcher()

        engine(d).evaluate(diParsData(soc = 50), null)

        assertTrue(RunCheckpointStore(prefs).all().isEmpty())
        coVerify(exactly = 0) { d.dispatch(any(), any()) }
    }

    @Test fun `the pause clock counts every pause from the run's start`() {
        assertEquals(60_000L, RunResume.pauseLeftMs(steps, 1, startedAt = 0L, now = 0L))
        assertEquals(150_000L, RunResume.pauseLeftMs(steps, 4, startedAt = 0L, now = 30_000L))
        assertTrue(RunResume.pauseLeftMs(steps, 6, startedAt = 0L, now = 9 * 60_000L) < 0)
    }
}
