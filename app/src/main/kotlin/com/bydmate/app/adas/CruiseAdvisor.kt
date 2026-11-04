package com.bydmate.app.adas

import android.content.Context
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.gnss.GnssGuard
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.voice.VoiceAutomationActions
import com.bydmate.app.voice.VoiceController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cruise advice (#23, safe class): while driving with the adaptive cruise on, says
 * "Ограничение 50, круиз 70" when Waze's limit drops below the set speed. It only reads the car
 * (the ACC mode and set speed, the same ids as [AdasCarReading]) and speaks; nothing goes to the
 * driving systems. Runs on its own every [TICK_MS] rather than inside [AdasShadow], so it works
 * with the shadow log off and costs a two-item read, not the shadow's seven. Off by default.
 *
 * Speech goes through [VoiceAutomationActions.speak]: it ducks the music and drops the phrase
 * when the voice assistant or its speech is off or a dialog is live. It suspends until playback
 * ends, so it runs in [scope] and never holds the tracking tick.
 */
@Singleton
class CruiseAdvisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
    private val voiceActions: dagger.Lazy<VoiceAutomationActions>,
    private val voiceController: dagger.Lazy<VoiceController>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rule = CruiseAdviceRule()
    private var speakJob: Job? = null
    private var lastTickMs = 0L

    suspend fun onTick(carOn: Boolean, data: DiParsData?, nowMs: Long = System.currentTimeMillis()) {
        if (!carOn || data == null || !isEnabled(context)) {
            rule.reset()
            return
        }
        if ((data.speed ?: 0) <= 0 || nowMs - lastTickMs < TICK_MS) return
        lastTickMs = nowMs
        // No advice from a limit read at an implausible position (GPS jamming or spoofing).
        val waze = GnssGuard.withheld(NavGuidanceHub.snapshot(nowMs)).speedLimit.takeIf { it > 0 } ?: return
        val car = helper.readBatch(ITEMS)?.let(::decode) ?: return
        val busy = speakJob?.isActive == true || voiceController.get().sessionActive()
        val advice = rule.decide(nowMs, car, waze, busy) ?: return
        val line = "ADAS cruise advice limit=${advice.limitKph} cruise=${advice.cruiseKph} acc=${car.accMode ?: "-"}"
        Log.i(TAG, line)
        TripDebugLog.event("ADAS", line)
        speakJob = scope.launch {
            val result = voiceActions.get().speak(advice.phrase)
            if (!result.success) {
                val why = "ADAS cruise advice not spoken: ${result.reason ?: "-"}"
                Log.i(TAG, why)
                TripDebugLog.event("ADAS", why)
            }
        }
    }

    companion object {
        const val TAG = "CruiseAdvisor"
        const val TICK_MS = 2_000L

        private const val TX_INT = 5
        private const val DEV_INSTRUMENT = 1007
        private const val DEV_ADAS = 1038
        private const val MAX_KPH = 200

        /** ADAS_ACC_MODE and INSTRUMENT_ACC_CRUISE_SPEED, as in [AdasCarReading.ITEMS]; read only. */
        internal val ITEMS: List<BatchReadItem> = listOf(
            BatchReadItem(TX_INT, DEV_ADAS, 852492340),
            BatchReadItem(TX_INT, DEV_INSTRUMENT, 597688344),
        )

        /** The two replies of [ITEMS] as a reading with only the cruise filled in. */
        internal fun decode(replies: List<Pair<Int, Int>>): AdasCarReading {
            fun value(i: Int): Int? = replies.getOrNull(i)?.takeIf { it.first == 0 }?.second
            return AdasCarReading(accMode = value(0), cruiseKph = value(1)?.takeIf { it in 1..MAX_KPH })
        }

        private const val PREFS = "adas_cruise_advice"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }
    }
}
