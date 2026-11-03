package com.bydmate.app.diagnostics.audio

import android.media.AudioAttributes
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioChannelReportTest {

    @Test
    fun `eight variants, ducked first within each usage`() {
        val all = AudioChannelVariant.all()
        assertEquals(
            listOf(
                "media+duck", "media-nofocus", "navigation+duck", "navigation-nofocus",
                "assistant+duck", "assistant-nofocus", "notification+duck", "notification-nofocus",
            ),
            all.map { it.key },
        )
        assertEquals(
            listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                AudioAttributes.USAGE_ASSISTANT,
                AudioAttributes.USAGE_NOTIFICATION,
            ),
            all.map { it.channel.usage }.distinct(),
        )
    }

    @Test
    fun `result line names variant, focus answer, timing and source`() {
        val r = VariantResult(
            AudioChannelVariant(AudioChannel.NAVIGATION, duck = true),
            FocusOutcome.DELAYED_TIMEOUT, played = true, durationMs = 2100, voice = "tts",
        )
        assertEquals(
            "navigation+duck: focus=delayed_timeout played=true 2100ms voice=tts",
            AudioChannelReport.resultLine(r),
        )
    }

    @Test
    fun `focus change codes get names`() {
        assertEquals("loss_transient_can_duck",
            AudioChannelReport.focusChangeName(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals("gain", AudioChannelReport.focusChangeName(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals("change(42)", AudioChannelReport.focusChangeName(42))
    }

    @Test
    fun `tone holds the beeps then a steady tone within range`() {
        val one = AudioChannelReport.tone(1)
        val three = AudioChannelReport.tone(3)
        // Each beep adds 150 ms of sound and 150 ms of gap.
        assertEquals(AudioChannelReport.durationMs(one) + 600, AudioChannelReport.durationMs(three))
        assertEquals(1800L, AudioChannelReport.durationMs(one))
        assertTrue(three.samples.all { abs(it) <= 1f })
        assertTrue(three.samples.any { it != 0f })
    }
}
