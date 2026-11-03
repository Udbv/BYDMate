package com.bydmate.app.adas

/**
 * When to say "the limit dropped below your cruise" (#23, the safe class: a voice, nothing sent
 * to the car). Pure: the clock, the car's cruise and Waze's limit come in, an [Advice] or null
 * comes out, so the whole policy is unit-testable.
 *
 * - The cruise counts as engaged when the car reports a set speed and its ACC mode is not
 *   [ACC_OFF]. The mode values are not decoded yet (fidrec 2026-10-25 §3.7 has no ACC events), so
 *   only 0 is taken as "off", the usual idle value of the car's mode enums; a car that did not
 *   answer the mode falls back to the set speed alone.
 * - Advice only when Waze's limit is at least [MIN_GAP_KPH] under the set speed: a limit a few
 *   km/h under the cruise is within the car's own tolerance and would only nag.
 * - The same limit is said once, then again only after [REPEAT_MS]: the driver may have missed
 *   it, but a voice every tick would be worse than none. A new limit value is said at once,
 *   unless the last advice was under [MIN_SPACING_MS] ago, so a limit flapping between two
 *   values on a junction does not chatter.
 * - Never while [voiceBusy]: a live voice dialog always wins. The skip is not remembered, so the
 *   advice comes once the dialog ends if it still holds.
 */
class CruiseAdviceRule {
    private var lastLimitKph: Int? = null
    private var lastSaidMs: Long? = null

    /** What to say and why. */
    data class Advice(val limitKph: Int, val cruiseKph: Int) {
        /** Russian: the car's voice language, whatever the UI language is. */
        val phrase: String get() = "Ограничение $limitKph, круиз $cruiseKph"
    }

    fun decide(nowMs: Long, car: AdasCarReading?, wazeLimitKph: Int?, voiceBusy: Boolean): Advice? {
        val cruise = car?.takeIf { engaged(it) }?.cruiseKph
        val limit = wazeLimitKph?.takeIf { it > 0 }
        if (voiceBusy || cruise == null || limit == null) return null
        if (cruise - limit < MIN_GAP_KPH) return null
        val sinceLast = lastSaidMs?.let { nowMs - it } ?: Long.MAX_VALUE
        val due = if (limit == lastLimitKph) sinceLast >= REPEAT_MS else sinceLast >= MIN_SPACING_MS
        if (!due) return null
        lastLimitKph = limit
        lastSaidMs = nowMs
        return Advice(limit, cruise)
    }

    /** A new drive starts with nothing said. */
    fun reset() {
        lastLimitKph = null
        lastSaidMs = null
    }

    companion object {
        const val ACC_OFF = 0
        const val MIN_GAP_KPH = 5
        const val REPEAT_MS = 60_000L
        const val MIN_SPACING_MS = 10_000L

        fun engaged(car: AdasCarReading): Boolean = car.cruiseKph != null && car.accMode != ACC_OFF
    }
}
