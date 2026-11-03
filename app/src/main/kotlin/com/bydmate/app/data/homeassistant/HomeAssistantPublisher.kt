package com.bydmate.app.data.homeassistant

import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** What the Settings "test connection" button reports. */
sealed interface HaTestResult {
    data object InvalidUrl : HaTestResult
    data object EmptyToken : HaTestResult
    data object Unauthorized : HaTestResult
    data class Unreachable(val reason: String) : HaTestResult
    data class HttpError(val code: Int) : HaTestResult
    data object BadAnswer : HaTestResult

    /** Address and token work; the device and its [sensorCount] sensors are registered. */
    data class Connected(val sensorCount: Int) : HaTestResult
}

/**
 * Pushes the live snapshot to Home Assistant as a native mobile_app device.
 *
 * [onSample] is called from the poll loop on every tick and must cost nothing there: it only
 * checks a clock and a flag, then hands the work to its own IO scope. At most one update a
 * minute, and only while the car is on, plus one last update when it turns off so HA shows
 * "off" rather than a frozen "drive". A failed send is not retried or queued: the next minute
 * carries a fresher snapshot anyway, and the car is offline for long stretches (no SIM).
 */
@Singleton
class HomeAssistantPublisher @Inject constructor(
    private val settings: SettingsRepository,
    private val client: HomeAssistantClient,
    private val registrar: HomeAssistantRegistrar,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "publish crashed: ${e.javaClass.simpleName}") }
    )
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastAttemptMs = 0L
    @Volatile private var lastSentPowerState: Int? = null

    fun onSample(data: DiParsData, rangeKm: Double?, nowMs: Long) {
        if (!HaPublishPolicy.shouldPublish(data.powerState, lastSentPowerState, lastAttemptMs, nowMs)) return
        if (!inFlight.compareAndSet(false, true)) return
        lastAttemptMs = nowMs
        lastSentPowerState = data.powerState
        scope.launch {
            try {
                publish(HaSnapshot(data, rangeKm))
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** One full cycle: register if needed, register sensors if needed, send states. */
    internal suspend fun publish(snapshot: HaSnapshot) {
        val (base, token) = loadConfig() ?: return
        val registration = when (val outcome = registrar.ensureRegistered(base, token)) {
            is HaRegistrationOutcome.Registered -> outcome.registration
            else -> return logFailure("register", outcome)
        }
        val webhook = HaUrls.webhookUrl(base, registration) ?: return
        registrar.ensureSensors(webhook, registration, snapshot)?.let { return handleWebhookFailure("sensors", it) }
        val body = HaPayloads.updateStates(snapshot) ?: return
        when (val response = client.postWebhook(webhook, body)) {
            is HaResponse.Ok -> if (HaPayloads.notRegisteredIds(response.body).isNotEmpty()) registrar.forgetSensors()
            else -> handleWebhookFailure("update", response)
        }
    }

    /**
     * Checks the address and token the user typed (not yet saved), then registers the device
     * and its sensors so the user sees them in HA right away. [snapshot] seeds the first states.
     */
    suspend fun testConnection(rawUrl: String, token: String, snapshot: HaSnapshot?): HaTestResult {
        val base = HaUrls.normalizeBase(rawUrl) ?: return HaTestResult.InvalidUrl
        if (token.isBlank()) return HaTestResult.EmptyToken
        client.checkApi(base, token).toFailure()?.let { return it }
        val registration = when (val outcome = registrar.ensureRegistered(base, token)) {
            is HaRegistrationOutcome.Registered -> outcome.registration
            HaRegistrationOutcome.BadAnswer -> return HaTestResult.BadAnswer
            is HaRegistrationOutcome.Failed -> return outcome.response.toFailure() ?: HaTestResult.BadAnswer
        }
        val webhook = HaUrls.webhookUrl(base, registration) ?: return HaTestResult.InvalidUrl
        registrar.ensureSensors(webhook, registration, snapshot)?.let { failure ->
            if (failure is HaResponse.Gone) registrar.forgetRegistration()
            return failure.toFailure() ?: HaTestResult.BadAnswer
        }
        snapshot?.let { s -> HaPayloads.updateStates(s)?.let { client.postWebhook(webhook, it) } }
        return HaTestResult.Connected(HaSensorCatalog.ALL.size)
    }

    private suspend fun loadConfig(): Pair<HttpUrl, String>? {
        if (settings.getString(SettingsRepository.KEY_HA_ENABLED, "false") != "true") return null
        val base = HaUrls.normalizeBase(settings.getString(SettingsRepository.KEY_HA_URL, "")) ?: return null
        val token = settings.getString(SettingsRepository.KEY_HA_TOKEN, "").trim()
        return if (token.isEmpty()) null else base to token
    }

    private suspend fun handleWebhookFailure(step: String, response: HaResponse) {
        if (response is HaResponse.Gone) registrar.forgetRegistration()
        Log.w(TAG, "$step: ${response.describe()}")
    }

    private fun logFailure(step: String, outcome: HaRegistrationOutcome) {
        val reason = (outcome as? HaRegistrationOutcome.Failed)?.response?.describe() ?: "bad answer"
        Log.w(TAG, "$step: $reason")
    }

    private companion object {
        const val TAG = "HomeAssistant"

        fun HaResponse.toFailure(): HaTestResult? = when (this) {
            is HaResponse.Ok -> null
            HaResponse.Unauthorized -> HaTestResult.Unauthorized
            HaResponse.Gone -> HaTestResult.HttpError(HTTP_GONE)
            is HaResponse.HttpError -> HaTestResult.HttpError(code)
            is HaResponse.NetworkError -> HaTestResult.Unreachable(message)
        }

        // Never the body or the URL: both can carry the webhook id. A network error keeps only
        // its exception class, since UnknownHostException's text is the owner's HA domain and
        // logs get shared for diagnosis.
        fun HaResponse.describe(): String = when (this) {
            is HaResponse.Ok -> "ok"
            HaResponse.Unauthorized -> "unauthorized"
            HaResponse.Gone -> "gone, will re-register"
            is HaResponse.HttpError -> "HTTP $code"
            is HaResponse.NetworkError -> message.substringBefore(':')
        }

        const val HTTP_GONE = 410
    }
}

/** When the poll loop may hand a snapshot to Home Assistant. Pure, for tests. */
internal object HaPublishPolicy {
    const val MIN_INTERVAL_MS = 60_000L
    private const val POWER_OFF = 0

    fun shouldPublish(powerState: Int?, lastSentPowerState: Int?, lastAttemptMs: Long, nowMs: Long): Boolean {
        val carOn = powerState != null && powerState != POWER_OFF
        // The switch to off goes out at once, whatever the interval, so HA does not keep "drive".
        val turnedOff = powerState == POWER_OFF && lastSentPowerState != null && lastSentPowerState != POWER_OFF
        if (turnedOff) return true
        return carOn && (lastAttemptMs == 0L || nowMs - lastAttemptMs >= MIN_INTERVAL_MS)
    }
}
