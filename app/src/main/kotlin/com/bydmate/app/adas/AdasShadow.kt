package com.bydmate.app.adas

import android.content.Context
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.navdata.NavGuidanceHub
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADAS shadow mode (issue #23): while the car is on, builds the messages a Waze source would offer
 * the driving computer and logs them, never sending anything, next to what the car's own
 * assistance reports. Every [TICK_MS] it reads the camera's sign, the speed-limit assist, the
 * cruise and lane assist states ([AdasCarReading], read only) and counts how Waze's limit and the
 * camera's agree ([AdasShadowStats]); the drive's summary is logged when the car goes off.
 *
 * Log volume: a frame only when its bytes change, a reading only when it changes, and one
 * heartbeat line a minute. The lines land in the automatic trip log under [TAG].
 */
@Singleton
class AdasShadow @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
) {
    private var lastTickMs = 0L
    private var lastHeartbeatMs = 0L
    private var lastFrames = emptyMap<Long, ShadowFrame>()
    private var lastReading: AdasCarReading? = null
    private var stats = AdasShadowStats()
    private var episodes = newEpisodes()
    private var driving = false

    suspend fun onTick(carOn: Boolean, data: DiParsData?, nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled(context)) return
        if (!carOn || data == null) {
            finish()
            return
        }
        if (nowMs - lastTickMs < TICK_MS) return
        lastTickMs = nowMs
        driving = true
        val snap = NavGuidanceHub.snapshot(nowMs)
        logFrames(snap)
        val reading = readCar(snap, data)
        if ((data.speed ?: 0) > 0) stats.add(wazeLimit(snap), reading?.carLimitKph)
        episodes.add(nowMs, data.speed, wazeLimit(snap), reading?.carLimitKph)
        if (nowMs - lastHeartbeatMs >= HEARTBEAT_MS) {
            lastHeartbeatMs = nowMs
            Log.i(TAG, "alive route=${snap.active} waze=${wazeLimit(snap) ?: "-"} car=${reading?.describe() ?: "no answer"} ${stats.summary()} ${episodes.summary()}")
        }
    }

    /** The car's own view, read only; logged when it changes. Null when the car did not answer. */
    private suspend fun readCar(snap: NavGuidanceHub.Snapshot, data: DiParsData): AdasCarReading? {
        val reading = helper.readBatch(AdasCarReading.ITEMS)?.let { AdasCarReading.decode(it) } ?: return null
        if (reading != lastReading) {
            Log.i(TAG, "car ${reading.describe()} waze=${wazeLimit(snap) ?: "-"} speed=${data.speed ?: "-"}")
            lastReading = reading
        }
        return reading
    }

    /** A frame is logged when its bytes change: the log shows what would go out, not every tick. */
    private fun logFrames(snap: NavGuidanceHub.Snapshot) {
        for (frame in AdasShadowFrames.of(snap)) {
            if (lastFrames[frame.topic] != frame) {
                Log.i(TAG, "would send 0x%016x %s {%s} bytes=%s".format(frame.topic, frame.name, frame.fields, frame.hex))
            }
            lastFrames = lastFrames + (frame.topic to frame)
        }
    }

    /** The drive is over: its comparison, then a clean slate for the next. */
    private fun finish() {
        if (!driving) return
        driving = false
        episodes.finish(System.currentTimeMillis())
        Log.i(TAG, "drive summary ${stats.summary()} ${episodes.summary()}")
        TripDebugLog.event("ADAS", "drive summary ${stats.summary()} ${episodes.summary()}")
        stats = AdasShadowStats()
        episodes = newEpisodes()
        lastFrames = emptyMap()
        lastReading = null
        lastHeartbeatMs = 0L
    }

    private fun newEpisodes() = AdasLimitEpisodes { line ->
        Log.i(TAG, line)
        TripDebugLog.event("ADAS", line)
    }

    private fun wazeLimit(snap: NavGuidanceHub.Snapshot): Int? = snap.speedLimit.takeIf { it > 0 }

    companion object {
        const val TAG = "AdasShadow"
        const val TICK_MS = 2_000L
        const val HEARTBEAT_MS = 60_000L

        /** On by default in -dev builds only; a stable build logs nothing of this unless asked. */
        val DEFAULT_ENABLED: Boolean = com.bydmate.app.BuildConfig.DEFAULT_UPDATE_CHANNEL == "dev"

        private const val PREFS = "adas_shadow"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        }
    }
}
