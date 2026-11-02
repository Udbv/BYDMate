package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.push.FidRecStart
import com.bydmate.app.helper.push.FidRecStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The bump recording: starts with the car, goes out in parts, never touches a recording by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BumpRecordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var running = false
    private var path = ""
    private var n = 0
    private val helper = mockk<HelperClient>(relaxed = true).also { h ->
        coEvery { h.recStatus() } answers { FidRecStatus(running, emptyList(), 0, emptyList(), path, 0) }
        coEvery { h.recStart(any()) } answers {
            running = true
            path = File(context.cacheDir, "bydmate_fidrec_${++n}.txt").apply { writeText("x") }.path
            FidRecStart(5, 100)
        }
        coEvery { h.recStop() } answers {
            running = false
            true
        }
    }
    private val sender = mockk<LogTelegramSender>(relaxed = true)
    private lateinit var rec: BumpRecording

    @Before fun setUp() {
        context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        BumpRecording.setEnabled(context, true)
        rec = BumpRecording(context, helper, sender)
    }

    @Test fun `starts over the bump devices when the car comes on`() = runBlocking {
        rec.onTick(carOn = true, now = 0)
        coVerify(exactly = 1) { helper.recStart(BumpRecording.DEVICES) }
    }

    @Test fun `a long drive goes out in parts`() = runBlocking {
        rec.onTick(carOn = true, now = 0)
        rec.onTick(carOn = true, now = BumpRecording.CHUNK_MS - 1)
        coVerify(exactly = 0) { helper.recStop() }
        rec.onTick(carOn = true, now = BumpRecording.CHUNK_MS + BumpRecording.CHECK_MS)
        coVerify(exactly = 1) { helper.recStop() }
        coVerify(exactly = 2) { helper.recStart(any()) }
        coVerify(timeout = 5_000) { sender.sendZipped(any(), File(context.cacheDir, "bydmate_fidrec_1.txt")) }
    }

    @Test fun `the last part goes a minute after the car is off`() = runBlocking {
        rec.onTick(carOn = true, now = 0)
        rec.onTick(carOn = false, now = 20_000)
        coVerify(exactly = 0) { helper.recStop() }
        rec.onTick(carOn = false, now = 20_000 + BumpRecording.OFF_MS)
        coVerify(exactly = 1) { helper.recStop() }
    }

    @Test fun `a recording started by hand is left alone`() = runBlocking {
        running = true
        path = "/sdcard/Download/bydmate_fidrec_by_hand.txt"
        rec.onTick(carOn = true, now = 0)
        rec.onTick(carOn = false, now = 20_000)
        rec.onTick(carOn = false, now = 20_000 + BumpRecording.OFF_MS)
        coVerify(exactly = 0) { helper.recStart(any()) }
        coVerify(exactly = 0) { helper.recStop() }
    }

    @Test fun `switched off it does nothing`() = runBlocking {
        BumpRecording.setEnabled(context, false)
        rec.onTick(carOn = true, now = 0)
        coVerify(exactly = 0) { helper.recStatus() }
    }
}
