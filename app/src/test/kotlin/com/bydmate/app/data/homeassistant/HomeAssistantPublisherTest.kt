package com.bydmate.app.data.homeassistant

import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * End to end against MockWebServer. The app accepts https only and the test server speaks
 * plain http, so an interceptor points the https test host at the server.
 */
@RunWith(RobolectricTestRunner::class)
class HomeAssistantPublisherTest {

    private lateinit var server: MockWebServer
    private val store = mutableMapOf<String, String>()
    private lateinit var publisher: HomeAssistantPublisher
    private lateinit var registrar: HomeAssistantRegistrar

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val http = OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url.newBuilder()
                    .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
            .build()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getString(any(), any()) } answers { store[firstArg()] ?: secondArg() }
        coEvery { settings.setString(any(), any()) } answers { store[firstArg()] = secondArg() }
        coEvery { settings.setStrings(any()) } answers { store.putAll(firstArg<Map<String, String>>()) }
        val client = HomeAssistantClient(http)
        registrar = HomeAssistantRegistrar(settings, client).apply {
            describeDevice = { HaDeviceInfo(it, "test", "DiLink", "13") }
        }
        publisher = HomeAssistantPublisher(settings, client, registrar)
        store[SettingsRepository.KEY_HA_ENABLED] = "true"
        store[SettingsRepository.KEY_HA_URL] = BASE
        store[SettingsRepository.KEY_HA_TOKEN] = "secret-token"
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `first publish registers device and sensors, then updates`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"webhook_id":"wh1","cloudhook_url":null}"""))
        repeat(HaSensorCatalog.ALL.size) { server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true}""")) }
        server.enqueue(MockResponse().setBody("{}"))

        publisher.publish(HaSnapshot(data(soc = 70, powerState = 1), 310.0))

        val reg = server.takeRequest()
        assertEquals("/api/mobile_app/registrations", reg.path)
        assertEquals("Bearer secret-token", reg.getHeader("Authorization"))
        assertEquals("bydmate", JSONObject(reg.body.readUtf8()).getString("app_id"))
        repeat(HaSensorCatalog.ALL.size) {
            val r = server.takeRequest()
            assertEquals("/api/webhook/wh1", r.path)
            // The webhook id is the credential: the long-lived token must not go there.
            assertEquals(null, r.getHeader("Authorization"))
            assertEquals("register_sensor", JSONObject(r.body.readUtf8()).getString("type"))
        }
        val update = server.takeRequest()
        assertEquals("update_sensor_states", JSONObject(update.body.readUtf8()).getString("type"))
        assertEquals("wh1", store[SettingsRepository.KEY_HA_WEBHOOK_ID])
        assertEquals("wh1:${HaSensorCatalog.SCHEMA_VERSION}", store[SettingsRepository.KEY_HA_SENSORS_REGISTERED])
    }

    @Test fun `registered device only sends the update`() = runBlocking {
        seedRegistered()
        server.enqueue(MockResponse().setBody("{}"))

        publisher.publish(HaSnapshot(data(soc = 70, powerState = 1), null))

        assertEquals(1, server.requestCount)
        assertEquals("update_sensor_states", JSONObject(server.takeRequest().body.readUtf8()).getString("type"))
    }

    @Test fun `410 forgets the registration`() = runBlocking {
        seedRegistered()
        server.enqueue(MockResponse().setResponseCode(410))

        publisher.publish(HaSnapshot(data(soc = 70, powerState = 1), null))

        assertEquals("", store[SettingsRepository.KEY_HA_WEBHOOK_ID])
    }

    @Test fun `not_registered answer forgets the sensors`() = runBlocking {
        seedRegistered()
        server.enqueue(MockResponse().setBody("""{"bydmate_soc":{"success":false,"error":{"code":"not_registered"}}}"""))

        publisher.publish(HaSnapshot(data(soc = 70, powerState = 1), null))

        assertEquals("", store[SettingsRepository.KEY_HA_SENSORS_REGISTERED])
        assertEquals("wh1", store[SettingsRepository.KEY_HA_WEBHOOK_ID])
    }

    @Test fun `network failure does not throw and keeps the registration`() = runBlocking {
        seedRegistered()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        publisher.publish(HaSnapshot(data(soc = 70, powerState = 1), null))

        assertEquals("wh1", store[SettingsRepository.KEY_HA_WEBHOOK_ID])
    }

    @Test fun `disabled integration sends nothing`() = runBlocking {
        store[SettingsRepository.KEY_HA_ENABLED] = "false"
        publisher.publish(HaSnapshot(data(soc = 70, powerState = 1), null))
        assertEquals(0, server.requestCount)
    }

    @Test fun `test connection reports a bad token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(HaTestResult.Unauthorized, publisher.testConnection(BASE, "bad", null))
    }

    @Test fun `test connection rejects http and empty token without a request`() = runBlocking {
        assertEquals(HaTestResult.InvalidUrl, publisher.testConnection("http://ha.example.test", "t", null))
        assertEquals(HaTestResult.EmptyToken, publisher.testConnection(BASE, " ", null))
        assertEquals(0, server.requestCount)
    }

    @Test fun `test connection registers device and sensors`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"message":"API running."}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"webhook_id":"wh9"}"""))
        repeat(HaSensorCatalog.ALL.size) { server.enqueue(MockResponse().setResponseCode(201).setBody("{}")) }

        val result = publisher.testConnection(BASE, "secret-token", null)

        assertEquals(HaTestResult.Connected(HaSensorCatalog.ALL.size), result)
        assertEquals("/api/", server.takeRequest().path)
        assertTrue(store[SettingsRepository.KEY_HA_DEVICE_ID].orEmpty().isNotEmpty())
    }

    @Test fun `publish policy sends once a minute while on and once on power off`() {
        assertTrue(HaPublishPolicy.shouldPublish(1, null, 0L, 1_000L))
        assertEquals(false, HaPublishPolicy.shouldPublish(1, 1, 1_000L, 30_000L))
        assertTrue(HaPublishPolicy.shouldPublish(2, 1, 1_000L, 61_000L))
        assertTrue(HaPublishPolicy.shouldPublish(0, 2, 50_000L, 51_000L))
        assertEquals(false, HaPublishPolicy.shouldPublish(0, 0, 0L, 500_000L))
        assertEquals(false, HaPublishPolicy.shouldPublish(null, null, 0L, 500_000L))
    }

    private fun seedRegistered() {
        store[SettingsRepository.KEY_HA_WEBHOOK_ID] = "wh1"
        store[SettingsRepository.KEY_HA_REGISTERED_BASE] = HaUrls.normalizeBase(BASE).toString()
        store[SettingsRepository.KEY_HA_SENSORS_REGISTERED] = "wh1:${HaSensorCatalog.SCHEMA_VERSION}"
    }

    private fun data(soc: Int?, powerState: Int?) = DiParsData(
        soc = soc, speed = null, mileage = 12345.67, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = 25, minBatTemp = null, chargingStatus = 0,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = 13.8,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = 4, gear = null,
        powerState = powerState, insideTemp = 21, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = 0, doorFR = 0, doorRL = 0, doorRR = 0,
        windowFL = 0, windowFR = 0, windowRL = 0, windowRR = 0, sunroof = null,
        trunk = 2, hood = 0, seatbeltFL = null, lockFL = 2, tirePressFL = 250,
        tirePressFR = 250, tirePressRL = 245, tirePressRR = 245, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
    )

    private companion object {
        const val BASE = "https://ha.example.test"
    }
}
