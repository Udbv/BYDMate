package com.bydmate.app.diagnostics.audio

import android.media.AudioAttributes
import androidx.annotation.StringRes
import com.bydmate.app.R

/** The AudioAttributes usages the channel test (issue #6) plays a phrase under. [key] is the
 *  log name, [label] the spoken and on-screen name. */
enum class AudioChannel(val usage: Int, val key: String, @StringRes val label: Int) {
    MEDIA(AudioAttributes.USAGE_MEDIA, "media", R.string.audio_test_usage_media),
    NAVIGATION(
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, "navigation", R.string.audio_test_usage_navigation,
    ),
    ASSISTANT(AudioAttributes.USAGE_ASSISTANT, "assistant", R.string.audio_test_usage_assistant),
    NOTIFICATION(AudioAttributes.USAGE_NOTIFICATION, "notification", R.string.audio_test_usage_notification),
}

/**
 * One playback of the test: [channel] with a transient may-duck focus request ([duck] = true),
 * or with no focus request at all. The pair tells apart "the firmware ducks by usage alone"
 * from "it ducks only for a focus holder", which decides how Waze guidance has to be routed.
 */
data class AudioChannelVariant(val channel: AudioChannel, val duck: Boolean) {

    /** Log name, e.g. `navigation+duck` / `navigation-nofocus`. */
    val key: String get() = channel.key + if (duck) "+duck" else "-nofocus"

    @get:StringRes
    val modeLabel: Int get() = if (duck) R.string.audio_test_mode_duck else R.string.audio_test_mode_no_focus

    companion object {
        /** Play order: per usage, the ducked variant first, so the owner hears the pair back to back. */
        fun all(): List<AudioChannelVariant> =
            AudioChannel.entries.flatMap { listOf(AudioChannelVariant(it, true), AudioChannelVariant(it, false)) }
    }
}
