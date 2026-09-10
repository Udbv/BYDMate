package com.bydmate.app.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebhookTelemetryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WebhookTelemetryClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        client = WebhookTelemetryClient(OkHttpClient())
    }

    /**
     * The webhook address, always as the literal loopback IP.
     *
     * Not `server.url(path)`: that builds the host from a reverse lookup of the bound address, so
     * it returns whatever name the machine happens to map 127.0.0.1 to. On a host with Docker
     * Desktop installed that is `kubernetes.docker.internal`, which the client refuses -- rightly,
     * since the app permits cleartext only to localhost and 127.0.0.1
     * (res/xml/network_security_config.xml). Every request then failed before it left the client
     * and the whole suite looked like a MockWebServer problem for as long as it existed.
     */
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    @After fun tearDown() { server.shutdown() }

    private fun telemetry(): JSONObject = JSONObject().apply {
        put("utc", 1_700_000_000L)
        put("soc", 73)
        put("power", 12.5)
    }

    @Test fun `posts telemetry json to the configured url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val payload = telemetry()

        val result = client.send(url("/tlm"), null, payload)

        assertTrue(result.isSuccess)
        val recorded = requireNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)) { "no request reached the mock server" }
        assertEquals("POST", recorded.method)
        assertEquals("/tlm", recorded.path)
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals(payload.toString(), recorded.body.readUtf8())
    }

    @Test fun `secret goes out as Authorization Bearer header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.send(url("/tlm"), "s3cret", telemetry())

        assertTrue(result.isSuccess)
        assertEquals("Bearer s3cret", requireNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)) { "no request reached the mock server" }.getHeader("Authorization"))
    }

    @Test fun `blank secret sends no Authorization header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        client.send(url("/tlm"), "   ", telemetry())

        assertNull(requireNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS)) { "no request reached the mock server" }.getHeader("Authorization"))
    }

    @Test fun `non-2xx response returns failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))

        val result = client.send(url("/tlm"), null, telemetry())

        assertTrue(result.isFailure)
        assertEquals("HTTP 503", result.exceptionOrNull()?.message)
    }

    @Test fun `invalid url fails without a network call`() = runTest {
        val result = client.send("ftp://example.com/tlm", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }

    @Test fun `plain http to a non-loopback host fails without a network call`() = runTest {
        // network_security_config.xml allows cleartext only on localhost/127.0.0.1;
        // any other http host would be silently blocked by the platform, so reject it up front.
        val result = client.send("http://192.168.1.10:8123/api/webhook/x", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }

    @Test fun `garbage url fails without a network call`() = runTest {
        val result = client.send("not a url", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }

    @Test fun `IPv6 loopback literal is refused - the platform blocks cleartext to it`() = runTest {
        // Deliberate: network_security_config.xml permits cleartext only for "localhost" and
        // "127.0.0.1", so an http://[::1]:port/ webhook would be killed by the platform after we
        // had already handed over the payload. Better to refuse it here, with a message the user
        // sees in settings.
        val result = client.send("http://[::1]:8080/tlm", null, telemetry())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no request must reach the network", 0, server.requestCount)
    }
}
