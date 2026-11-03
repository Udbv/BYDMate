package com.bydmate.app.data.homeassistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaUrlsTest {

    @Test fun `https base is kept, trailing slash and api suffix dropped`() {
        assertEquals("https://ha.example.test/", HaUrls.normalizeBase("https://ha.example.test")?.toString())
        assertEquals("https://ha.example.test/", HaUrls.normalizeBase(" https://ha.example.test/ ")?.toString())
        assertEquals("https://ha.example.test/", HaUrls.normalizeBase("https://ha.example.test/api/")?.toString())
        assertEquals("https://ha.example.test:8123/", HaUrls.normalizeBase("https://ha.example.test:8123/api")?.toString())
    }

    @Test fun `sub path survives`() {
        assertEquals("https://example.test/ha", HaUrls.normalizeBase("https://example.test/ha/")?.toString())
    }

    @Test fun `plain http and garbage are rejected`() {
        assertNull(HaUrls.normalizeBase("http://ha.example.test"))
        assertNull(HaUrls.normalizeBase("ha.example.test"))
        assertNull(HaUrls.normalizeBase(""))
    }

    @Test fun `registration and api root urls`() {
        val base = HaUrls.normalizeBase("https://ha.example.test")!!
        assertEquals("https://ha.example.test/api/mobile_app/registrations", HaUrls.registrationUrl(base).toString())
        assertEquals("https://ha.example.test/api/", HaUrls.apiRootUrl(base).toString())
        val sub = HaUrls.normalizeBase("https://example.test/ha")!!
        assertEquals("https://example.test/ha/api/mobile_app/registrations", HaUrls.registrationUrl(sub).toString())
    }

    @Test fun `cloudhook wins over remote ui and base`() {
        val base = "https://ha.example.test/".toHttpUrl()
        val reg = HaRegistration("wh1", cloudhookUrl = "https://hooks.example.test/abc", remoteUiUrl = "https://remote.example.test")
        assertEquals("https://hooks.example.test/abc", HaUrls.webhookUrl(base, reg).toString())
    }

    @Test fun `remote ui wins over base when no cloudhook`() {
        val base = "https://ha.example.test/".toHttpUrl()
        val reg = HaRegistration("wh1", remoteUiUrl = "https://remote.example.test")
        assertEquals("https://remote.example.test/api/webhook/wh1", HaUrls.webhookUrl(base, reg).toString())
    }

    @Test fun `base is used when nothing else, insecure candidates skipped`() {
        val base = "https://ha.example.test/".toHttpUrl()
        val reg = HaRegistration("wh1", cloudhookUrl = "http://hooks.example.test/abc", remoteUiUrl = "http://remote.example.test")
        assertEquals("https://ha.example.test/api/webhook/wh1", HaUrls.webhookUrl(base, reg).toString())
    }
}
