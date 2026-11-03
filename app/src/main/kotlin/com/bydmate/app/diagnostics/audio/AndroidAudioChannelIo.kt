package com.bydmate.app.diagnostics.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bydmate.app.R
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.voice.TtsEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [AudioChannelIo] on the real device: the app's local voice for the phrase, AudioManager for the
 * focus request and a throwaway AudioTrack per variant, so each phrase carries exactly the usage
 * under test (the voice engine's own shared track has one fixed usage). [log] receives the focus
 * change callbacks, which say whether the framework told the requester anything mid-phrase.
 */
class AndroidAudioChannelIo(
    private val context: Context,
    private val tts: TtsEngine,
    private val helper: HelperClient,
    private val log: (String) -> Unit,
) : AudioChannelIo {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var held: AudioFocusRequest? = null

    override fun phrase(variant: AudioChannelVariant): String {
        val res = context.appLocalizedContext()
        return res.getString(
            R.string.audio_test_phrase,
            res.getString(variant.channel.label),
            res.getString(variant.modeLabel),
        )
    }

    override fun synthesize(text: String): TtsEngine.Pcm? = tts.synthesizePcm(text, SYNTH_TIMEOUT_MS)

    override fun requestFocus(usage: Int): FocusOutcome {
        val gained = CountDownLatch(1)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes(usage))
            // Delayed grants are accepted so a DELAYED answer is observable at all; without
            // this flag the framework turns that case into a plain FAILED.
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change ->
                log("focus change: ${AudioChannelReport.focusChangeName(change)}")
                if (change == AudioManager.AUDIOFOCUS_GAIN) gained.countDown()
            }, handler)
            .build()
        held = request
        return when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> FocusOutcome.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED ->
                if (gained.await(DELAYED_WAIT_MS, TimeUnit.MILLISECONDS)) FocusOutcome.DELAYED_THEN_GRANTED
                else FocusOutcome.DELAYED_TIMEOUT
            else -> FocusOutcome.FAILED
        }
    }

    override fun abandonFocus() {
        val request = held ?: return
        held = null
        audioManager.abandonAudioFocusRequest(request)
    }

    override fun play(pcm: TtsEngine.Pcm, usage: Int): Boolean {
        val track = buildTrack(pcm.sampleRate, usage) ?: return false
        try {
            val startedAt = SystemClock.elapsedRealtime()
            track.play()
            val written = track.write(pcm.samples, 0, pcm.samples.size, AudioTrack.WRITE_BLOCKING)
            // Wall clock, not playbackHeadPosition: the DiLink HAL reports garbage head
            // positions on some tracks (see SherpaTtsEngine), and a short tail cut is harmless.
            val remaining = AudioChannelReport.durationMs(pcm) + TAIL_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) Thread.sleep(remaining)
            return written == pcm.samples.size
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    override suspend fun focusDump(): List<String>? = helper.audioFocusDiag()

    private fun buildTrack(sampleRate: Int, usage: Int): AudioTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes(usage))
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, MIN_BUFFER_BYTES))
                .build()
                .let { track ->
                    if (track.state == AudioTrack.STATE_INITIALIZED) track
                    else null.also { track.release(); log("track not initialized for usage=$usage") }
                }
        } catch (e: UnsupportedOperationException) {
            log("track refused for usage=$usage: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            log("track refused for usage=$usage: ${e.message}")
            null
        }
    }

    private fun attributes(usage: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private companion object {
        const val SYNTH_TIMEOUT_MS = 15_000L
        const val DELAYED_WAIT_MS = 3_000L
        const val TAIL_MS = 300L
        const val MIN_BUFFER_BYTES = 32 * 1024
    }
}
