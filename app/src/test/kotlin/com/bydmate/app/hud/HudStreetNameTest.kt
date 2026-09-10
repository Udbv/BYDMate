package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavGuidanceHub
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HudStreetNameTest {

    private fun snapshot(road: String) = NavGuidanceHub.Snapshot(
        active = true, maneuverGaode = 2, distanceMeters = 200, road = road,
    )

    private fun fids(helper: HelperClient, scope: TestScope) = HudInstrumentFids(helper, scope)

    @Test fun `utf8 truncation never splits a character`() {
        val helper = mockk<HelperClient>(relaxed = true)
        val f = HudInstrumentFids(helper, TestScope())
        // Cyrillic is two bytes per character, so an odd cap must cut a whole character off.
        val cut = f.truncateUtf8("Хрещатик", 5)
        assertTrue(cut.size <= 5)
        assertEquals("Хр", String(cut, Charsets.UTF_8))
        // A cap larger than the text leaves it whole.
        assertEquals("Садова", String(f.truncateUtf8("Садова", 96), Charsets.UTF_8))
    }

    @Test fun `the overseas feature is tried first and remembered once accepted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.write(any(), any(), any()) } returns true
        coEvery { helper.writeBytes(any(), HudInstrumentFids.FID_STREET_NAME_OVERSEAS, any()) } returns 1

        val f = fids(helper, scope)
        f.update(snapshot("вул. Садова"))
        scope.advanceUntilIdle()

        val bytes = slot<ByteArray>()
        coVerify {
            helper.writeBytes(
                HudInstrumentFids.DEV_INSTRUMENT,
                HudInstrumentFids.FID_STREET_NAME_OVERSEAS,
                capture(bytes),
            )
        }
        assertEquals("вул. Садова", String(bytes.captured, Charsets.UTF_8))
        // The domestic feature is never touched once the overseas one answered.
        coVerify(exactly = 0) {
            helper.writeBytes(any(), HudInstrumentFids.FID_STREET_NAME, any())
        }
    }

    @Test fun `a panel that refuses the overseas feature falls back to the domestic one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.write(any(), any(), any()) } returns true
        coEvery { helper.writeBytes(any(), HudInstrumentFids.FID_STREET_NAME_OVERSEAS, any()) } returns -1
        coEvery { helper.writeBytes(any(), HudInstrumentFids.FID_STREET_NAME, any()) } returns 1

        val f = fids(helper, scope)
        f.update(snapshot("Main Street"))
        scope.advanceUntilIdle()

        coVerify { helper.writeBytes(any(), HudInstrumentFids.FID_STREET_NAME_OVERSEAS, any()) }
        coVerify { helper.writeBytes(any(), HudInstrumentFids.FID_STREET_NAME, any()) }
    }

    @Test fun `an unchanged street is not written again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.write(any(), any(), any()) } returns true
        coEvery { helper.writeBytes(any(), any(), any()) } returns 1

        val f = fids(helper, scope)
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.writeBytes(any(), any(), any()) }
    }

    @Test fun `a panel that takes neither feature is not retried on every maneuver`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.write(any(), any(), any()) } returns true
        coEvery { helper.writeBytes(any(), any(), any()) } returns -1

        val f = fids(helper, scope)
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()

        // Two candidates on the first attempt, nothing on the second: the name is unchanged.
        coVerify(exactly = 2) { helper.writeBytes(any(), any(), any()) }
    }
}
