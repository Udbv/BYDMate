package com.bydmate.app.diagnostics.audio

import com.bydmate.app.voice.TtsEngine
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class AudioChannelTestTest {

    /** Records every call in order; the phrase is synthesized only for the first variant. */
    private class FakeIo(private val dump: List<String>?) : AudioChannelIo {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun phrase(variant: AudioChannelVariant) = variant.key
        override fun synthesize(text: String): TtsEngine.Pcm? =
            if (text == "media+duck") TtsEngine.Pcm(FloatArray(10), 1000) else null
        override fun requestFocus(usage: Int): FocusOutcome {
            calls += "focus $usage"
            return FocusOutcome.GRANTED
        }
        override fun abandonFocus() { calls += "abandon" }
        override fun play(pcm: TtsEngine.Pcm, usage: Int): Boolean {
            calls += "play $usage"
            return true
        }
        override suspend fun focusDump(): List<String>? {
            calls += "dump"
            return dump
        }
    }

    private fun runTest(io: FakeIo): Pair<AudioChannelTest.State.Done, List<String>> {
        val log = Collections.synchronizedList(mutableListOf<String>())
        val test = AudioChannelTest(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)).apply {
            scope = CoroutineScope(Dispatchers.Default)
            this.io = io
            this.log = { log += it }
            gapMs = 0
            dumpDelayMs = 0
            nowMs = { 0L }
        }
        test.start()
        val done = runBlocking {
            withTimeout(10_000) { test.state.first { it is AudioChannelTest.State.Done } }
        } as AudioChannelTest.State.Done
        return done to log.toList()
    }

    @Test
    fun `focus is requested and given back only for the ducked variants`() {
        val io = FakeIo(listOf("Audio Focus stack entries (last is top of stack):"))
        val (done, _) = runTest(io)
        assertEquals(4, io.calls.count { it.startsWith("focus ") })
        assertEquals(8, io.calls.count { it.startsWith("play ") })
        // One dump before the run, one during each ducked phrase.
        assertEquals(5, io.calls.count { it == "dump" })
        // Each focus request is followed by its play, then an abandon before the next request;
        // the last abandon is the end-of-run safety release.
        val focusAndAbandon = io.calls.filter { it.startsWith("focus") || it == "abandon" }.map { it.substringBefore(' ') }
        assertEquals(List(4) { listOf("focus", "abandon") }.flatten() + "abandon", focusAndAbandon)
        assertEquals(9, done.lines.size)
        assertEquals("media+duck: focus=granted played=true 0ms voice=tts", done.lines[0])
        assertEquals("media-nofocus: focus=not_requested played=true 0ms voice=tone", done.lines[1])
    }

    @Test
    fun `a missing daemon is said once in the result and in the log`() {
        val io = FakeIo(null)
        val (done, log) = runTest(io)
        assertEquals("focus dump: helper daemon unavailable", done.lines.last())
        assertTrue(log.any { it.startsWith("before: focus dump unavailable") })
    }

    @Test
    fun `focus stack lines are logged with their moment`() {
        val io = FakeIo(listOf("stack-line"))
        val (_, log) = runTest(io)
        assertTrue(log.contains("before: stack-line"))
        assertTrue(log.contains("during navigation+duck: stack-line"))
        assertTrue(log.none { it.startsWith("during navigation-nofocus") })
    }
}
