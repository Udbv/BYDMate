package com.bydmate.app.diagnostics.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusDumpTest {

    private val dump = """
        Stream volumes (device: index)
        - STREAM_MUSIC:
           Muted: false

        Audio Focus stack entries (last is top of stack):
          source:android.os.BinderProxy@1 -- pack: com.spotify.music -- client: a -- gain: GAIN -- flags:  -- loss: LOSS_TRANSIENT_CAN_DUCK -- notified: true -- uid: 10101 -- attr: AudioAttributes: usage=USAGE_MEDIA
          source:android.os.BinderProxy@2 -- pack: com.bydmate.app -- client: b -- gain: GAIN_TRANSIENT_MAY_DUCK -- flags: DELAY_OK -- loss: none -- notified: true -- uid: 10200 -- attr: AudioAttributes: usage=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE

         Notify on duck:  true
         In ring or call: false

        PlaybackActivityMonitor dump time: 12:00:00
          ducked players piids:5 7
        Ducking is DUCK_MODE_X
    """.trimIndent()

    @Test
    fun `keeps the focus stack and the duck lines in order`() {
        val out = AudioFocusDump.excerpt(dump)
        assertEquals("Audio Focus stack entries (last is top of stack):", out[0])
        assertTrue(out[1].contains("com.spotify.music"))
        assertTrue(out[2].contains("com.bydmate.app"))
        assertEquals(listOf("Notify on duck:  true", "ducked players piids:5 7", "Ducking is DUCK_MODE_X"), out.drop(3))
    }

    @Test
    fun `stack lines are not repeated as duck lines`() {
        // The stack entries contain CAN_DUCK / MAY_DUCK; they must appear once, in the stack part.
        val out = AudioFocusDump.excerpt(dump)
        assertEquals(1, out.count { it.contains("com.spotify.music") })
    }

    @Test
    fun `missing stack is reported and duck lines still kept`() {
        val out = AudioFocusDump.excerpt("foo\n ducked players piids:3\nbar")
        assertEquals(listOf(AudioFocusDump.NO_STACK, "ducked players piids:3"), out)
    }

    @Test
    fun `long lines and long stacks are capped`() {
        val entries = (1..40).joinToString("\n") { "  entry $it " + "x".repeat(400) }
        val out = AudioFocusDump.excerpt("Audio Focus stack entries (last is top of stack):\n$entries\n")
        // The header plus 16 entries.
        assertEquals(17, out.size)
        assertTrue(out.all { it.length <= 240 })
    }
}
