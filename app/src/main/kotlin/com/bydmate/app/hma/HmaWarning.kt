package com.bydmate.app.hma

import com.bydmate.app.data.automation.AmbientNightController

/** Why the automatic high beam cannot be trusted right now. */
enum class HmaReason(val code: String) {
    /** HMA state 4: the car reports the function faulty. */
    FAULT("fault"),

    /** HMA state 5: the camera behind the windscreen is blocked (dirt, fog, ice). */
    CAMERA_BLOCKED("camera-blocked"),

    /** The gray flag at night, moving, with the function on: it is suppressed right when it is needed. */
    SUPPRESSED("suppressed"),

    /** A light, rain or mist sensor behind the windscreen reports an error. */
    SENSOR_ERROR("sensor-error"),
}

/** Which reasons hold in one reading. Pure: no time, no memory. */
object HmaWarningRule {

    /** Below this the car is treated as standing: a warning is for the drive, not for the car park. */
    const val MOVING_KPH = 10

    fun reasons(r: HmaReading): Set<HmaReason> {
        if ((r.speedKph ?: 0) < MOVING_KPH) return emptySet()
        val out = mutableSetOf<HmaReason>()
        when (r.mode) {
            HmaMode.FAULT -> out += HmaReason.FAULT
            HmaMode.CAMERA_BLOCKED -> out += HmaReason.CAMERA_BLOCKED
            else -> Unit
        }
        val night = r.lightLevel?.let { it in 1..AmbientNightController.DARK_LIGHT_LEVEL_MAX } == true
        if (r.gray == 1 && night && r.mode?.enabled == true) out += HmaReason.SUPPRESSED
        if (r.sensorErrors.isNotEmpty()) out += HmaReason.SENSOR_ERROR
        return out
    }
}

/**
 * The anti-spam half: a reason must hold for [holdMs] before it is shown (a one-tick flicker of
 * the state is not a dirty windscreen), a short gap of up to [gapMs] does not restart that count
 * (hysteresis), and each reason is shown at most once per trip. [newTrip] forgets what was shown.
 */
class HmaWarningGate(
    private val holdMs: Long = HOLD_MS,
    private val gapMs: Long = GAP_MS,
) {
    private val since = HashMap<HmaReason, Long>()
    /** When the reason was first found missing since it last held; absent while it holds. */
    private val missingSince = HashMap<HmaReason, Long>()
    private val shown = HashSet<HmaReason>()

    /**
     * The reasons to show now — each one only the first time it qualifies in this trip. The
     * calls come at the poll's pace, so the time between two of them says nothing; only an
     * observed absence longer than [gapMs] restarts the count.
     */
    fun update(active: Set<HmaReason>, nowMs: Long): Set<HmaReason> {
        for (reason in HmaReason.entries) {
            if (reason in active) {
                val missing = missingSince.remove(reason)
                if (missing != null && nowMs - missing > gapMs) since.remove(reason)
                if (reason !in since) since[reason] = nowMs
            } else if (reason in since) {
                val missing = missingSince.getOrPut(reason) { nowMs }
                if (nowMs - missing > gapMs) {
                    since.remove(reason)
                    missingSince.remove(reason)
                }
            }
        }
        val due = active.filter { reason ->
            reason !in shown && (since[reason]?.let { nowMs - it >= holdMs } == true)
        }.toSet()
        shown += due
        return due
    }

    /** The reasons already shown in this trip. */
    fun shown(): Set<HmaReason> = shown.toSet()

    fun newTrip() {
        since.clear()
        missingSince.clear()
        shown.clear()
    }

    companion object {
        /** How long a reason must hold before the driver hears about it. */
        const val HOLD_MS = 10_000L

        /** A dropout up to this long keeps the count running. */
        const val GAP_MS = 5_000L
    }
}
