package com.bydmate.app.data.roadpoints

import android.location.Location
import com.bydmate.app.helper.push.FidRecBump
import com.bydmate.app.voice.VoiceController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Лежачий?» after a felt bump; «да» saves the point where it was felt (#22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BumpPrompterTest {

    private val answer = slot<(Boolean?) -> Unit>()
    private val voice = mockk<VoiceController>(relaxed = true).also {
        every { it.askYesNo(any(), any(), capture(answer)) } answers { }
    }
    private val points = mockk<RoadPointRecorder>(relaxed = true).also {
        coEvery { it.markAt(any(), any()) } returns MarkResult.Added(RoadPointType.SPEED_BUMP)
    }
    private val prompter = BumpPrompter(voice, points)

    private fun fix(time: Long, lat: Double) = Location("gps").apply {
        this.time = time
        latitude = lat
        longitude = 30.0
        accuracy = 5f
    }

    @Test fun `yes saves the bump at the fix of the moment it was felt`() {
        prompter.noteLocation(fix(1_000, 50.0))
        prompter.noteLocation(fix(5_000, 50.001))
        prompter.onBumps(listOf(FidRecBump(1_200, 24.0, 35.0)), nowMs = 4_000)
        answer.captured(true)
        coVerify(timeout = 2_000) { points.markAt(RoadPointType.SPEED_BUMP, match { it.latitude == 50.0 }) }
    }

    @Test fun `no saves nothing`() {
        prompter.noteLocation(fix(1_000, 50.0))
        prompter.onBumps(listOf(FidRecBump(1_200, 24.0, 35.0)), nowMs = 4_000)
        answer.captured(false)
        Thread.sleep(300)
        coVerify(exactly = 0) { points.markAt(any(), any()) }
    }

    @Test fun `a bump is asked about once, and not right after another`() {
        prompter.onBumps(listOf(FidRecBump(1_000, 24.0, 35.0)), nowMs = 2_000)
        prompter.onBumps(listOf(FidRecBump(1_000, 24.0, 35.0)), nowMs = 5_000)
        prompter.onBumps(listOf(FidRecBump(1_000, 24.0, 35.0), FidRecBump(10_000, 25.0, 30.0)), nowMs = 11_000)
        verify(exactly = 1) { voice.askYesNo(any(), any(), any()) }
    }

    @Test fun `a bump reported too late is not asked about`() {
        prompter.onBumps(listOf(FidRecBump(1_000, 24.0, 35.0)), nowMs = 1_000 + BumpPrompter.MAX_AGE_MS + 1)
        verify(exactly = 0) { voice.askYesNo(any(), any(), any()) }
    }
}
