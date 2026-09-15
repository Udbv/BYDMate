package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol.SDK_DEV_STATISTIC
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.WazeReportsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The camera sign the reports list produces.
 *
 * Type 1 with a real distance is the only camera call this cluster has been seen to draw (it is
 * the speed-limit roundel's own call); the clear is the donor's type 0 at -1 m. The speed-limit
 * path must keep its own call at distance 0 either way - that is the one thing on the glass today.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HudInstrumentFidsCameraTest {

    private fun helper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery { it.write(any(), any(), any()) } returns true
        coEvery { it.read(any(), any(), any()) } returns null
        coEvery { it.sdkSetInt(any(), any(), any()) } returns 0
        coEvery { it.sdkSetBytes(any(), any(), any()) } returns 0
        coEvery { it.sdkNaviStatus(any()) } returns 0
        coEvery { it.sdkSimpleGuidance(any(), any()) } returns 0
        coEvery { it.sdkNextPathName(any()) } returns 0
        coEvery { it.sdkRestRoute(any(), any(), any()) } returns 0
        coEvery { it.sdkCameraGuidance(any(), any(), any()) } returns 0
    }

    private fun fids(helper: HelperClient, scope: TestScope) =
        HudInstrumentFids(helper, scope, { false }, HudTextSanitizer(null))

    private fun camera(meters: Int) = NavGuidanceHub.Snapshot(
        active = true,
        cameraAlert = "Камера",
        cameraDistanceMeters = meters,
        cameraKind = WazeReportsReader.Kind.CAMERA,
    )

    @Test fun `a camera ahead sends type 1 with its distance`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(camera(800))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) {
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 800, 1)
        }
    }

    @Test fun `only a changed distance is sent again`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        val f = fids(helper, scope)
        f.update(camera(800)); scope.advanceUntilIdle()
        f.update(camera(800)); scope.advanceUntilIdle()
        f.update(camera(600)); scope.advanceUntilIdle()

        coVerifyOrder {
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 800, 1)
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 600, 1)
        }
        coVerify(exactly = 2) { helper.sdkCameraGuidance(any(), any(), any()) }
    }

    @Test fun `a camera that disappears is cleared the donor's way`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        val f = fids(helper, scope)
        f.update(camera(800)); scope.advanceUntilIdle()
        f.update(NavGuidanceHub.Snapshot(active = true)); scope.advanceUntilIdle()

        coVerifyOrder {
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 800, 1)
            helper.sdkCameraGuidance(HudCameraTypes.NONE, HudCameraTypes.CLEAR_DISTANCE, 1)
        }
    }

    @Test fun `a route that never saw a camera sends no clear`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(NavGuidanceHub.Snapshot(active = true))
        scope.advanceUntilIdle()

        coVerify(exactly = 0) { helper.sdkCameraGuidance(any(), any(), any()) }
    }

    @Test fun `a police row draws no camera`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(
            NavGuidanceHub.Snapshot(
                active = true,
                cameraAlert = "Поліція",
                cameraDistanceMeters = 500,
                cameraKind = WazeReportsReader.Kind.POLICE,
            ),
        )
        scope.advanceUntilIdle()

        coVerify(exactly = 0) { helper.sdkCameraGuidance(any(), any(), any()) }
    }

    @Test fun `the speed limit keeps its own call beside the camera`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(camera(800).copy(speedLimit = 50))
        scope.advanceUntilIdle()

        coVerifyOrder {
            helper.sdkSetInt(SDK_DEV_STATISTIC, HudInstrumentFids.STAT_SEGMENT_SPEED_LIMIT, 50)
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 0, 1)
            helper.sdkCameraGuidance(HudCameraTypes.SPEED_LIMITED, 800, 1)
        }
    }
}
