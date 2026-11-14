package com.bydmate.app.data.vehicle

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.helper.KeptDisabledApps
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KeptDisabledAppsSyncTest {

    private val values = mutableMapOf<String, String>()
    private val settings = mockk<SettingsRepository>().also { s ->
        val key = slot<String>()
        val default = slot<String>()
        coEvery { s.getString(capture(key), capture(default)) } answers { values[key.captured] ?: default.captured }
        val k = slot<String>()
        val v = slot<String>()
        coEvery { s.setString(capture(k), capture(v)) } answers { values[k.captured] = v.captured }
    }
    private val helper = mockk<HelperClient>().also {
        coEvery { it.keepAppsDisabled(any(), any()) } returns KeptDisabledApps.STATUS_STARTED
    }

    @Test fun `a driver who never chose sends nothing to the daemon`() = runTest {
        assertNull(KeptDisabledAppsSync.apply(helper, settings, "start"))
        coVerify(exactly = 0) { helper.keepAppsDisabled(any(), any()) }
    }

    @Test fun `stored choices become masks`() = runTest {
        values["keep_disabled_app_youku"] = "true"
        values["keep_disabled_app_alipay"] = "true"
        values["keep_disabled_app_hms"] = "false"
        assertEquals(KeptDisabledApps.STATUS_STARTED, KeptDisabledAppsSync.apply(helper, settings, "wake"))
        coVerify { helper.keepAppsDisabled(0b01001, 0b10000) }
    }

    @Test fun `ticking stores the choice and applies it, unticking restores`() = runTest {
        KeptDisabledAppsSync.setChoice(helper, settings, "iqiyi", true)
        assertEquals("true", values["keep_disabled_app_iqiyi"])
        coVerify { helper.keepAppsDisabled(0b10, 0) }
        KeptDisabledAppsSync.setChoice(helper, settings, "iqiyi", false)
        assertEquals("false", values["keep_disabled_app_iqiyi"])
        coVerify { helper.keepAppsDisabled(0, 0b10) }
    }

    @Test fun `an unknown id is ignored and nothing is stored`() = runTest {
        assertNull(KeptDisabledAppsSync.setChoice(helper, settings, "com.byd.otaupdate", true))
        assertTrue(values.isEmpty())
        coVerify(exactly = 0) { helper.keepAppsDisabled(any(), any()) }
    }

    @Test fun `rows are off by default and show the system state`() {
        val rows = KeptDisabledAppsSync.rows(mapOf("hms" to "true")) { pkg ->
            when (pkg) {
                "com.byd.videoplay.youku" -> KeptDisabledApps.STATE_DISABLED_USER
                "com.huawei.hwid" -> null
                else -> KeptDisabledApps.STATE_DEFAULT
            }
        }
        assertEquals(5, rows.size)
        assertFalse(rows[0].checked)
        assertEquals(KeptDisabledAppsSync.SystemState.DISABLED, rows[0].systemState)
        assertEquals(KeptDisabledAppsSync.SystemState.ENABLED, rows[1].systemState)
        assertTrue(rows[4].checked)
        assertEquals(KeptDisabledAppsSync.SystemState.MISSING, rows[4].systemState)
    }
}
