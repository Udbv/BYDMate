package com.bydmate.app.navvoice

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.diagnostics.audio.AndroidAudioChannelIo
import com.bydmate.app.diagnostics.audio.AudioChannel
import com.bydmate.app.diagnostics.audio.AudioChannelIo
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.voice.TtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BYDMate's own voice for Waze guidance. On the owner's DiLink Waze's prompts pause the music
 * instead of ducking it, and Waze cannot be changed; with Waze's voice off, BYDMate says the
 * maneuvers it already reads ([NavGuidanceHub]) in the local voice, on the AudioAttributes usage
 * the owner picks and with or without a may-duck focus request: which pair ducks the music on
 * this car is what the audio channel test measures. Every [PERIOD_MS] while the car is on and the
 * switch is on; [NavVoicePlanner] decides when. Nothing is written to the car.
 */
@Singleton
class NavVoice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tts: TtsEngine,
    private val helper: HelperClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val planner = NavVoicePlanner()
    private val speaking = AtomicBoolean(false)
    private var job: Job? = null
    private var noVoiceLogged = false

    internal var io: AudioChannelIo? = null

    /** From the tracking service's tick: runs the loop while the car is on and the switch is on. */
    fun onTick(carOn: Boolean) {
        if (!carOn || !NavVoiceSettings.isEnabled(context)) {
            job?.cancel()
            return
        }
        if (job?.isActive != true) job = scope.launch { loop() }
    }

    private suspend fun loop() {
        planner.reset()
        while (scope.isActive && NavVoiceSettings.isEnabled(context)) {
            planner.lang = selectedVoiceLang()
            val snap = NavGuidanceHub.snapshot()
            val input = NavVoiceInput(snap.active, snap.maneuverGaode, snap.distanceMeters, snap.road, snap.exitNumber)
            planner.next(input, System.currentTimeMillis())?.let { phrase ->
                if (speaking.compareAndSet(false, true)) scope.launch { speak(phrase) } else note("busy, dropped: $phrase")
            }
            delay(PERIOD_MS)
        }
    }

    private fun speak(phrase: String) {
        try {
            val channel = NavVoiceSettings.channel(context)
            val duck = NavVoiceSettings.duck(context)
            val io = io ?: AndroidAudioChannelIo(context, tts, helper) { Log.i(TAG, it) }.also { io = it }
            val pcm = io.synthesize(phrase)
            if (pcm == null) {
                if (!noVoiceLogged) note("no local voice ready: nothing said")
                noVoiceLogged = true
                return
            }
            noVoiceLogged = false
            val focus = if (duck) io.requestFocus(channel.usage).name else "none"
            val played = io.play(pcm, channel.usage)
            if (duck) io.abandonFocus()
            note("said \"$phrase\" on ${channel.key} focus=$focus played=$played")
        } finally {
            speaking.set(false)
        }
    }

    /** The language of the voice chosen in the voice settings (prefs "voice"/"tts_voice"). */
    private fun selectedVoiceLang(): com.bydmate.app.voice.TtsLang = com.bydmate.app.voice.TtsVoiceCatalog.byId(
        context.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .getString("tts_voice", com.bydmate.app.voice.TtsModelManager.DEFAULT_VOICE_ID)
            ?: com.bydmate.app.voice.TtsModelManager.DEFAULT_VOICE_ID,
    ).lang

    private fun note(line: String) {
        Log.i(TAG, line)
        TripDebugLog.event("NAVVOICE", line)
    }

    companion object {
        const val TAG = "NavVoice"
        const val PERIOD_MS = 500L
    }
}

/** The switch and the channel of [NavVoice]; off by default, navigation usage with ducking. */
object NavVoiceSettings {
    private const val PREFS = "nav_voice"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_DUCK = "duck"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        TripDebugLog.event("NAVVOICE", if (on) "switched on" else "switched off")
    }

    fun channel(context: Context): AudioChannel =
        prefs(context).getString(KEY_CHANNEL, null)
            ?.let { key -> AudioChannel.entries.firstOrNull { it.key == key } }
            ?: AudioChannel.NAVIGATION

    fun setChannel(context: Context, channel: AudioChannel) {
        prefs(context).edit().putString(KEY_CHANNEL, channel.key).apply()
    }

    fun duck(context: Context): Boolean = prefs(context).getBoolean(KEY_DUCK, true)

    fun setDuck(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_DUCK, on).apply()
    }
}
