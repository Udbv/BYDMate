package com.bydmate.app.data.automation

import android.content.SharedPreferences
import com.bydmate.app.data.local.entity.ActionDef

/**
 * Where a rule with pauses stood when the app went away: the run started at [startedAt] and was
 * about to do step [nextStep] (0-based). [actionsHash] ties it to the rule's steps as they were,
 * so an edited rule is not resumed with someone else's plan.
 */
internal data class RunCheckpoint(
    val ruleId: Long,
    val startedAt: Long,
    val nextStep: Int,
    val savedAt: Long,
    val actionsHash: Int,
)

/**
 * Keeps [RunCheckpoint]s of runs in progress. A run with pauses («Зимний старт»: seat 3, a
 * minute, seat 2 and the wheel off ...) dies with the process when the car is switched off
 * between steps, and the car keeps the heaters as they were: the checkpoint lets the next start
 * finish the run. Written synchronously before each step: the process may die right after.
 */
internal class RunCheckpointStore(private val prefs: SharedPreferences) {

    fun save(cp: RunCheckpoint) {
        prefs.edit()
            .putString(key(cp.ruleId), "${cp.startedAt},${cp.nextStep},${cp.savedAt},${cp.actionsHash}")
            .commit()
    }

    fun clear(ruleId: Long) {
        prefs.edit().remove(key(ruleId)).commit()
    }

    fun all(): List<RunCheckpoint> = prefs.all.mapNotNull { (k, v) ->
        if (!k.startsWith(PREFIX)) return@mapNotNull null
        val ruleId = k.removePrefix(PREFIX).toLongOrNull() ?: return@mapNotNull null
        val parts = (v as? String)?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return@mapNotNull null
        if (parts.size != FIELDS) return@mapNotNull null
        RunCheckpoint(ruleId, parts[0], parts[1].toInt(), parts[2], parts[3].toInt())
    }

    private fun key(ruleId: Long) = "$PREFIX$ruleId"

    private companion object {
        const val PREFIX = "run_cp_"
        const val FIELDS = 4
    }
}

/**
 * The run's own clock. A run with pauses keeps time from its start, so a pause ends when it would
 * have ended had nothing stopped: one whose end already passed while the app was gone is skipped,
 * one still running waits only what is left. A «Зимний старт» cut after its first minute and
 * resumed eight minutes later lands straight on the step it would be at by now (heaters down or
 * off) instead of replaying the warm-up.
 */
internal object RunResume {

    /** Older than this the car has long since moved on: the run is dropped, not finished. */
    const val MAX_AGE_MS = 60 * 60_000L

    fun resumable(cp: RunCheckpoint, actionsHash: Int, stepCount: Int, now: Long): Boolean =
        cp.actionsHash == actionsHash && cp.nextStep in 0 until stepCount &&
            now - cp.savedAt <= MAX_AGE_MS && now >= cp.startedAt

    /** What is left at [now] of the pause at [index], counting every pause up to it from [startedAt]. */
    fun pauseLeftMs(actions: List<ActionDef>, index: Int, startedAt: Long, now: Long): Long {
        val end = startedAt + actions.take(index + 1).sumOf { pauseMs(it) }
        return end - now
    }

    fun hasPauses(actions: List<ActionDef>): Boolean = actions.any { it.kind == DELAY }

    fun isPause(action: ActionDef): Boolean = action.kind == DELAY

    private fun pauseMs(action: ActionDef): Long =
        if (action.kind == DELAY) action.payload?.toLongOrNull() ?: 0L else 0L

    private const val DELAY = "delay"
}
