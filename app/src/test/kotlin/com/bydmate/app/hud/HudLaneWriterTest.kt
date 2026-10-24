package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavLaneCodes
import com.bydmate.app.navdata.NavLanes
import com.bydmate.app.navdata.NavLanes.Dir
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expected numbers here are read off openbyd's `CarControlImpl.sendLaneGuidanceInfo`
 * (:1400-1467) and its glyph tables (:172-312), not derived from this implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HudLaneWriterTest {

    private fun lanes(codes: IntArray, fronts: IntArray, dist: Int) =
        NavLanes.ofCodes(codes, fronts, dist)

    /** The reference strip: left, two straights, straight+right taken right, right taken right. */
    private val sample = lanes(
        intArrayOf(1, 0, 0, 4, 3), intArrayOf(255, 255, 255, 3, 3), 100)

    // ---- pure encoding ----

    @Test fun `feature ids match BYD's own catalogue`() {
        assertEquals(1_285_554_200, HudLaneWriter.FID_SET_LANE_NUM)
        assertEquals(1_285_554_184, HudLaneWriter.FID_SET_LANE_DIST)
        assertArrayEquals(
            intArrayOf(
                1_285_554_208, 1_285_554_212, 1_285_554_216, 1_285_554_220,
                1_285_554_224, 1_285_554_228, 1_285_554_232, 1_285_554_236,
                1_285_554_260, 1_285_554_264, 1_285_554_268, 1_285_554_272,
            ),
            HudLaneWriter.SET_LANE_STATES,
        )
        assertEquals(427_827_416, HudLaneWriter.FID_LANE_COUNT)
        assertEquals(427_827_288, HudLaneWriter.fidGlyph(0))
        assertEquals(427_827_296, HudLaneWriter.fidLineType(0))
        assertEquals(427_827_300, HudLaneWriter.fidRecommended(0))
        assertEquals(427_827_288 + 7 * 16, HudLaneWriter.fidGlyph(7))
    }

    @Test fun `the setting device gets the count, twelve states and the distance`() {
        val w = HudLaneWriter.settingWrites(sample)
        assertEquals(14, w.size)
        assertEquals(
            listOf(
                1_285_554_200 to 5,
                1_285_554_208 to 1, 1_285_554_212 to 0, 1_285_554_216 to 0, 1_285_554_220 to 4,
                1_285_554_224 to 3, 1_285_554_228 to 255, 1_285_554_232 to 255,
                1_285_554_236 to 255, 1_285_554_260 to 255, 1_285_554_264 to 255,
                1_285_554_268 to 255, 1_285_554_272 to 255,
                1_285_554_184 to 100,
            ),
            w,
        )
    }

    @Test fun `the instrument batch is one 25-value write in the donor's layout`() {
        val (fids, values) = HudLaneWriter.instrumentBatch(sample)
        val expectedFids = IntArray(25)
        expectedFids[0] = 427_827_416
        for (i in 0 until 8) {
            expectedFids[1 + i] = 427_827_288 + 16 * i
            expectedFids[9 + i] = 427_827_296 + 16 * i
            expectedFids[17 + i] = 427_827_300 + 16 * i
        }
        assertArrayEquals(expectedFids, fids)
        assertArrayEquals(
            intArrayOf(
                5,
                // glyphs: plain 1+1, plain 0+1, plain 0+1, complex(4,3), front 3+26, then off
                2, 1, 1, 54, 29, -1, -1, -1,
                // line types: plain until the two on-route lanes, then unused
                5, 5, 5, 0, 0, 14, 14, 14,
                // validity: every present lane, on route or not
                1, 1, 1, 1, 1, -1, -1, -1,
            ),
            values,
        )
    }

    @Test fun `clearing sends zero lanes, all states 255 and distance -1`() {
        val cleared = NavLanes.CLEARED
        assertEquals(
            listOf(1_285_554_200 to 0) +
                HudLaneWriter.SET_LANE_STATES.map { it to 255 } +
                listOf(1_285_554_184 to -1),
            HudLaneWriter.settingWrites(cleared),
        )
        val (_, values) = HudLaneWriter.instrumentBatch(cleared)
        assertArrayEquals(
            intArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, 14, 14, 14, 14, 14, 14, 14, 14,
                -1, -1, -1, -1, -1, -1, -1, -1),
            values,
        )
    }

    @Test fun `nine lanes fill twelve setting slots and eight panel slots, count unclamped`() {
        val nine = lanes(IntArray(9) { 0 }, IntArray(9) { 255 }, 250)
        val setting = HudLaneWriter.settingWrites(nine)
        assertEquals(9, setting.first().second)
        assertEquals(14, setting.size)
        // Slots 0..8 hold the lanes, 9..11 are empty.
        assertEquals(List(9) { 0 } + List(3) { 255 }, setting.subList(1, 13).map { it.second })
        val (_, values) = HudLaneWriter.instrumentBatch(nine)
        assertEquals(9, values[0])
        for (i in 0 until 8) {
            assertEquals("glyph $i", 1, values[1 + i])
            assertEquals("valid $i", 1, values[17 + i])
        }
    }

    @Test fun `a multi-direction pair with no combined glyph falls back to the plain one`() {
        // Code 17 (u-turn + right) has glyphs for 3 and 5 only; a straight must not blank it.
        assertEquals(-1, HudLaneWriter.complexGlyph(17, 0))
        assertEquals(17, HudLaneWriter.laneGuideVal(17, 0))
        assertEquals(73, HudLaneWriter.laneGuideVal(17, 3))
        // A simple lane on the route draws the front, not its own code.
        assertEquals(29, HudLaneWriter.laneGuideVal(3, 3))
        assertEquals(4, HudLaneWriter.laneGuideVal(3, 255))
    }

    @Test fun `direction sets map onto the donor lane codes`() {
        assertEquals(NavLaneCodes.STRAIGHT, NavLaneCodes.codeFor(setOf(Dir.STRAIGHT)))
        assertEquals(NavLaneCodes.LEFT, NavLaneCodes.codeFor(setOf(Dir.LEFT)))
        assertEquals(NavLaneCodes.STRAIGHT_RIGHT, NavLaneCodes.codeFor(setOf(Dir.STRAIGHT, Dir.RIGHT)))
        assertEquals(NavLaneCodes.EMPTY, NavLaneCodes.codeFor(emptySet()))
    }

    @Test fun `the reader's lane list becomes the same codes and fronts`() {
        val read = NavLanes(
            listOf(
                NavLanes.Lane(setOf(Dir.LEFT)),
                NavLanes.Lane(setOf(Dir.STRAIGHT)),
                NavLanes.Lane(setOf(Dir.STRAIGHT)),
                NavLanes.Lane(setOf(Dir.STRAIGHT, Dir.RIGHT), recommended = Dir.RIGHT),
                NavLanes.Lane(setOf(Dir.RIGHT), recommended = Dir.RIGHT),
            ),
            distanceMeters = 100,
        )
        assertArrayEquals(intArrayOf(1, 0, 0, 4, 3), read.codes)
        assertArrayEquals(intArrayOf(255, 255, 255, 3, 3), read.fronts)
        assertEquals(sample, read)
        // The log line the emulator scenarios grep for.
        assertEquals("1,0,0,4>3,3>3", read.codesLine())
    }

    // ---- sending ----

    private fun writer(helper: HelperClient, scope: TestScope) = HudLaneWriter(helper, scope)

    private fun fakeHelper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery { it.sdkSetInt(any(), any(), any()) } returns 0
        coEvery { it.sdkSetIntArray(any(), any(), any()) } returns 0
    }

    @Test fun `one update is fourteen setting writes and one instrument batch`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = fakeHelper()
        writer(helper, scope).update(sample)
        scope.advanceUntilIdle()

        for ((fid, value) in HudLaneWriter.settingWrites(sample)) {
            coVerify(exactly = 1) { helper.sdkSetInt(HudLaneWriter.SDK_DEV_SETTING, fid, value) }
        }
        val fids = slot<IntArray>()
        val values = slot<IntArray>()
        coVerify(exactly = 1) {
            helper.sdkSetIntArray(HudLaneWriter.SDK_DEV_INSTRUMENT, capture(fids), capture(values))
        }
        val (expectedFids, expectedValues) = HudLaneWriter.instrumentBatch(sample)
        assertArrayEquals(expectedFids, fids.captured)
        assertArrayEquals(expectedValues, values.captured)
        coVerify(exactly = 14) { helper.sdkSetInt(any(), any(), any()) }
    }

    @Test fun `the same lanes twice are sent once`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = fakeHelper()
        val w = writer(helper, scope)
        w.update(sample)
        scope.advanceUntilIdle()
        w.update(lanes(intArrayOf(1, 0, 0, 4, 3), intArrayOf(255, 255, 255, 3, 3), 100))
        scope.advanceUntilIdle()
        coVerify(exactly = 14) { helper.sdkSetInt(any(), any(), any()) }
        coVerify(exactly = 1) { helper.sdkSetIntArray(any(), any(), any()) }
        // A different distance is a different strip.
        w.update(lanes(intArrayOf(1, 0, 0, 4, 3), intArrayOf(255, 255, 255, 3, 3), 80))
        scope.advanceUntilIdle()
        coVerify(exactly = 2) { helper.sdkSetIntArray(any(), any(), any()) }
    }

    @Test fun `losing the lanes clears once and then stays quiet`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = fakeHelper()
        val w = writer(helper, scope)
        w.update(sample)
        scope.advanceUntilIdle()
        w.update(NavLanes.NONE)
        scope.advanceUntilIdle()
        w.update(NavLanes.NONE)
        w.clear()
        scope.advanceUntilIdle()

        coVerify(exactly = 1) {
            helper.sdkSetInt(HudLaneWriter.SDK_DEV_SETTING, HudLaneWriter.FID_SET_LANE_DIST, -1)
        }
        coVerify(exactly = 1) {
            helper.sdkSetInt(HudLaneWriter.SDK_DEV_SETTING, HudLaneWriter.FID_SET_LANE_NUM, 0)
        }
        coVerify(exactly = 2) { helper.sdkSetIntArray(any(), any(), any()) }
    }

    @Test fun `nothing is sent before anything was ever shown`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = fakeHelper()
        val w = writer(helper, scope)
        w.clear()
        w.update(NavLanes.NONE)
        scope.advanceUntilIdle()
        coVerify(exactly = 0) { helper.sdkSetInt(any(), any(), any()) }
        coVerify(exactly = 0) { helper.sdkSetIntArray(any(), any(), any()) }
    }

    @Test fun `a rejected write is counted without stopping the rest`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = fakeHelper()
        coEvery { helper.sdkSetIntArray(any(), any(), any()) } returns -1
        val w = writer(helper, scope)
        w.update(sample)
        scope.advanceUntilIdle()
        assertEquals(15L, w.writes)
        assertEquals(1L, w.failures)
    }
}
