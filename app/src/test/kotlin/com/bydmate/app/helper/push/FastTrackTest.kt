package com.bydmate.app.helper.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detail on demand: compressed while nothing happens, every sample around a trigger. */
class FastTrackTest {

    private val steering = 1
    private val brake = 2

    private fun track() = FastTrack().apply {
        adopt(steering, "Bodywork.BODYWORK_STEERING_WHEEL_ANGEL")
        adopt(brake, "Speed.SPEED_BRAKE_S")
        adopt(3, "Cpu.CPU_TEMPRATURE")    // not a fast signal: ignored
    }

    private fun angle(t: Long, v: Double) = RecEvent(t, 1001, steering, "Bodywork.BODYWORK_STEERING_WHEEL_ANGEL", 0, v)

    @Test fun `only the fast signals are taken`() {
        val f = track()
        assertTrue(f.isFast(steering))
        assertTrue(!f.isFast(3))
    }

    @Test fun `a steady ramp is compressed and a hard brake writes the seconds before it raw`() {
        val f = track()
        val out = mutableListOf<RecEvent>()
        for (i in 0..50) f.sample(angle(i * 20L, i * 0.1), out)          // 1 s of a smooth turn
        assertEquals(1, out.size)                                         // only the first point so far

        f.sample(RecEvent(1_000, 1013, brake, "Speed.SPEED_BRAKE_S", 40, 0.0), out)
        val note = out.single { it.note != null }
        assertTrue(note.note!!.startsWith("detail from -8000 ms: Speed.SPEED_BRAKE_S=40"))
        assertEquals((0..50).map { it * 20L }, out.filter { it.fid == steering }.map { it.atMs })

        out.clear()
        for (i in 51..400) f.sample(angle(i * 20L, i * 0.1), out)        // window, then back to compression
        f.drain(out)
        val times = out.filter { it.fid == steering }.map { it.atMs }
        assertEquals((51..199).map { it * 20L }, times.filter { it < 4_000 })   // raw until +3 s
        assertTrue("compressed after the window: $times", times.count { it >= 4_000 } <= 3)
    }

    @Test fun `a marker opens a window over every fast signal`() {
        val f = track()
        val out = mutableListOf<RecEvent>()
        f.sample(angle(0, 1.0), out)
        f.sample(angle(100, 5.0), out)
        f.sample(angle(200, 1.0), out)
        out.clear()
        f.trigger(2_000, "Audio.AUDIO_VOLUME=5", out)
        // 0 and the corner at 100 were already written by the compressor; the ring adds the rest raw.
        assertEquals(listOf(200L), out.filter { it.fid == steering }.map { it.atMs })
        assertTrue(out.first().note!!.endsWith("Audio.AUDIO_VOLUME=5"))
    }

    @Test fun `ending a run inside a window writes no sample twice`() {
        val f = track()
        val out = mutableListOf<RecEvent>()
        for (i in 0..20) f.sample(angle(i * 20L, if (i % 2 == 0) 0.0 else 3.0), out)
        f.trigger(500, "marker", out)
        f.drain(out)
        val times = out.filter { it.fid == steering }.map { it.atMs }
        assertEquals(times.distinct(), times)
        assertEquals((0..20).map { it * 20L }, times)
    }
}
