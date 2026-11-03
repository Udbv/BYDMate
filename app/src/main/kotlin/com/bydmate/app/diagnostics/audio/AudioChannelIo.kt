package com.bydmate.app.diagnostics.audio

import com.bydmate.app.voice.TtsEngine

/** The device side of the audio channel test, behind an interface so the sequencing in
 *  [AudioChannelTest] is unit-testable without AudioManager, AudioTrack or a voice model. */
interface AudioChannelIo {
    /** The spoken phrase for [variant], in the app's language. */
    fun phrase(variant: AudioChannelVariant): String

    /** The phrase as PCM from the local voice; null when no voice is ready. Blocking. */
    fun synthesize(text: String): TtsEngine.Pcm?

    /** Asks for AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK under [usage]. Blocking (a delayed grant is
     *  waited for briefly). */
    fun requestFocus(usage: Int): FocusOutcome

    /** Gives back whatever [requestFocus] took; no-op when nothing is held. */
    fun abandonFocus()

    /** Plays [pcm] on a fresh track under [usage] and returns once it has sounded. Blocking and
     *  interruptible; true when every sample was written. */
    fun play(pcm: TtsEngine.Pcm, usage: Int): Boolean

    /** `dumpsys audio` focus excerpt read by the helper daemon; null when the daemon is unreachable. */
    suspend fun focusDump(): List<String>?
}
