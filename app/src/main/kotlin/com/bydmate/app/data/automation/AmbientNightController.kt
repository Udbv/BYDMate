package com.bydmate.app.data.automation

import android.content.Context
import android.location.Location
import android.util.Log
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import dagger.hilt.android.qualifiers.ApplicationContext
import org.shredzone.commons.suncalc.SunPosition
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Амбиент в темноте»: the car drops the ambient (atmosphere) light between trips, so once per
 * power cycle — at power-on if it is already dark, or the first time it gets dark during the
 * drive — turn it back on. Once per cycle on purpose: if the driver switches it off by hand, it
 * stays off until the next power-on.
 *
 * Not an automation rule: rules are edge-triggered and seed silently on their first tick, so a
 * "dark AND power on" rule never fires when BYDMate itself starts with the car already on at
 * night — exactly the case this feature is for.
 */
@Singleton
class AmbientNightController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionDispatcher: ActionDispatcher,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var state = AmbientNightState()

    /** Called on every poll tick from TrackingService. [sessionId] is the widget session start. */
    suspend fun onTick(data: DiParsData, sessionId: Long?, location: Location?, now: Long = System.currentTimeMillis()) {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val dark = isDark(location, data.lightLevel, now)
        val step = ambientNightStep(
            state = state,
            powerOn = data.powerState?.let { it >= 1 },
            sessionId = sessionId,
            handledSession = prefs.getLong(KEY_HANDLED_SESSION, NO_SESSION).takeIf { it != NO_SESSION },
            dark = dark,
            now = now,
        )
        state = step.state
        when (step.persist) {
            HandledChange.CLEAR ->
                if (prefs.contains(KEY_HANDLED_SESSION)) prefs.edit().remove(KEY_HANDLED_SESSION).apply()
            HandledChange.NONE -> Unit
        }
        if (!step.fire || sessionId == null) return

        val result = actionDispatcher.dispatch(ActionDef(COMMAND_AMBIENT_ON, COMMAND_AMBIENT_ON), data)
        Log.i(TAG, "ambient on (session=$sessionId, attempt=${state.attempts}): " +
            "success=${result.success} reason=${result.reason}")
        state = ambientNightAfterDispatch(state, result.success)
        if (result.success || state.attempts >= MAX_ATTEMPTS) {
            prefs.edit().putLong(KEY_HANDLED_SESSION, sessionId).apply()
        }
    }

    companion object {
        private const val TAG = "AmbientNight"
        const val PREFS_NAME = "ambient_night"
        const val KEY_ENABLED = "enabled"
        private const val KEY_HANDLED_SESSION = "handled_session"
        private const val NO_SESSION = -1L

        /** CommandTranslator → ambient_light_on (dev=1023 brightness carve-out, raw 5 = level 4). */
        const val COMMAND_AMBIENT_ON = "氛围灯打开"

        /** Car settings apply their own state right after power-on; writing earlier can be undone. */
        const val SETTLE_MS = 15_000L
        const val RETRY_MS = 10_000L
        const val MAX_ATTEMPTS = 3

        /** Sensor scale: 1 = dark … 5 = bright. */
        const val DARK_LIGHT_LEVEL_MAX = 2

        /**
         * Sun below the horizon (geometric altitude < 0°) wins when the position is known; the
         * light sensor is the fallback, since a tunnel or a garage reads dark at noon. Null when
         * neither is available yet — the controller then waits instead of guessing.
         */
        fun isDark(location: Location?, lightLevel: Int?, now: Long): Boolean? {
            if (location != null) {
                val altitude = SunPosition.compute()
                    .at(location.latitude, location.longitude)
                    .on(Date(now))
                    .execute()
                    .altitude
                return altitude < 0.0
            }
            return lightLevel?.let { it in 1..DARK_LIGHT_LEVEL_MAX }
        }
    }
}

internal data class AmbientNightState(
    /** Session this cycle belongs to; a new one re-arms. */
    val sessionId: Long? = null,
    /** When power-on was first seen for this cycle — the settle delay counts from here. */
    val armedAt: Long? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    val done: Boolean = false,
)

internal enum class HandledChange { NONE, CLEAR }

internal data class AmbientNightStep(
    val state: AmbientNightState,
    val fire: Boolean,
    val persist: HandledChange = HandledChange.NONE,
)

/**
 * One poll tick of the once-per-power-cycle decision. Pure, so the edge cases are unit-tested:
 * power-off re-arms (the car drops the light at every power-off, even inside one widget session
 * that survives a short stop), and [handledSession] — persisted — keeps a process restart in
 * the middle of a drive from turning the light back on after the driver switched it off.
 */
internal fun ambientNightStep(
    state: AmbientNightState,
    powerOn: Boolean?,
    sessionId: Long?,
    handledSession: Long?,
    dark: Boolean?,
    now: Long,
): AmbientNightStep {
    if (powerOn == false) {
        return AmbientNightStep(AmbientNightState(), fire = false, persist = HandledChange.CLEAR)
    }
    if (powerOn == null || sessionId == null) return AmbientNightStep(state, fire = false)

    var s = if (state.sessionId != sessionId) AmbientNightState(sessionId = sessionId, armedAt = now) else state
    if (s.armedAt == null) s = s.copy(armedAt = now)
    if (s.done || handledSession == sessionId) return AmbientNightStep(s.copy(done = true), fire = false)
    if (dark != true) return AmbientNightStep(s, fire = false)
    if (now - s.armedAt!! < AmbientNightController.SETTLE_MS) return AmbientNightStep(s, fire = false)
    if (s.attempts > 0 && now - s.lastAttemptAt < AmbientNightController.RETRY_MS) {
        return AmbientNightStep(s, fire = false)
    }
    return AmbientNightStep(s.copy(attempts = s.attempts + 1, lastAttemptAt = now), fire = true)
}

internal fun ambientNightAfterDispatch(state: AmbientNightState, success: Boolean): AmbientNightState =
    if (success || state.attempts >= AmbientNightController.MAX_ATTEMPTS) state.copy(done = true) else state
