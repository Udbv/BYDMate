package com.bydmate.app.diagnostics

import android.content.Context
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background recording for the speed bump detector (#22): every drive, the sensor recorder runs
 * by itself over the few devices a bump shows in — the DiSus suspension's vertical accelerations,
 * the ADAS body pitch and accelerations, speed, wheel speeds — so ordinary drives collect the
 * bumps to tune the detector on, with nothing to press and nothing to mark. The recorder writes
 * its fast signals raw around every pitch or suspension event (its detail windows).
 *
 * Like the full trip log: a part every [CHUNK_MS] (stopped, zipped to the owner's bot, deleted on
 * the car once sent, recording goes on in a new file) and the last part a minute after the car
 * goes off. A recording someone started by hand (Settings) is never touched: it runs alone. On by
 * default in -dev builds only.
 */
@Singleton
class BumpRecording @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
    private val sender: LogTelegramSender,
    private val prompter: com.bydmate.app.data.roadpoints.BumpPrompter? = null,
) {
    internal var location: () -> android.location.Location? = {
        com.bydmate.app.service.TrackingService.lastLocation.value
    }
    private val prefs get() = context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)
    private val uploads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var startedAt = 0L
    // Not Long.MIN_VALUE: `now - MIN_VALUE` overflows to a negative gap and nothing would ever run.
    private var lastCheck: Long? = null
    private var lastStartTry: Long? = null
    private var offSince: Long? = null

    /** Every poll tick; the daemon is asked at most every [CHECK_MS]. */
    suspend fun onTick(carOn: Boolean, now: Long = System.currentTimeMillis()) {
        if (!isEnabled(context)) return
        prompter?.noteLocation(location())
        if (lastCheck?.let { now - it < CHECK_MS } == true) return
        lastCheck = now
        val own = prefs.getString(KEY_PATH, null)
        val status = helper.recStatus() ?: return
        if (carOn) prompter?.onBumps(status.bumps)
        val ours = if (status.running && own != null && status.filePath == own) own else null
        if (carOn) {
            offSince = null
            onCarOn(status.running, ours, now)
            return
        }
        val since = offSince ?: now.also { offSince = it }
        if (ours != null && now - since >= OFF_MS) finish(ours)
    }

    /** [ours]: the path of the recording running now when it is this class's, else null. */
    private suspend fun onCarOn(running: Boolean, ours: String?, now: Long) {
        when {
            running && ours == null -> return            // a recording by hand: left alone
            !running -> if (lastStartTry?.let { now - it < RETRY_MS } != true) start(now)
            ours != null && now - startedAt >= CHUNK_MS -> {
                finish(ours)
                start(now)
            }
        }
    }

    private suspend fun start(now: Long) {
        lastStartTry = now
        // A previous part that never reached the bot (the app or the daemon died with it) goes now.
        prefs.getString(KEY_PATH, null)?.let { prev ->
            if (prev != prefs.getString(KEY_SENT, null)) send(prev)
        }
        val started = helper.recStart(DEVICES) ?: return
        val path = helper.recStatus()?.takeIf { it.running }?.filePath?.takeIf { it.isNotEmpty() }
        if (started.registered == 0 || path == null) {
            Log.w(TAG, "not started: devices=${started.devices} registered=${started.registered}")
            return
        }
        startedAt = now
        prefs.edit().putString(KEY_PATH, path).apply()
        Log.i(TAG, "started ${File(path).name}: registered=${started.registered}")
    }

    private suspend fun finish(path: String) {
        helper.recStop()
        Log.i(TAG, "part done: ${File(path).name}")
        send(path)
    }

    /** Zipped to the bot; retried while another upload runs or the network is down. */
    private fun send(path: String) {
        uploads.launch {
            val file = File(path)
            repeat(SEND_ATTEMPTS) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(SEND_RETRY_MS)
                if (!file.isFile) return@launch
                val outcome = try {
                    sender.sendZipped(context, file)
                } catch (e: IOException) {
                    Log.w(TAG, "unreadable ${file.name}: ${e.javaClass.simpleName}")
                    return@launch
                }
                if (outcome != null && (outcome.sent || !outcome.transient)) {
                    prefs.edit().putString(KEY_SENT, path).apply()
                    // Tens of MB an hour: once at the bot, the car's copy only fills the storage.
                    if (outcome.sent) runCatching { file.delete() }
                    Log.i(TAG, "${file.name} to telegram: ${if (outcome.sent) "sent" else "failed for good"}")
                    return@launch
                }
            }
        }
    }

    companion object {
        private const val TAG = "BumpRec"
        const val KEY_ENABLED = "bump_recording"
        private const val KEY_PATH = "bump_rec_path"
        private const val KEY_SENT = "bump_rec_sent"

        /** Bodywork (DiSus), Speed, ADAS (pitch, accelerations), Sensor (pitch), Special (wheels). */
        val DEVICES = intArrayOf(1001, 1013, 1038, 1043, 1049)

        const val CHUNK_MS = 20 * 60 * 1000L
        /** Also how late a bump is offered at most: the daemon is asked this often while driving. */
        const val CHECK_MS = 3_000L
        const val OFF_MS = 60_000L
        const val RETRY_MS = 5 * 60 * 1000L
        private const val SEND_ATTEMPTS = 5
        private const val SEND_RETRY_MS = 60_000L

        val DEFAULT_ENABLED: Boolean = BuildConfig.DEFAULT_UPDATE_CHANNEL == "dev"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply()
        }
    }
}
