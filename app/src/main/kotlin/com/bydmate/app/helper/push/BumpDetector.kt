package com.bydmate.app.helper.push

/** A body pitch swing big enough to be a speed bump: when, how big (°/s peak to peak), how fast. */
data class FidRecBump(val atMs: Long, val peakToPeak: Double, val speedKph: Double)

/**
 * Speed bump candidates from the ADAS body pitch rate (#22), in the daemon where every sample of
 * it is seen (25–40 a second on the Tang L; the app's push keeps one per 200 ms and loses the
 * peaks). The 2026-11-03 city recording: ordinary road roughness swings the pitch rate ±5–6 °/s,
 * the hard double hits ±9–15 °/s; 22 °/s peak to peak offered four of them in 20 min of city. A
 * candidate is a swing of at least [promptPp] peak to peak
 * within [WINDOW_MS], at [MIN_KPH]..[MAX_KPH] and not under hard braking (a dive is not a bump);
 * one per [COOLDOWN_MS], the rear axle's hit belonging to the front's. Swings from [notePp] up are
 * noted in the recording for tuning without being offered.
 */
internal class BumpDetector(
    private val promptPp: Double = PROMPT_PP,
    private val notePp: Double = NOTE_PP,
) {
    private val ring = ArrayDeque<Pair<Long, Double>>()
    private var speed: Double? = null
    private var brake: Double = 0.0
    private var lastAt = -COOLDOWN_MS
    private var lastNoteAt = -COOLDOWN_MS

    /** What a pitch sample came to: nothing, a swing worth a note, or a candidate to offer. */
    sealed interface Verdict {
        data class Note(val bump: FidRecBump) : Verdict
        data class Candidate(val bump: FidRecBump) : Verdict
    }

    fun speed(kph: Double) {
        speed = kph
    }

    fun brake(pedal: Double) {
        brake = pedal
    }

    fun pitch(atMs: Long, rate: Double): Verdict? {
        ring.addLast(atMs to rate)
        while (ring.isNotEmpty() && atMs - ring.first().first > WINDOW_MS) ring.removeFirst()
        val v = speed ?: return null
        if (v < MIN_KPH || v > MAX_KPH || brake >= HARD_BRAKE) return null
        val pp = ring.maxOf { it.second } - ring.minOf { it.second }
        return when {
            pp >= promptPp && atMs - lastAt >= COOLDOWN_MS -> {
                lastAt = atMs
                lastNoteAt = atMs
                Verdict.Candidate(FidRecBump(atMs, pp, v))
            }
            pp >= notePp && atMs - lastNoteAt >= COOLDOWN_MS -> {
                lastNoteAt = atMs
                Verdict.Note(FidRecBump(atMs, pp, v))
            }
            else -> null
        }
    }

    companion object {
        const val PITCH_SYMBOL = "Adas.ADAS_WY_PITCH_RATE"
        const val SPEED_SYMBOL = "Speed.SPEED_AUTO_SPEED_121"
        const val BRAKE_SYMBOL = "Speed.SPEED_BRAKE_S"
        const val PROMPT_PP = 22.0
        const val NOTE_PP = 14.0
        const val WINDOW_MS = 600L
        const val COOLDOWN_MS = 8_000L
        const val MIN_KPH = 5.0
        const val MAX_KPH = 70.0
        const val HARD_BRAKE = 30.0
    }
}

/**
 * The recorder's side of the detector (#22): feeds it the pitch, speed and brake samples, and on
 * a candidate opens a detail window, writes a note and keeps it for the app to offer.
 * Not thread-safe: [FidRecorder] calls it under its own lock.
 */
internal class BumpWatch(private val fast: FastTrack) {
    private val detector = BumpDetector()
    private val bumps = ArrayDeque<FidRecBump>()

    fun bumps(): List<FidRecBump> = bumps.toList()

    fun feed(e: RecEvent, out: MutableList<RecEvent>) {
        val value = fidRecValue(e.intValue, e.doubleValue)
        when (e.symbol) {
            BumpDetector.SPEED_SYMBOL -> detector.speed(value)
            BumpDetector.BRAKE_SYMBOL -> detector.brake(value)
            BumpDetector.PITCH_SYMBOL -> when (val v = detector.pitch(e.atMs, value)) {
                is BumpDetector.Verdict.Candidate -> {
                    bumps.addLast(v.bump)
                    while (bumps.size > MAX_BUMPS) bumps.removeFirst()
                    fast.trigger(e.atMs, "bump? ${describe(v.bump)}", out)
                    out += note(e.atMs, "bump? offered ${describe(v.bump)}")
                }
                is BumpDetector.Verdict.Note -> out += note(e.atMs, "bump? noted ${describe(v.bump)}")
                null -> Unit
            }
        }
    }

    private fun note(atMs: Long, text: String) = RecEvent(atMs, 0, 0, "#bump", 0, 0.0).apply { note = text }

    private fun describe(b: FidRecBump) = "pp=%.1f v=%.0f".format(java.util.Locale.ROOT, b.peakToPeak, b.speedKph)

    private companion object {
        const val MAX_BUMPS = 8
    }
}
