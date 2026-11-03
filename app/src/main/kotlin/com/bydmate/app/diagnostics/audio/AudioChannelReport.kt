package com.bydmate.app.diagnostics.audio

import android.media.AudioManager
import com.bydmate.app.voice.TtsEngine
import kotlin.math.PI
import kotlin.math.sin

/** What a focus request for one variant came back with. */
enum class FocusOutcome {
    /** The variant plays without asking for focus. */
    NOT_REQUESTED,
    GRANTED,
    FAILED,
    /** AUDIOFOCUS_REQUEST_DELAYED, and the GAIN callback came within the wait. */
    DELAYED_THEN_GRANTED,
    /** AUDIOFOCUS_REQUEST_DELAYED and no GAIN within the wait: the phrase plays anyway. */
    DELAYED_TIMEOUT,
}

/** One played variant, as the result list and the log show it. */
data class VariantResult(
    val variant: AudioChannelVariant,
    val focus: FocusOutcome,
    val played: Boolean,
    val durationMs: Long,
    val voice: String,
)

/** Pure helpers of the audio channel test: log line shapes, focus names and the tone fallback. */
object AudioChannelReport {

    /** Tone fallback when no local voice is downloaded: the variant number as short beeps, then
     *  a steady tone long enough for the owner to hear whether the music dips under it. */
    private const val TONE_RATE = 24_000
    private const val BEEP_HZ = 880.0
    private const val HOLD_HZ = 440.0
    private const val BEEP_MS = 150
    private const val HOLD_MS = 1_500
    private const val TONE_GAIN = 0.4f

    fun resultLine(r: VariantResult): String =
        "${r.variant.key}: focus=${r.focus.name.lowercase()} played=${r.played} ${r.durationMs}ms voice=${r.voice}"

    /** AudioManager.AUDIOFOCUS_* change codes as names for the log. */
    fun focusChangeName(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "gain_transient"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "gain_transient_may_duck"
        AudioManager.AUDIOFOCUS_LOSS -> "loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_transient_can_duck"
        else -> "change($change)"
    }

    /** [count] beeps of [BEEP_MS] with equal gaps, then [HOLD_MS] of steady tone. */
    fun tone(count: Int): TtsEngine.Pcm {
        val beep = samplesFor(BEEP_MS)
        val hold = samplesFor(HOLD_MS)
        val out = FloatArray(count * beep * 2 + hold)
        var at = 0
        repeat(count) {
            for (i in 0 until beep) out[at + i] = wave(BEEP_HZ, i)
            at += beep * 2
        }
        for (i in 0 until hold) out[at + i] = wave(HOLD_HZ, i)
        return TtsEngine.Pcm(out, TONE_RATE)
    }

    /** Playback length of [pcm] in ms. */
    fun durationMs(pcm: TtsEngine.Pcm): Long =
        if (pcm.sampleRate <= 0) 0L else pcm.samples.size * MS_PER_S / pcm.sampleRate

    private fun samplesFor(ms: Int): Int = (TONE_RATE.toLong() * ms / MS_PER_S).toInt()

    private fun wave(hz: Double, i: Int): Float = (sin(2 * PI * hz * i / TONE_RATE) * TONE_GAIN).toFloat()

    private const val MS_PER_S = 1000L
}
