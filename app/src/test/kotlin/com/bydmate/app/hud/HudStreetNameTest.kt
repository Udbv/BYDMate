package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol
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

/**
 * The next-street write, as openbyd does it: one feature, UTF-16LE, no BOM, the SDK call after
 * the bytes, and no second attempt whatever the status says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HudStreetNameTest {

    private fun snapshot(road: String) = NavGuidanceHub.Snapshot(
        active = true, maneuverGaode = 2, distanceMeters = 200, road = road,
    )

    /** ICU is not available on a plain JVM, so transliteration is injected where it matters and
     *  switched off where the test is about the bytes rather than the script. */
    private fun fids(
        helper: HelperClient,
        scope: TestScope,
        sanitize: Boolean = false,
        sanitizer: HudTextSanitizer = HudTextSanitizer(null),
    ) = HudInstrumentFids(helper, scope, { sanitize }, sanitizer)

    private fun helper(): HelperClient = mockk<HelperClient>(relaxed = true).also {
        coEvery { it.write(any(), any(), any()) } returns true
        coEvery { it.read(any(), any(), any()) } returns null
        coEvery { it.sdkSetInt(any(), any(), any()) } returns 0
        coEvery { it.sdkSetBytes(any(), any(), any()) } returns 0
        coEvery { it.sdkNaviStatus(any()) } returns 0
        coEvery { it.sdkSimpleGuidance(any(), any()) } returns 0
        coEvery { it.sdkNextPathName(any()) } returns 0
    }

    @Test fun `the name goes out as utf-16le bytes with no bom`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(snapshot("вул. Садова"))
        scope.advanceUntilIdle()

        val bytes = slot<ByteArray>()
        coVerify(exactly = 1) {
            helper.sdkSetBytes(
                HelperBinderProtocol.SDK_DEV_INSTRUMENT,
                HudInstrumentFids.FID_STREET_NAME,
                capture(bytes),
            )
        }
        assertEquals("вул. Садова", String(bytes.captured, Charsets.UTF_16LE))
        assertEquals(2 * "вул. Садова".length, bytes.captured.size)
        // A BOM would make the panel read the first character as U+FFFE.
        assertTrue(
            "leading BOM",
            !(bytes.captured[0] == 0xFF.toByte() && bytes.captured[1] == 0xFE.toByte()),
        )
        coVerify(exactly = 1) { helper.sdkNextPathName("вул. Садова") }
    }

    @Test fun `only the one street feature is ever written`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(snapshot("Main Street"))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkSetBytes(any(), any(), any()) }
        coVerify(exactly = 0) { helper.writeBytes(any(), any(), any()) }
    }

    @Test fun `an empty name becomes a single space`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(snapshot(""))
        scope.advanceUntilIdle()

        val bytes = slot<ByteArray>()
        coVerify { helper.sdkSetBytes(any(), HudInstrumentFids.FID_STREET_NAME, capture(bytes)) }
        // An empty buffer leaves the previous street on the glass; a space clears it.
        assertEquals(" ", String(bytes.captured, Charsets.UTF_16LE))
        coVerify { helper.sdkNextPathName(" ") }
    }

    @Test fun `status 0 is the accepted code`() {
        assertTrue(HelperClient.sdkAccepted(0))
        assertTrue(!HelperClient.sdkAccepted(1))
        assertTrue(!HelperClient.sdkAccepted(-1))
        assertTrue(!HelperClient.sdkAccepted(null))
    }

    @Test fun `a rejected write counts as a failure and is never retried elsewhere`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        coEvery { helper.sdkSetBytes(any(), any(), any()) } returns -1

        val f = fids(helper, scope)
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()
        // Same name again: the panel refused it, but the donor never inspects the status, so
        // nothing is resent.
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkSetBytes(any(), any(), any()) }
        assertTrue("a rejected write must count", f.failures > 0)
    }

    @Test fun `an unchanged street is not written again`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        val f = fids(helper, scope)
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()
        f.update(snapshot("Садова"))
        scope.advanceUntilIdle()

        coVerify(exactly = 1) { helper.sdkSetBytes(any(), any(), any()) }
        coVerify(exactly = 1) { helper.sdkNextPathName(any()) }
    }

    @Test fun `with the pref on the name is latinised before it is sent`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()
        val sanitizer = HudTextSanitizer { s -> if (s == "é") "e" else s }

        fids(helper, scope, sanitize = true, sanitizer = sanitizer).update(snapshot("  Café  "))
        scope.advanceUntilIdle()

        coVerify { helper.sdkNextPathName("Cafe") }
    }

    @Test fun `a corrupted read is bounded before it reaches the bus`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val helper = helper()

        fids(helper, scope).update(snapshot("x".repeat(5_000)))
        scope.advanceUntilIdle()

        val bytes = slot<ByteArray>()
        coVerify { helper.sdkSetBytes(any(), any(), capture(bytes)) }
        assertEquals(2 * HudInstrumentFids.MAX_STREET_CHARS, bytes.captured.size)
    }
}
