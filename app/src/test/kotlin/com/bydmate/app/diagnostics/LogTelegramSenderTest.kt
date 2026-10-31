package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TgBackupConfig
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The automatic upload of a finished log recording to the user's bot (#324). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LogTelegramSenderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val recorderState = MutableStateFlow(LogRecorder.State())
    private val recorder = mockk<LogRecorder> { every { state } returns recorderState }
    private val settings = mockk<SettingsRepository>()
    private val sink = mockk<TelegramBackupSink>()
    private lateinit var sender: LogTelegramSender

    private var autoSend = true
    private var config = TgBackupConfig(token = "123:abc", chatId = 777L, botName = "my_car_bot", chatName = "Andy")

    @Before fun setUp() {
        coEvery { settings.isTgLogAutoSendEnabled() } answers { autoSend }
        coEvery { settings.getTgBackupConfig() } answers { config }
        coEvery { sink.sendDocument(any(), any(), any(), any(), true) } returns Result.success(Unit)
        TripDebugLog.setAutoSendEnabled(ctx, false)
        sender = LogTelegramSender(recorder, settings, sink, AppStrings(ctx))
    }

    private fun log(bytes: Long = 10): File =
        File.createTempFile("bydmate_logs_", ".txt").apply {
            deleteOnExit()
            java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        }

    private fun TestScope.start() {
        backgroundScope.launch { sender.run() }
        runCurrent()
    }

    private fun stop(file: File, sizeKb: Long = 0, finished: Boolean = true) {
        recorderState.value = LogRecorder.State(lastStopped = LogRecorder.Stopped(file.absolutePath, sizeKb, finished))
    }

    @Test fun `a finished recording goes to the bound bot once`() = runTest {
        val file = log()
        start()
        recorderState.value = LogRecorder.State(isRecording = true, filePath = file.absolutePath)
        runCurrent()
        stop(file)
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, file, file.name, true) }
    }

    @Test fun `the same stopped file emitted again is not sent again`() = runTest {
        val file = log()
        start()
        stop(file, sizeKb = 0)
        runCurrent()
        stop(file, sizeKb = 1)
        runCurrent()

        // A restarted service observes the same state again in the same process.
        backgroundScope.launch { sender.run() }
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `the setting off sends nothing`() = runTest {
        autoSend = false
        start()
        stop(log())
        runCurrent()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `no bound bot sends nothing`() = runTest {
        config = TgBackupConfig(token = "", chatId = null, botName = "", chatName = "")
        start()
        stop(log())
        runCurrent()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `a file over the Telegram limit is not sent`() = runTest {
        start()
        stop(log(TelegramBackupSink.MAX_UPLOAD_BYTES + 1))
        runCurrent()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `a recording that will resume is not sent until it really ends`() = runTest {
        val file = log()
        start()
        // Resumed after the power-off: recording again, nothing stopped.
        recorderState.value = LogRecorder.State(isRecording = true, filePath = file.absolutePath)
        runCurrent()
        // Logcat died on its own; the pending record stays for the next resume.
        stop(file, finished = false)
        runCurrent()
        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any(), true) }

        stop(file, sizeKb = 1)
        runCurrent()
        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `a manual send works with the setting off and reports the result`() = runTest {
        autoSend = false
        val file = log()
        val results = mutableListOf<String>()
        backgroundScope.launch { sender.results.collect { results += it } }
        runCurrent()

        sender.send(file)
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, file, file.name, true) }
        assertEquals(AppStrings(ctx).get(com.bydmate.app.R.string.settings_log_tg_sent), results.last())
        assertEquals(false, sender.sending.value)
    }

    @Test fun `a quiet send returns its status and reports nothing to the log row`() = runTest {
        val file = log()
        val results = mutableListOf<String>()
        backgroundScope.launch { sender.results.collect { results += it } }
        runCurrent()

        val status = sender.sendQuietly(file)
        runCurrent()

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, file, file.name, true) }
        assertEquals(AppStrings(ctx).get(com.bydmate.app.R.string.settings_log_tg_sent), status)
        assertEquals(emptyList<String>(), results)
        assertEquals(false, sender.sending.value)
    }

    @Test fun `a quiet send while another upload runs does nothing`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { sink.sendDocument(any(), any(), any(), any(), true) } coAnswers { gate.await(); Result.success(Unit) }
        val first = log()
        backgroundScope.launch { sender.send(first) }
        runCurrent()

        assertEquals(null, sender.sendQuietly(log()))
        gate.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    // --- the previous trip at the next start ---

    private fun tripDir(): File =
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "BYDMate-trips")
            .apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    private fun trip(dir: File, name: String, ageMs: Long): File =
        File(dir, name).apply { writeText("TRIP"); setLastModified(System.currentTimeMillis() - ageMs) }

    @Test fun `the finished trip goes to the bot once when auto-send is on`() = runTest {
        val dir = tripDir()
        val finished = trip(dir, "trip-20261024-070000.log", ageMs = 60_000)
        TripDebugLog.setAutoSendEnabled(ctx, true)

        sender.sendFinishedTripLog(ctx)
        sender.sendFinishedTripLog(ctx)

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, finished, finished.name, true) }
        assertEquals(finished.name, TripDebugLog.lastSent(ctx))
    }

    @Test fun `auto-send off or no bot sends no trip`() = runTest {
        trip(tripDir(), "trip-20261024-070000.log", ageMs = 60_000)
        TripDebugLog.setAutoSendEnabled(ctx, false)
        sender.sendFinishedTripLog(ctx)

        TripDebugLog.setAutoSendEnabled(ctx, true)
        config = config.copy(chatId = null)
        sender.sendFinishedTripLog(ctx)

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `no network is tried again, a permanent refusal is not`() = runTest {
        val finished = trip(tripDir(), "trip-20261024-070000.log", ageMs = 60_000)
        TripDebugLog.setAutoSendEnabled(ctx, true)
        coEvery { sink.sendDocument(any(), any(), any(), any(), true) } returnsMany listOf(
            Result.failure(com.bydmate.app.data.backup.TelegramSinkException(com.bydmate.app.data.backup.TelegramError.NO_NETWORK)),
            Result.success(Unit),
        )
        sender.sendFinishedTripLog(ctx)
        coVerify(exactly = 2) { sink.sendDocument(any(), any(), finished, any(), true) }
        assertEquals(finished.name, TripDebugLog.lastSent(ctx))

        trip(tripDir(), "trip-20261024-080000.log", ageMs = 30_000)
        coEvery { sink.sendDocument(any(), any(), any(), any(), true) } returns
            Result.failure(com.bydmate.app.data.backup.TelegramSinkException(com.bydmate.app.data.backup.TelegramError.BAD_TOKEN))
        sender.sendFinishedTripLog(ctx)
        coVerify(exactly = 3) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    private fun recDir(): File = File(ctx.cacheDir, "rec").apply { deleteRecursively(); mkdirs() }

    private fun recording(dir: File, name: String): File = File(dir, name).apply { writeText("dev=1023 fid=1 int=1\n") }

    @Test fun `a recording the car powered off on goes to the bot once, zipped`() = runTest {
        val dir = recDir()
        recording(dir, "bydmate_fidrec_20261025_100000.txt")
        recording(dir, "bydmate_fidrec_20261025_115150.txt")

        sender.sendUnsentRecording(ctx, dir) { false }
        sender.sendUnsentRecording(ctx, dir) { false }

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, match { it.name == "bydmate_fidrec_20261025_115150.zip" }, any(), true) }
        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any(), true) }
        // The zip is a temporary: only the original stays.
        assertEquals(emptyList<String>(), ctx.cacheDir.listFiles()!!.filter { it.name.endsWith(".zip") }.map { it.name })
    }

    @Test fun `a recording the daemon still writes, or with no bot, is left alone`() = runTest {
        val dir = recDir()
        val file = recording(dir, "bydmate_fidrec_20261025_115150.txt")

        sender.sendUnsentRecording(ctx, dir) { it == file.path }
        config = config.copy(chatId = null)
        sender.sendUnsentRecording(ctx, dir) { false }

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any(), true) }
    }

    @Test fun `a recording sent from Settings is not sent again at the next start`() = runTest {
        val dir = recDir()
        val file = recording(dir, "bydmate_fidrec_20261025_115150.txt")

        assertEquals(AppStrings(ctx).get(com.bydmate.app.R.string.settings_log_tg_sent), sender.sendRecording(ctx, file))
        sender.sendUnsentRecording(ctx, dir) { false }

        coVerify(exactly = 1) { sink.sendDocument(any(), any(), any(), any(), true) }
    }
}
