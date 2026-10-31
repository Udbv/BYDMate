package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TgBackupConfig
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The automatic trip log: starts with the car, ends with it, and leaves the owner's stop alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AutoTripLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val state = MutableStateFlow(LogRecorder.State())
    private val file = File(context.cacheDir, "bydmate_logs_test.txt").apply { writeText("x") }
    private val recorder = mockk<LogRecorder>(relaxed = true).also {
        every { it.state } returns state
        coEvery { it.start(any()) } answers {
            state.value = LogRecorder.State(isRecording = true, filePath = file.absolutePath, startedAtMs = 1_000)
            LogRecorder.StartResult.Started(file)
        }
        coEvery { it.stop() } answers {
            state.value = LogRecorder.State()
            null
        }
    }
    private val sender = mockk<LogTelegramSender>(relaxed = true)
    private val settings = mockk<SettingsRepository>().also {
        coEvery { it.getTgBackupConfig() } returns TgBackupConfig("", null, "", "")
    }
    private lateinit var log: AutoTripLog

    @Before fun setUp() {
        context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        AutoTripLog.setEnabled(context, true)
        log = AutoTripLog(context, recorder, sender, settings)
        every { recorder.pendingPath() } returns null
        runBlocking { log.onServiceStart(now = 0) }
    }

    @Test fun `starts within seconds of the car coming on`() = runBlocking {
        log.onTick(carOn = true, now = 1_000)
        coVerify(exactly = 0) { recorder.start(any()) }
        log.onTick(carOn = true, now = AutoTripLog.EARLY_START_MS)
        coVerify(exactly = 1) { recorder.start(any()) }
        assertTrue(state.value.isRecording)
    }

    @Test fun `waits past the resume of a pending button recording`() = runBlocking {
        every { recorder.pendingPath() } returns "/sdcard/Download/bydmate_logs_button.txt"
        log.onTick(carOn = true, now = 10_000)
        coVerify(exactly = 0) { recorder.start(any()) }
        log.onTick(carOn = true, now = AutoTripLog.START_DELAY_MS)
        coVerify(exactly = 1) { recorder.start(any()) }
        assertTrue(state.value.isRecording)
    }

    @Test fun `stops its recording a minute after the car goes off`() = runBlocking {
        log.onTick(carOn = true, now = AutoTripLog.START_DELAY_MS)
        log.onTick(carOn = false, now = 100_000)
        coVerify(exactly = 0) { recorder.stop() }
        log.onTick(carOn = false, now = 100_000 + AutoTripLog.OFF_MS)
        coVerify(exactly = 1) { recorder.stop() }
    }

    @Test fun `an owner's stop keeps it off until the car goes off`() = runBlocking {
        log.onTick(carOn = true, now = AutoTripLog.START_DELAY_MS)
        state.value = LogRecorder.State()                       // stopped from Settings
        log.onTick(carOn = true, now = 120_000)
        log.onTick(carOn = true, now = 180_000)
        coVerify(exactly = 1) { recorder.start(any()) }
        log.onTick(carOn = false, now = 200_000)
        log.onTick(carOn = false, now = 200_000 + AutoTripLog.OFF_MS)
        log.onTick(carOn = true, now = 400_000)
        coVerify(exactly = 2) { recorder.start(any()) }
    }

    @Test fun `the last trip's file is finished, not resumed into the new trip`() = runBlocking {
        log.onTick(carOn = true, now = AutoTripLog.START_DELAY_MS)
        every { recorder.pendingPath() } returns file.absolutePath
        val restarted = AutoTripLog(context, recorder, sender, settings)
        assertTrue(restarted.onServiceStart(now = 0))
        coVerify { recorder.stop() }
    }

    @Test fun `a button recording is left to resume`() = runBlocking {
        every { recorder.pendingPath() } returns "/sdcard/Download/bydmate_logs_button.txt"
        assertFalse(AutoTripLog(context, recorder, sender, settings).onServiceStart(now = 0))
    }

    @Test fun `switched off it does nothing`() = runBlocking {
        AutoTripLog.setEnabled(context, false)
        log.onTick(carOn = true, now = 10 * AutoTripLog.START_DELAY_MS)
        coVerify(exactly = 0) { recorder.start(any()) }
    }

    @Test fun `a long trip goes out in 20-minute parts and keeps recording`() = runBlocking {
        coEvery { settings.getTgBackupConfig() } returns TgBackupConfig("t", 1L, "", "")
        var clock = 1_000L
        coEvery { recorder.start(any()) } answers {
            state.value = LogRecorder.State(isRecording = true, filePath = file.absolutePath, startedAtMs = clock)
            LogRecorder.StartResult.Started(file)
        }
        log.onTick(carOn = true, now = AutoTripLog.START_DELAY_MS)
        coVerify(exactly = 1) { recorder.start(any()) }
        log.onTick(carOn = true, now = 1_000 + AutoTripLog.CHUNK_MS - 1)
        coVerify(exactly = 0) { recorder.stop() }
        clock = 1_000 + AutoTripLog.CHUNK_MS
        log.onTick(carOn = true, now = clock)
        coVerify(exactly = 1) { recorder.stop() }
        coVerify(exactly = 2) { recorder.start(any()) }
        assertTrue(state.value.isRecording)
        coVerify(timeout = 5_000) { sender.sendZipped(any(), file) }
        // The part was not an owner's stop: the next tick neither stops nor holds the recording off.
        log.onTick(carOn = true, now = 1_000 + AutoTripLog.CHUNK_MS + 2_000)
        coVerify(exactly = 2) { recorder.start(any()) }
    }
}
