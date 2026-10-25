package com.bydmate.app.diagnostics

import android.content.Context
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Повний лог кожної поїздки»: the same logcat recording as the Settings button, started by itself
 * when the car comes on and finished when it goes off, then sent to the owner's bot. On by
 * default on the development channel, where the drive nobody watched is the one that matters;
 * off on stable. A stored choice wins either way.
 *
 * - Car on for [START_DELAY_MS] (the startup resume of a button recording goes first) and nothing
 *   recording: a recording starts and is remembered as this class's own.
 * - Car off for [OFF_MS] with that recording running: it stops, and the file goes to the bot.
 * - The car usually kills the process sooner. Then at the next start the old file is neither
 *   resumed into the new trip nor lost: [onServiceStart] finishes it and sends it.
 * - The 2 h auto-stop ends a long trip's file; a new one starts. A stop by the owner keeps it off
 *   until the car goes off.
 */
@Singleton
class AutoTripLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: LogRecorder,
    private val sender: LogTelegramSender,
    private val settings: SettingsRepository,
) {
    private val prefs get() = context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)

    private var serviceStartedAt = 0L
    private var offSince: Long? = null
    private var stoppedByOwner = false
    private var watchedPath: String? = null
    private var watchedSince = 0L

    /** Uploads wait for the modem and retry for minutes: never on the poll loop. */
    private val uploads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * At service start, before the resume of a button recording: this class's own file from the
     * last trip is finished, not resumed into the new trip, and goes to the bot once the modem is
     * up. True when it took the pending recording (the caller then skips the resume).
     */
    suspend fun onServiceStart(now: Long = System.currentTimeMillis()): Boolean {
        serviceStartedAt = now
        val own = prefs.getString(KEY_PATH, null) ?: return false
        val tookPending = recorder.pendingPath() == own
        // No session runs this early, so this only drops the pending record.
        if (tookPending) recorder.stop()
        if (prefs.getString(KEY_SENT, null) != own) uploads.launch { send(File(own), waitForNetwork = true) }
        return tookPending
    }

    /** Every poll tick. */
    suspend fun onTick(carOn: Boolean, now: Long = System.currentTimeMillis()) {
        if (!isEnabled(context)) return
        val state = recorder.state.value
        val own = prefs.getString(KEY_PATH, null)
        noteOwnerStop(state, own, carOn, now)
        if (carOn) {
            offSince = null
            if (!state.isRecording && !stoppedByOwner && now - serviceStartedAt >= START_DELAY_MS) start()
            return
        }
        val since = offSince ?: now.also { offSince = it }
        if (now - since < OFF_MS) return
        stoppedByOwner = false
        if (state.isRecording && state.filePath == own) {
            Log.i(TAG, "car off: finishing $own")
            recorder.stop()
            own?.let { uploads.launch { send(File(it), waitForNetwork = false) } }
        }
    }

    /** Our recording ended while the car is on and well short of the 2 h auto-stop: the owner stopped it. */
    private fun noteOwnerStop(state: LogRecorder.State, own: String?, carOn: Boolean, now: Long) {
        val watched = watchedPath
        if (state.isRecording && state.filePath == own) {
            watchedPath = own
            watchedSince = state.startedAtMs
            return
        }
        if (watched == null) return
        watchedPath = null
        if (carOn && now - watchedSince < AUTO_STOP_MS - AUTO_STOP_SLACK_MS) {
            stoppedByOwner = true
            Log.i(TAG, "stopped by the owner: no new recording until the car goes off")
        }
    }

    private suspend fun start() {
        val result = recorder.start { file ->
            file.writeText(
                "BYDMate ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}): automatic trip log, " +
                    "started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n",
            )
        }
        if (result is LogRecorder.StartResult.Started) {
            prefs.edit().putString(KEY_PATH, result.file.absolutePath).apply()
            watchedPath = result.file.absolutePath
            watchedSince = recorder.state.value.startedAtMs
            Log.i(TAG, "started ${result.file.name}")
        } else {
            Log.w(TAG, "not started: $result")
        }
    }

    /** One file to the bot, retried while the failure may heal; remembered once sent or hopeless. */
    private suspend fun send(file: File, waitForNetwork: Boolean) {
        if (!settings.getTgBackupConfig().configured || !file.isFile) return
        repeat(ATTEMPTS) { attempt ->
            if (waitForNetwork || attempt > 0) delay(if (attempt == 0) SETTLE_MS else RETRY_MS)
            val outcome = try {
                sender.sendZipped(context, file)
            } catch (e: IOException) {
                Log.w(TAG, "unreadable ${file.name}: ${e.javaClass.simpleName}")
                return
            }
            if (outcome != null && (outcome.sent || !outcome.transient)) {
                prefs.edit().putString(KEY_SENT, file.absolutePath).apply()
                Log.i(TAG, "${file.name} to telegram: ${if (outcome.sent) "sent" else "failed for good"}")
                return
            }
        }
    }

    companion object {
        private const val TAG = "AutoTripLog"
        const val KEY_ENABLED = "full_log_auto"
        private const val KEY_PATH = "full_log_auto_path"
        private const val KEY_SENT = "full_log_auto_sent"

        val DEFAULT_ENABLED: Boolean = BuildConfig.DEFAULT_UPDATE_CHANNEL == "dev"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, on).apply()
        }

        const val START_DELAY_MS = 45_000L
        const val OFF_MS = 60_000L
        private const val AUTO_STOP_MS = 2 * 60 * 60 * 1000L
        private const val AUTO_STOP_SLACK_MS = 60_000L
        private const val SETTLE_MS = 60_000L
        private const val RETRY_MS = 120_000L
        private const val ATTEMPTS = 3
    }
}
