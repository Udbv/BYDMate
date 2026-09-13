package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_INSTRUMENT
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_SETTING
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_STATISTIC
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test bench itself, pinned against openbyd's `yt` case 1 (manual frame), `l70` (the
 * three-second tour) and `m70` (state and stop).
 *
 * The expected lane numbers come from the donor's own tester table, which is deliberately wider
 * than the glyph table downstream — `17,0` is accepted here even though `CarControlImpl` has no
 * combined glyph for it. That asymmetry is the donor's, and the port keeps it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HudPanelTesterTest {

    private fun helper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery { it.write(any(), any(), any()) } returns true
        coEvery { it.read(any(), any(), any()) } returns null
        coEvery { it.isAlive() } returns true
        coEvery { it.sdkSetInt(any(), any(), any()) } returns 0
        coEvery { it.sdkSetBytes(any(), any(), any()) } returns 0
        coEvery { it.sdkSetIntArray(any(), any(), any()) } returns 0
        coEvery { it.sdkNaviStatus(any()) } returns 0
        coEvery { it.sdkSimpleGuidance(any(), any()) } returns 0
        coEvery { it.sdkNextPathName(any()) } returns 0
        coEvery { it.sdkRestRoute(any(), any(), any()) } returns 0
        coEvery { it.sdkCameraGuidance(any(), any(), any()) } returns 0
        coEvery { it.writeStatus(any(), any(), any()) } returns 0
    }

    private fun tester(
        helper: HelperClient,
        scope: TestScope,
        routeActive: () -> Boolean = { false },
    ) = HudPanelTester(helper, transliteratePref = { false }, routeActive = routeActive).also {
        it.scope = scope
        it.sanitizer = HudTextSanitizer(null)
    }

    // ---------------------------------------------------------------- raw FID rows

    private val DEV = HudInstrumentFids.DEV_INSTRUMENT

    @Test fun `the raw camera row writes type, distance and state in that order`() = runTest {
        val helper = helper()
        val tester = tester(helper, this)
        tester.setCameraType(3)
        tester.setCameraDistance("250")
        tester.setCameraState("1")

        assertTrue(tester.sendRawCameraNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_TYPE, 3)
            helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_DISTANCE, 250)
            helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_STATE, 1)
        }
        val status = tester.state.value.rawCameraStatus
        assertTrue(status, "0x43F03010=3->0" in status)
        assertTrue(status, "0x43F0301C=250->0" in status)
        assertTrue(status, "0x43F03018=1->0" in status)
    }

    @Test fun `the raw camera clear runs the three backwards`() = runTest {
        val helper = helper()
        assertTrue(tester(helper, this).clearRawCameraNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_STATE, 0)
            helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_DISTANCE, -1)
            helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_TYPE, 0)
        }
    }

    @Test fun `the raw safety row writes the safety triple in order`() = runTest {
        val helper = helper()
        val tester = tester(helper, this)
        tester.setCameraType(7)
        tester.setCameraDistance("80")
        tester.setCameraState("1")

        assertTrue(tester.sendRawSafetyNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_SAFETY_TYPE, 7)
            helper.writeStatus(DEV, HudInstrumentFids.FID_SAFETY_DISTANCE, 80)
            helper.writeStatus(DEV, HudInstrumentFids.FID_SAFETY_STATE, 1)
        }
        assertTrue(tester.state.value.rawSafetyStatus.isNotEmpty())
    }

    @Test fun `the raw safety clear runs the three backwards`() = runTest {
        val helper = helper()
        assertTrue(tester(helper, this).clearRawSafetyNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_SAFETY_STATE, 0)
            helper.writeStatus(DEV, HudInstrumentFids.FID_SAFETY_DISTANCE, -1)
            helper.writeStatus(DEV, HudInstrumentFids.FID_SAFETY_TYPE, 0)
        }
    }

    @Test fun `the then row writes icon, distance and action in order, from its own defaults`() = runTest {
        val helper = helper()
        val tester = tester(helper, this)

        assertTrue(tester.sendRawThenNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_ICON, 2)
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_DISTANCE, 500)
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_ACTION, 0)
        }
    }

    @Test fun `the then row sends the values typed into it`() = runTest {
        val helper = helper()
        val tester = tester(helper, this)
        tester.setThenIcon("9")
        tester.setThenDistance("120")
        tester.setThenAction("1")

        assertTrue(tester.sendRawThenNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_ICON, 9)
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_DISTANCE, 120)
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_ACTION, 1)
        }
    }

    @Test fun `the then clear runs the three backwards`() = runTest {
        val helper = helper()
        assertTrue(tester(helper, this).clearRawThenNow())

        coVerifyOrder {
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_ACTION, 0)
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_DISTANCE, -1)
            helper.writeStatus(DEV, HudInstrumentFids.FID_THEN_ICON, 0)
        }
    }

    @Test fun `a live route blocks every raw row`() = runTest {
        val helper = helper()
        val tester = tester(helper, this, routeActive = { true })

        assertFalse(tester.sendRawCameraNow())
        assertFalse(tester.sendRawSafetyNow())
        assertFalse(tester.sendRawThenNow())

        coVerify(exactly = 0) { helper.writeStatus(DEV, any(), any()) }
        assertTrue(tester.state.value.routeActiveBlocked)
    }

    @Test fun `a refused raw write still reports its status`() = runTest {
        val helper = helper()
        coEvery { helper.writeStatus(DEV, HudInstrumentFids.FID_CAMERA_STATE, any()) } returns -1
        val tester = tester(helper, this)

        assertTrue(tester.sendRawCameraNow())

        val status = tester.state.value.rawCameraStatus
        assertTrue(status, "0x43F03018=1->-1" in status)
    }

    // ---------------------------------------------------------------- lane parser

    @Test fun `the donor's default lane text is the reference strip`() {
        val lanes = HudPanelTester.parseLanes(HudPanelTester.DEFAULT_LANE_TEXT, 100)!!
        assertArrayEquals(intArrayOf(1, 0, 0, 4, 3), lanes.codes)
        assertArrayEquals(intArrayOf(255, 255, 255, 3, 3), lanes.fronts)
        assertEquals(100, lanes.distanceMeters)
    }

    @Test fun `a front the tester's table allows survives even without a glyph for it`() {
        // 17 = u-turn-left + right; the tester allows fronts {0, 3, 5}, so 0 is kept and the
        // lane writer falls back to the plain glyph downstream.
        val lanes = HudPanelTester.parseLanes("17,0", 100)!!
        assertArrayEquals(intArrayOf(17), lanes.codes)
        assertArrayEquals(intArrayOf(0), lanes.fronts)
    }

    @Test fun `a front the table does not allow becomes not-on-route`() {
        val lanes = HudPanelTester.parseLanes("3,1", 100)!!
        assertArrayEquals(intArrayOf(3), lanes.codes)
        assertArrayEquals(intArrayOf(255), lanes.fronts)
    }

    @Test fun `minus one and 255 both mean not-on-route`() {
        val lanes = HudPanelTester.parseLanes("0,-1|0,255", 100)!!
        assertArrayEquals(intArrayOf(255, 255), lanes.fronts)
    }

    @Test fun `a front equal to its code is kept as it is`() {
        val lanes = HudPanelTester.parseLanes("3,3", 100)!!
        assertArrayEquals(intArrayOf(3), lanes.fronts)
    }

    @Test fun `blank segments and malformed segments are skipped, not fatal`() {
        val lanes = HudPanelTester.parseLanes("1,255||0,255|5|", 100)!!
        assertArrayEquals(intArrayOf(1, 0), lanes.codes)
    }

    @Test fun `a token that is not a number gives no lanes at all`() {
        assertNull(HudPanelTester.parseLanes("1,255|left,0", 100))
        assertNull(HudPanelTester.parseLanes("   ", 100))
    }

    // ---------------------------------------------------------------- steps

    @Test fun `the step table is the donor's 49 entries`() {
        assertEquals(49, HudPanelTestSteps.SIZE)
        assertEquals(1, HudPanelTestSteps.STEPS[0].iconId)
        assertEquals(35, HudPanelTestSteps.STEPS[0].distanceMeters)
        assertEquals("Grand Avenue", HudPanelTestSteps.STEPS[0].road)
        assertEquals(49, HudPanelTestSteps.STEPS[48].iconId)
        assertEquals(0, HudPanelTestSteps.STEPS[47].distanceMeters)   // arrival draws "Arrived"
    }

    @Test fun `a step number outside the table is coerced, not wrapped`() {
        assertEquals(1, HudPanelTestSteps.stepFor(0).iconId)
        assertEquals(1, HudPanelTestSteps.stepFor(-7).iconId)
        assertEquals(49, HudPanelTestSteps.stepFor(50).iconId)
        assertEquals(49, HudPanelTestSteps.stepFor(49).iconId)
    }

    @Test fun `typing a step number loads that step's street name`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val t = tester(helper(), scope)
        t.setManualStep("2")
        assertEquals("Broadway", t.state.value.customStreet)
        t.setManualStep("x")          // digits only: ignored, nothing changes
        assertEquals("2", t.state.value.manualStep)
    }

    // ---------------------------------------------------------------- manual frame

    @Test fun `the manual frame writes the donor's sequence`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val t = tester(helper, scope)

        assertTrue(t.sendManualFrameNow())

        coVerifyOrder {
            // arm
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_NAVI_STATUS, 2)
            helper.sdkNaviStatus(2)
            // guidance: step 1 = icon 1 at 35 m
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_ICON, 1)
            helper.sdkSimpleGuidance(1, 35)
            // street
            helper.sdkSetBytes(SDK_DEV_INSTRUMENT, HudInstrumentFids.FID_STREET_NAME, any())
            helper.sdkNextPathName("Grand Avenue")
            // remaining route: the tester's fixed 0 h 15 min / 15 km
            helper.sdkRestRoute(0, 15, 15_000L)
            // lanes: five of them, at 100 m
            helper.sdkSetInt(SDK_DEV_SETTING, HudLaneWriter.FID_SET_LANE_NUM, 5)
            helper.sdkSetInt(SDK_DEV_SETTING, HudLaneWriter.FID_SET_LANE_DIST, 100)
            helper.sdkSetIntArray(SDK_DEV_INSTRUMENT, any(), any())
            // speed limit last
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudPanelTester.STAT_SEGMENT_SPEED_LIMIT, 50)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudPanelTester.STAT_SEGMENT_SPEED_2, 50)
            helper.sdkCameraGuidance(1, 0, 1)
        }
        val s = t.state.value
        assertEquals(1, s.iconId)
        assertEquals(35, s.distanceMeters)
        assertEquals("Grand Avenue", s.streetDisplay)
        assertTrue(s.lastStatus.contains("guidance=0"))
        assertNull(s.lastError)
    }

    @Test fun `an unchanged speed limit is not written twice`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val t = tester(helper, scope)

        t.sendManualFrameNow()
        t.sendManualFrameNow()

        coVerify(exactly = 1) {
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudPanelTester.STAT_SEGMENT_SPEED_LIMIT, 50)
        }
    }

    @Test fun `a thrown write surfaces as the card's failure line`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        coEvery { helper.sdkCameraGuidance(any(), any(), any()) } throws IllegalStateException("binder died")
        val t = tester(helper, scope)

        assertFalse(t.sendManualFrameNow())
        assertEquals("binder died", t.state.value.lastError)
    }

    // ---------------------------------------------------------------- route guard

    @Test fun `nothing is sent while the navigator is guiding a route`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val t = tester(helper, scope, routeActive = { true })

        assertFalse(t.sendManualFrameNow())
        t.start()
        scope.advanceUntilIdle()

        assertFalse(t.state.value.running)
        assertTrue(t.state.value.routeActiveBlocked)
        coVerify(exactly = 0) { helper.sdkSimpleGuidance(any(), any()) }
        coVerify(exactly = 0) { helper.sdkNextPathName(any()) }
    }

    // ---------------------------------------------------------------- automatic tour

    @Test fun `the tour walks the table at three seconds a step and wraps at 49`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val t = tester(helper, scope)

        // The loop never goes idle (it always has a delay pending), so time is advanced by hand.
        t.start()
        scope.advanceTimeBy(1)
        assertEquals(1, t.state.value.stepNumber)
        assertEquals(0, t.state.value.elapsedSeconds)

        scope.advanceTimeBy(HudPanelTester.STEP_INTERVAL_MS)
        assertEquals(2, t.state.value.stepNumber)
        assertEquals(3, t.state.value.elapsedSeconds)

        // to the end of the table and one step past it
        scope.advanceTimeBy(HudPanelTester.STEP_INTERVAL_MS * 48)
        assertEquals(1, t.state.value.stepNumber)
        assertEquals(3 * 49, t.state.value.elapsedSeconds)
        coVerify { helper.sdkSimpleGuidance(49, 84) }   // the last step of the tour

        t.stopNow()
        assertFalse(t.state.value.running)
        assertEquals(0, t.state.value.iconId)
        assertEquals("", t.state.value.streetDisplay)
        coVerify { helper.sdkNaviStatus(4) }
    }

    @Test fun `the card shows what the panel was handed, raw or Latinised`() {
        val plain = HudTextSanitizer(null)
        assertEquals("[RAW] Broadway", HudPanelTester.displayStreet("Broadway", false, plain))
        assertEquals("Broadway", HudPanelTester.displayStreet("Broadway", true, plain))
        assertEquals(
            "[SANITIZED] Cafe (Café)",
            HudPanelTester.displayStreet("Café", true, plain))
    }
}
