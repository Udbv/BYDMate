package com.bydmate.app.navvoice

/** What the guidance shows now, as the planner needs it (from NavGuidanceHub.Snapshot). */
data class NavVoiceInput(
    val active: Boolean,
    val gaode: Int,
    val distanceM: Int,
    val road: String,
    val exitNumber: Int?,
)

/**
 * When BYDMate speaks a maneuver. Pure, fed every ~500 ms. Three calls per maneuver, each once:
 * - far: at [FAR_M] or closer, only when the maneuver was first seen beyond [FAR_SEEN_M] (one that
 *   shows up nearer gets the near call only) and still well beyond [NEAR_M];
 * - near: at [NEAR_M] or closer while still well beyond [NOW_M];
 * - now: at [NOW_M] or closer.
 * A maneuver is its code, exit and street; a change of any of them, the distance jumping up by
 * more than [JUMP_M] (the same turn at the next junction) or the guidance going inactive starts
 * afresh. Two phrases are at least [MIN_GAP_MS] apart; a call held back by the gap is said on a
 * later tick if it still applies. A stage said makes the earlier ones moot.
 */
class NavVoicePlanner {

    private enum class Stage { FAR, NEAR, NOW }

    private var key: String? = null
    private var firstSeenM = 0
    private var lastM = 0
    private val said = mutableSetOf<Stage>()
    private var lastSpokeMs: Long? = null

    /** The phrase to say now, or null. */
    fun next(input: NavVoiceInput, nowMs: Long): String? {
        if (!speakable(input)) {
            reset()
            return null
        }
        track(input)
        if (lastSpokeMs?.let { nowMs - it < MIN_GAP_MS } == true) return null
        val stage = due(input.distanceM) ?: return null
        val phrase = when (stage) {
            Stage.NOW -> NavVoicePhrases.now(input.gaode, input.exitNumber)
            else -> NavVoicePhrases.ahead(input.gaode, input.exitNumber, input.distanceM, input.road)
        } ?: return null
        Stage.entries.filter { it.ordinal <= stage.ordinal }.forEach { said += it }
        lastSpokeMs = nowMs
        return phrase
    }

    fun reset() {
        key = null
        said.clear()
    }

    private fun speakable(input: NavVoiceInput): Boolean =
        input.active && input.gaode > 0 && input.distanceM > 0 && NavVoicePhrases.voiced(input.gaode, input.exitNumber)

    private fun track(input: NavVoiceInput) {
        val k = "${input.gaode}|${input.exitNumber}|${input.road.trim()}"
        if (k != key || input.distanceM > lastM + JUMP_M) {
            key = k
            said.clear()
            firstSeenM = input.distanceM
        }
        lastM = input.distanceM
    }

    private fun due(m: Int): Stage? = when {
        m <= NOW_M -> Stage.NOW.takeUnless { it in said }
        m <= NEAR_M -> Stage.NEAR.takeIf { it !in said && m > NOW_M + NEAR_MARGIN_M }
        m <= FAR_M -> Stage.FAR.takeIf { it !in said && firstSeenM > FAR_SEEN_M && m > NEAR_M + FAR_MARGIN_M }
        else -> null
    }

    companion object {
        const val FAR_M = 1_000
        const val FAR_SEEN_M = 1_200
        const val NEAR_M = 400
        const val NOW_M = 60
        const val JUMP_M = 300
        const val MIN_GAP_MS = 4_000L
        /** No «через 100 метров» right before «сейчас». */
        private const val NEAR_MARGIN_M = 60
        /** No «через километр» right before «через 400 метров». */
        private const val FAR_MARGIN_M = 150
    }
}
