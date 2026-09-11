package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_INSTRUMENT
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_SETTING
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_STATISTIC
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavManeuverCodes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.coVerifySequence
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
 * The instrument-panel write sequences, pinned feature by feature against openbyd 2.4.3.
 *
 * Order is the point of this file, not coverage: the cluster acts on the features as they land,
 * so a start that arms the SDK before the feature, or a stop that clears the lane strip after
 * the navigation status, produces a different picture on the glass than the donor does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HudInstrumentFidsTest {

    private fun helper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery { it.write(any(), any(), any()) } returns true
        coEvery { it.read(any(), any(), any()) } returns null
        coEvery { it.sdkSetInt(any(), any(), any()) } returns 0
        coEvery { it.sdkSetDouble(any(), any(), any()) } returns 0
        coEvery { it.sdkSetBytes(any(), any(), any()) } returns 0
        coEvery { it.sdkSetIntArray(any(), any(), any()) } returns 0
        coEvery { it.sdkNaviStatus(any()) } returns 0
        coEvery { it.sdkSimpleGuidance(any(), any()) } returns 0
        coEvery { it.sdkNextPathName(any()) } returns 0
        coEvery { it.sdkRestRoute(any(), any(), any()) } returns 0
        coEvery { it.sdkCameraGuidance(any(), any(), any()) } returns 0
    }

    private fun fids(helper: HelperClient, scope: TestScope) =
        HudInstrumentFids(helper, scope, { false }, HudTextSanitizer(null))

    // ---------------------------------------------------------------- start

    @Test fun `the first update arms the panel in the donor's order`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()

        coVerifyOrder {
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_NAVI_STATUS, 2)
            helper.sdkSetInt(SDK_DEV_SETTING, HudInstrumentFids.SET_NAVI_SCREEN_STATUS, 3)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_NAVI_STATUS, 1)
            helper.sdkNaviStatus(2)
        }
    }

    @Test fun `a status the cluster still reports as navigating is not re-armed`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        // 2 = our own arming, 621 = the cluster's SOME/IP navigation holding the panel.
        coEvery { helper.read(any(), HudInstrumentFids.FID_NAVI_STATUS, any()) } returnsMany
            listOf(2L, 621L)

        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()
        f.update(NavGuidanceHub.Snapshot(active = true, distanceMeters = 50))
        scope.advanceUntilIdle()
        f.update(NavGuidanceHub.Snapshot(active = true, distanceMeters = 40))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkNaviStatus(2) }
    }

    @Test fun `a cluster that forgot the arming is re-armed on the next update`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        coEvery { helper.read(any(), HudInstrumentFids.FID_NAVI_STATUS, any()) } returns 0L

        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()
        f.update(NavGuidanceHub.Snapshot(active = true, distanceMeters = 50))
        scope.advanceUntilIdle()

        coVerify(exactly = 2) { helper.sdkNaviStatus(2) }
    }

    @Test fun `a failed read leaves the cached state alone`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper() // read returns null
        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()
        f.update(NavGuidanceHub.Snapshot(active = true, distanceMeters = 50))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkNaviStatus(2) }
    }

    // ------------------------------------------------------------- guidance

    @Test fun `guidance writes the icon twice, the distance, then the sdk call`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(
            NavGuidanceHub.Snapshot(
                active = true, maneuverGaode = NavManeuverCodes.GAODE_RIGHT, distanceMeters = 240,
            )
        )
        scope.advanceUntilIdle()

        coVerifyOrder {
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_ICON, 2)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_ICON_DUAL, 2)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_DISTANCE, 240)
            helper.sdkSimpleGuidance(2, 240)
        }
    }

    @Test fun `a panel icon from the arrow reader beats the gaode translation`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        // Clockwise roundabout, second exit: 36 has no Gaode equivalent at all, so only the
        // reader's own panel code can produce it.
        fids(helper, scope).update(
            NavGuidanceHub.Snapshot(
                active = true, maneuverGaode = NavManeuverCodes.GAODE_ROUNDABOUT_ENTER,
                panelIcon = 36, distanceMeters = 100,
            )
        )
        scope.advanceUntilIdle()

        coVerify { helper.sdkSimpleGuidance(36, 100) }
    }

    @Test fun `an unchanged icon and distance write nothing`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val s = NavGuidanceHub.Snapshot(active = true, maneuverGaode = 1, distanceMeters = 100)

        val f = fids(helper, scope)
        f.update(s); scope.advanceUntilIdle()
        f.update(s); scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkSimpleGuidance(any(), any()) }
    }

    // ----------------------------------------------------------- rest route

    @Test fun `the rest route writes five features then the sdk call`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        // 1h 5m remaining, 42 km to go.
        fids(helper, scope).update(
            NavGuidanceHub.Snapshot(
                active = true, etaSeconds = 3900, totalDistMeters = 42_000,
            )
        )
        scope.advanceUntilIdle()

        coVerifyOrder {
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_MILEAGE, 42_000)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_HOUR, 1)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_MINUTE, 5)
            // Remaining seconds: openbyd always writes 0 and BYDMate used to omit it entirely,
            // leaving whatever the cluster's own navigation had left there.
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_SECOND, 0)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_ARRIVE_MINUTE, any())
            helper.sdkRestRoute(1, 5, 42_000L)
        }
    }

    @Test fun `mileage is capped where the int cast would wrap, not at six digits`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        // A cross-country route: the old 999 999 m cap turned 2 400 km into 1 000 km.
        fids(helper, scope).update(
            NavGuidanceHub.Snapshot(active = true, etaSeconds = 600, totalDistMeters = 2_400_000)
        )
        scope.advanceUntilIdle()

        coVerify { helper.sdkRestRoute(0, 10, 2_400_000L) }
        coVerify { helper.write(any(), HudInstrumentFids.FID_TRIP_MILEAGE, 2_400_000) }
        assertEquals(Int.MAX_VALUE.toLong(), HudInstrumentFids.REST_MILEAGE_MAX)
    }

    @Test fun `an unchanged rest route writes nothing`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val s = NavGuidanceHub.Snapshot(active = true, etaSeconds = 600, totalDistMeters = 5_000)

        val f = fids(helper, scope)
        f.update(s); scope.advanceUntilIdle()
        f.update(s); scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkRestRoute(any(), any(), any()) }
    }

    // ----------------------------------------------------------------- stop

    // ---------------------------------------------------------------- speed limit

    @Test fun `a speed limit writes both statistics and the camera call`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(NavGuidanceHub.Snapshot(active = true, speedLimit = 50))
        scope.advanceUntilIdle()

        coVerifyOrder {
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_LIMIT, 50)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_2, 50)
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 0, 1)
        }
    }

    @Test fun `an unchanged speed limit writes nothing the second time`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val s = NavGuidanceHub.Snapshot(active = true, speedLimit = 50)

        val f = fids(helper, scope)
        f.update(s); scope.advanceUntilIdle()
        f.update(s.copy(distanceMeters = 100)); scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkCameraGuidance(any(), any(), any()) }
    }

    @Test fun `a route without a known limit sends no speed limit at all`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(NavGuidanceHub.Snapshot(active = true, speedLimit = 0))
        scope.advanceUntilIdle()

        coVerify(exactly = 0) { helper.sdkCameraGuidance(any(), any(), any()) }
        coVerify(exactly = 0) {
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_2, any())
        }
    }

    @Test fun `a limit that disappears clears the roundel`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true, speedLimit = 50)); scope.advanceUntilIdle()
        f.update(NavGuidanceHub.Snapshot(active = true, speedLimit = 0)); scope.advanceUntilIdle()

        // 0 on both statistics, and the camera call with state 0: the sign goes away.
        coVerify(exactly = 1) {
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_LIMIT, 0)
        }
        coVerify(exactly = 1) { helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 0, 0) }
    }

    @Test fun `stop clears every feature the donor clears, in the donor's order`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()

        f.stopNow()

        val dev = HudInstrumentFids.DEV_INSTRUMENT
        coVerifyOrder {
            helper.write(dev, HudInstrumentFids.FID_NAVI_STATUS, 4)
            helper.write(dev, HudInstrumentFids.FID_GUIDE_ICON, 0)
            helper.write(dev, HudInstrumentFids.FID_GUIDE_ICON_DUAL, 0)
            // -1, not 0: 0 is a real distance and the panel draws "in 0 m" for it.
            helper.write(dev, HudInstrumentFids.FID_GUIDE_DISTANCE, -1)
            helper.sdkSetBytes(SDK_DEV_INSTRUMENT, HudInstrumentFids.FID_STREET_NAME, ByteArray(0))
            helper.write(dev, HudInstrumentFids.FID_TRIP_MILEAGE, -1)
            helper.write(dev, HudInstrumentFids.FID_TRIP_HOUR, 0)
            helper.write(dev, HudInstrumentFids.FID_TRIP_MINUTE, 0)
            helper.write(dev, HudInstrumentFids.FID_TRIP_SECOND, 0)
            helper.write(dev, HudInstrumentFids.FID_ARRIVE_MINUTE, 0)

            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_NAVI_STATUS, 0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_CUR_SEGMENT, 0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_LENGTH, 0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_LIMIT, 0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SIZE, 0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_INDEX_2, 0)
            helper.sdkSetDouble(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_LENGTH_2, 0.0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_2, 0)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_LIGHT_2, 0)

            helper.sdkSetInt(SDK_DEV_SETTING, HudInstrumentFids.SET_LANE_NUM, 0)
            helper.sdkSetInt(SDK_DEV_SETTING, HudInstrumentFids.SET_LANE_DIST, 0)
            helper.sdkSetInt(SDK_DEV_SETTING, HudInstrumentFids.SET_LANE_STATES.first(), 255)
            helper.sdkSetInt(SDK_DEV_SETTING, HudInstrumentFids.SET_LANE_STATES.last(), 255)

            helper.sdkSetIntArray(SDK_DEV_INSTRUMENT, any(), any())
            helper.sdkNaviStatus(4)
        }
        // All twelve slots, not just the two the order check names.
        for (fid in HudInstrumentFids.SET_LANE_STATES) {
            coVerify(exactly = 1) { helper.sdkSetInt(SDK_DEV_SETTING, fid, 255) }
        }
    }

    @Test fun `the stop lane batch is one call carrying all twenty-five features`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()

        f.stopNow()

        val fidsSlot = slot<IntArray>()
        val valuesSlot = slot<IntArray>()
        coVerify(exactly = 1) {
            helper.sdkSetIntArray(SDK_DEV_INSTRUMENT, capture(fidsSlot), capture(valuesSlot))
        }
        val expectedFids = IntArray(25)
        val expectedValues = IntArray(25)
        expectedFids[0] = 427827416; expectedValues[0] = 0
        for (i in 0 until 8) {
            expectedFids[1 + i] = 427827288 + 16 * i; expectedValues[1 + i] = -1
            expectedFids[9 + i] = 427827296 + 16 * i; expectedValues[9 + i] = 14
            expectedFids[17 + i] = 427827300 + 16 * i; expectedValues[17 + i] = -1
        }
        assertArrayEquals(expectedFids, fidsSlot.captured)
        assertArrayEquals(expectedValues, valuesSlot.captured)
    }

    @Test fun `stopping twice does nothing the second time`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val f = fids(helper, scope)
        f.update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()

        f.stopNow()
        f.stopNow()

        coVerify(exactly = 1) { helper.sdkNaviStatus(4) }
    }

    @Test fun `a fresh route after a stop re-arms and resends everything`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val s = NavGuidanceHub.Snapshot(
            active = true, maneuverGaode = 1, distanceMeters = 300, road = "Main",
        )
        val f = fids(helper, scope)
        f.update(s); scope.advanceUntilIdle()
        f.stopNow()
        f.update(s); scope.advanceUntilIdle()

        coVerify(exactly = 2) { helper.sdkNaviStatus(2) }
        coVerify(exactly = 2) { helper.sdkSimpleGuidance(1, 300) }
        coVerify(exactly = 2) { helper.sdkNextPathName("Main") }
    }

    @Test fun `a whole first update makes exactly the donor's sequence of sdk calls`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(
            NavGuidanceHub.Snapshot(
                active = true, maneuverGaode = NavManeuverCodes.GAODE_LEFT, distanceMeters = 150,
                road = "Main", etaSeconds = 1800, totalDistMeters = 12_000,
            )
        )
        scope.advanceUntilIdle()

        coVerifySequence {
            // start
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_NAVI_STATUS, 2)
            helper.sdkSetInt(SDK_DEV_SETTING, HudInstrumentFids.SET_NAVI_SCREEN_STATUS, 3)
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_NAVI_STATUS, 1)
            helper.sdkNaviStatus(2)
            // guidance
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_ICON, 1)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_ICON_DUAL, 1)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_GUIDE_DISTANCE, 150)
            helper.sdkSimpleGuidance(1, 150)
            // street
            helper.sdkSetBytes(SDK_DEV_INSTRUMENT, HudInstrumentFids.FID_STREET_NAME, any())
            helper.sdkNextPathName("Main")
            // rest route
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_MILEAGE, 12_000)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_HOUR, 0)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_MINUTE, 30)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_TRIP_SECOND, 0)
            helper.write(HudInstrumentFids.DEV_INSTRUMENT, HudInstrumentFids.FID_ARRIVE_MINUTE, any())
            helper.sdkRestRoute(0, 30, 12_000L)
        }
    }
}
