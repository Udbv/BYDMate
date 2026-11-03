package com.bydmate.app.diagnostics.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.voice.TtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Тест аудиоканалов» (issue #6 part B, the measurement step): the same short phrase under each
 * usage of [AudioChannelVariant.all], once with a transient may-duck focus request and once with
 * none, while the owner's music keeps playing. The owner notes by ear which variants dip the music;
 * the log keeps the matching focus answers, start/end times and, through the helper daemon, the
 * `dumpsys audio` focus stack before the run and during every focused phrase. That is what tells
 * whether Waze guidance can duck music by usage alone or must hold focus like the factory navigation.
 */
@Singleton
class AudioChannelTest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tts: TtsEngine,
    private val helper: HelperClient,
) {
    sealed interface State {
        data object Idle : State
        data class Running(val step: Int, val total: Int, val variant: AudioChannelVariant) : State
        data class Done(val lines: List<String>) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var log: (String) -> Unit = {
        Log.i(TAG, it)
        TripDebugLog.event(TAG, it)
    }
    internal var io: AudioChannelIo? = null
    internal var gapMs = GAP_MS
    internal var dumpDelayMs = DUMP_DELAY_MS
    internal var nowMs: () -> Long = { SystemClock.elapsedRealtime() }

    private var job: Job? = null

    /** Starts a run unless one is going; progress and the result land in [state]. */
    fun start() {
        if (job?.isActive == true) return
        val device = io ?: AndroidAudioChannelIo(context, tts, helper) { log(it) }
        job = scope.launch {
            var lines: List<String>? = null
            try {
                lines = run(device)
            } finally {
                // A stop() mid-phrase must not leave the music ducked behind a held request.
                // Released before Done is published, so whoever sees Done sees no focus held.
                device.abandonFocus()
                _state.value = lines?.let { State.Done(it) } ?: State.Idle
            }
        }
    }

    /** Cancels a run; the phrase in flight is cut and any held focus given back. */
    fun stop() {
        job?.cancel()
        log("stopped by user")
    }

    private suspend fun run(device: AudioChannelIo): List<String> {
        val variants = AudioChannelVariant.all()
        log("start variants=${variants.size} voice_ready=${tts.isReady()}")
        val before = device.focusDump()
        logDump("before", before)
        val results = variants.mapIndexed { i, variant ->
            _state.value = State.Running(i + 1, variants.size, variant)
            playOne(device, i + 1, variant).also {
                log(AudioChannelReport.resultLine(it))
                delay(gapMs)
            }
        }
        val dumpNote = if (before == null) "focus dump: helper daemon unavailable" else "focus dump: see log"
        log("done; $dumpNote")
        return results.map(AudioChannelReport::resultLine) + dumpNote
    }

    private suspend fun playOne(device: AudioChannelIo, number: Int, variant: AudioChannelVariant): VariantResult {
        val synthesized = withContext(Dispatchers.IO) { device.synthesize(device.phrase(variant)) }
        val pcm = synthesized ?: AudioChannelReport.tone(number)
        val voice = if (synthesized != null) "tts" else "tone"
        val focus = if (variant.duck) {
            withContext(Dispatchers.IO) { device.requestFocus(variant.channel.usage) }
        } else {
            FocusOutcome.NOT_REQUESTED
        }
        log("${variant.key}: focus=${focus.name.lowercase()} start")
        val startedAt = nowMs()
        val played = coroutineScope {
            // Mid-phrase, while the request is live: the stack shows whether the music player
            // was moved to "loss: transient_can_duck" or left alone.
            val during = if (variant.duck) async { delay(dumpDelayMs); device.focusDump() } else null
            val ok = runInterruptible(Dispatchers.IO) { device.play(pcm, variant.channel.usage) }
            during?.await()?.let { logDump("during ${variant.key}", it) }
            ok
        }
        val duration = nowMs() - startedAt
        log("${variant.key}: end played=$played ${duration}ms")
        if (variant.duck) device.abandonFocus()
        return VariantResult(variant, focus, played, duration, voice)
    }

    private fun logDump(label: String, lines: List<String>?) {
        if (lines == null) {
            log("$label: focus dump unavailable (helper daemon not reachable)")
            return
        }
        lines.forEach { log("$label: $it") }
    }

    companion object {
        /** Logcat tag; listed in LogRecorder so a recorded log keeps the run. */
        const val TAG = "AudioChannelTest"
        private const val GAP_MS = 1_500L
        private const val DUMP_DELAY_MS = 700L
    }
}
