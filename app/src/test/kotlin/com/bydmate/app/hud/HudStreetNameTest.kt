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
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HudStreetNameTest {

    private fun snapshot(road: String) = NavGuidanceHub.Snapshot(
        active = true, maneuverGaode = 2, distanceMeters = 200, road = road,
    )

    private fun fids(helper: HelperClient, scope: TestScope) = HudInstrumentFids(helper, scope)

    @Test fun `utf8 truncation never splits a character`() {
        // Cyrillic is two bytes per character in UTF-8, so an odd cap must cut a whole
        // character off rather than leave half a code point on the bus.
        val (cut, charset) = HudStreetEncoding.truncate(
            "Хрещатик", HudStreetEncoding.UTF8, overseasFeature = true, maxBytes = 5)
        assertTrue(cut.size <= 5)
        assertEquals("Хр", String(cut, Charsets.UTF_8))
        assertEquals(HudStreetEncoding.UTF8, charset)
        // A cap larger than the text leaves it whole.
        val (whole, _) = HudStreetEncoding.truncate(
            "Садова", HudStreetEncoding.UTF8, overseasFeature = true, maxBytes = 96)
        assertEquals("Садова", String(whole, Charsets.UTF_8))
    }

    @Test fun `auto picks utf8 for the overseas feature and gbk for the domestic one`() {
        val (overseas, overseasName) = HudStreetEncoding.truncate(
            "Садова", HudStreetEncoding.AUTO, overseasFeature = true, maxBytes = 96)
        assertEquals(HudStreetEncoding.UTF8, overseasName)
        assertEquals("Садова", String(overseas, Charsets.UTF_8))

        // A firmware that exposes the domestic feature is a Chinese-market build; its panel
        // decodes GBK, and Cyrillic UTF-8 bytes sent there come out as Chinese characters -
        // which is exactly what the Tang L drew under the arrow on 2026-09-10.
        val (domestic, domesticName) = HudStreetEncoding.truncate(
            "Садова", HudStreetEncoding.AUTO, overseasFeature = false, maxBytes = 96)
        assertEquals(HudStreetEncoding.GBK, domesticName)
        assertNotEquals(
            "GBK bytes must differ from UTF-8 or the setting changes nothing",
            String(domestic, Charsets.UTF_8), String(domestic, Charset.forName("GBK")))
        assertEquals("Садова", String(domestic, Charset.forName("GBK")))
    }

    @Test fun `an unavailable charset falls back to utf8 instead of throwing`() {
        val (bytes, name) = HudStreetEncoding.truncate(
            "Садова", "not-a-charset", overseasFeature = true, maxBytes = 96)
        assertEquals("not-a-charset>utf8", name)
        assertEquals("Садова", String(bytes, Charsets.UTF_8))
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
