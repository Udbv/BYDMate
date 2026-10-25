package com.bydmate.app.diagnostics

import android.content.Context
import android.util.Log
import com.bydmate.app.R
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.settings.telegramErrorText
import com.bydmate.app.util.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.bydmate.app.helper.push.FidRecLog
import com.bydmate.app.helper.push.latestFidRecording
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a saved log to the user's bot (#324): on the Settings button, and by itself when a
 * recording ends for good and «Отправлять записанный лог в Telegram» is on. The FID dump and the
 * trip logs go the same way through [sendQuietly], so a file leaves the car without a cable. App-scoped: the 2h
 * auto-stop may come while Settings is closed. One attempt per file, no retry queue.
 *
 * Log tag `TgReport` (already in the recorded tags); sizes and verdicts only, never the token.
 */
@Singleton
class LogTelegramSender @Inject constructor(
    private val recorder: LogRecorder,
    private val settings: SettingsRepository,
    private val sink: TelegramBackupSink,
    private val strings: AppStrings,
) {
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    // Every status line of an upload, for an open Settings screen; nothing is replayed to a later one.
    private val _results = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val results: SharedFlow<String> = _results.asSharedFlow()

    // The last finished file the automatic path took up; on the singleton, so a service restarted
    // in the same process does not send it again.
    @Volatile
    private var handledPath: String? = null

    /** The automatic path, for the service's lifetime. */
    suspend fun run() {
        recorder.state.collect { state ->
            val stopped = state.lastStopped?.takeIf { it.finished } ?: return@collect
            if (stopped.path == handledPath) return@collect
            handledPath = stopped.path
            if (!settings.isTgLogAutoSendEnabled()) return@collect
            if (!settings.getTgBackupConfig().configured) {
                Log.i(TAG, "log to telegram: auto skipped, no bot")
                return@collect
            }
            Log.i(TAG, "log to telegram: auto")
            send(File(stopped.path))
        }
    }

    /** One upload of [file]; a call while another upload runs does nothing. */
    suspend fun send(file: File) {
        if (!_sending.compareAndSet(expect = false, update = true)) return
        try {
            _results.tryEmit(strings.get(R.string.settings_log_tg_sending))
            _results.tryEmit(upload(file))
        } finally {
            _sending.value = false
        }
    }

    /**
     * One upload of [file] for a caller that shows its own status line (the FID dump, a trip log):
     * nothing reaches [results]. Null when another upload is already running.
     */
    suspend fun sendQuietly(file: File): String? {
        if (!_sending.compareAndSet(expect = false, update = true)) return null
        try {
            return upload(file)
        } finally {
            _sending.value = false
        }
    }

    /**
     * At the start of a trip: the previous trip's log, once, when «…після поїздки» is on and a bot
     * is bound. The car's modem may not be up yet when the service starts, so the first try waits
     * and a failure that may heal (no network, a server error) is tried again.
     */
    suspend fun sendFinishedTripLog(context: Context) {
        if (!TripDebugLog.isAutoSendEnabled(context)) return
        if (!settings.getTgBackupConfig().configured) return
        val file = withContext(Dispatchers.IO) {
            TripDebugLog.files(context).firstOrNull { it != TripDebugLog.currentFile }
        } ?: return
        if (TripDebugLog.lastSent(context) == file.name) return
        repeat(TRIP_ATTEMPTS) { attempt ->
            delay(if (attempt == 0) TRIP_SETTLE_MS else TRIP_RETRY_MS)
            if (!_sending.compareAndSet(expect = false, update = true)) return@repeat
            val outcome = try {
                uploadOutcome(file)
            } finally {
                _sending.value = false
            }
            if (outcome.sent) {
                TripDebugLog.markSent(context, file.name)
                return
            }
            if (!outcome.transient) return
        }
    }

    /**
     * One sensor recording to the bot, zipped (the text shrinks ~10x and Telegram takes 50 MB),
     * pinned like every diagnostic file, and remembered so [sendUnsentRecording] skips it. Null
     * while another upload runs. Throws IOException when [file] cannot be read.
     */
    suspend fun sendRecording(context: Context, file: File): String? = recordingOutcome(context, file)?.status

    /**
     * At service start: the newest sensor recording when neither the switch nor the button sent it.
     * On the Tang L the car went off with the recorder running twice, and the upload on stop never
     * ran. [recording] says whether the daemon still writes that file (the service restarted with
     * the car on); such a file is left alone. Same modem wait and retries as the trip log.
     */
    suspend fun sendUnsentRecording(
        context: Context,
        dir: File = File(FidRecLog.DIR),
        recording: suspend (path: String) -> Boolean,
    ) {
        if (!settings.getTgBackupConfig().configured) return
        val file = withContext(Dispatchers.IO) { latestFidRecording(dir) } ?: return
        if (lastSentRecording(context) == file.name) return
        repeat(TRIP_ATTEMPTS) { attempt ->
            delay(if (attempt == 0) TRIP_SETTLE_MS else TRIP_RETRY_MS)
            if (recording(file.path)) return
            val outcome = try {
                recordingOutcome(context, file)
            } catch (e: IOException) {
                Log.w(TAG, "recording to telegram: unreadable ${e.javaClass.simpleName}")
                return
            } ?: return@repeat
            if (outcome.sent || !outcome.transient) return
        }
    }

    private suspend fun recordingOutcome(context: Context, file: File): Outcome? {
        val zip = withContext(Dispatchers.IO) { zipForUpload(file, context.cacheDir) }
        try {
            if (!_sending.compareAndSet(expect = false, update = true)) return null
            val outcome = try {
                uploadOutcome(zip)
            } finally {
                _sending.value = false
            }
            // A permanent failure (too big) is not retried at every start either.
            if (outcome.sent || !outcome.transient) markRecordingSent(context, file.name)
            return outcome
        } finally {
            withContext(Dispatchers.IO) { zip.delete() }
        }
    }

    private suspend fun upload(file: File): String = uploadOutcome(file).status

    private class Outcome(val status: String, val sent: Boolean = false, val transient: Boolean = false)

    private suspend fun uploadOutcome(file: File): Outcome {
        val config = settings.getTgBackupConfig()
        // The button shows only with a bound bot; this covers a disconnect racing the tap.
        val chatId = config.chatId ?: return Outcome(strings.get(R.string.settings_log_tg_failed, "?"))
        val size = if (file.isFile) file.length() else -1L
        if (size > TelegramBackupSink.MAX_UPLOAD_BYTES) {
            Log.w(TAG, "log to telegram: too big ${size / 1024} KB")
            return Outcome(strings.get(R.string.settings_log_tg_too_big))
        }
        if (size < 0) {
            Log.w(TAG, "log to telegram: file missing")
            return Outcome(strings.get(R.string.settings_log_tg_missing))
        }
        return sink.sendDocument(config.token, chatId, file, file.name, pin = true).fold(
            onSuccess = {
                Log.i(TAG, "log to telegram: sent ${size / 1024} KB")
                Outcome(strings.get(R.string.settings_log_tg_sent), sent = true)
            },
            onFailure = { error ->
                val failure = error as? TelegramSinkException
                Log.w(TAG, "log to telegram: failed ${failure?.key ?: error.javaClass.simpleName}")
                Outcome(
                    strings.get(
                        R.string.settings_log_tg_failed,
                        failure?.let { telegramErrorText(strings.context, it.key) } ?: "?",
                    ),
                    transient = failure?.transient ?: true,
                )
            },
        )
    }

    private companion object {
        const val TAG = "TgReport"
        const val TRIP_SETTLE_MS = 60_000L
        const val TRIP_RETRY_MS = 120_000L
        const val TRIP_ATTEMPTS = 3
    }
}

/** Name of the last sensor recording that went to the bot (or failed for good). */
private const val KEY_RECORDING_SENT = "fidrec_tg_sent"

private fun recordingPrefs(context: Context) =
    context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)

private fun lastSentRecording(context: Context): String? =
    recordingPrefs(context).getString(KEY_RECORDING_SENT, null)

private fun markRecordingSent(context: Context, name: String) {
    recordingPrefs(context).edit().putString(KEY_RECORDING_SENT, name).apply()
}

/**
 * [source] packed into `<name>.zip` in [dir] for an upload; an existing zip of that name is
 * replaced. Throws IOException when [source] cannot be read (gone, or no access).
 */
internal fun zipForUpload(source: File, dir: File): File {
    val zip = File(dir, source.nameWithoutExtension + ".zip")
    try {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry(source.name))
            source.inputStream().use { it.copyTo(out) }
            out.closeEntry()
        }
    } catch (e: java.io.IOException) {
        zip.delete()
        throw e
    }
    return zip
}
