package com.bydmate.app.data.homeassistant

import android.os.Build
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of making sure this head unit is a registered Home Assistant device. */
sealed interface HaRegistrationOutcome {
    data class Registered(val registration: HaRegistration) : HaRegistrationOutcome

    /** HA answered, but not with a webhook id (a proxy page, an old HA without mobile_app). */
    data object BadAnswer : HaRegistrationOutcome

    data class Failed(val response: HaResponse) : HaRegistrationOutcome
}

/**
 * Owns the persisted half of the Mobile App integration: the device id, the webhook id HA
 * issued, and whether the sensors exist on that webhook. Registration runs once per base URL;
 * a 410 from the webhook or a `not_registered` answer resets the matching step.
 */
@Singleton
class HomeAssistantRegistrar @Inject constructor(
    private val settings: SettingsRepository,
    private val client: HomeAssistantClient,
) {
    // The poll-loop publisher and the Settings "test" button can race to register; two
    // registrations would leave a duplicate device in HA.
    private val mutex = Mutex()

    /** Overridable in tests: android.os.Build fields are null on the JVM. */
    internal var describeDevice: (String) -> HaDeviceInfo = { deviceId ->
        HaDeviceInfo(
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
            model = Build.MODEL.orEmpty().ifBlank { DEFAULT_MODEL },
            osVersion = Build.VERSION.RELEASE.orEmpty(),
        )
    }

    suspend fun ensureRegistered(base: HttpUrl, token: String): HaRegistrationOutcome = mutex.withLock {
        storedRegistration(base)?.let { return@withLock HaRegistrationOutcome.Registered(it) }
        when (val response = client.register(base, token, HaPayloads.registration(describeDevice(deviceId())))) {
            is HaResponse.Ok -> {
                val registration = HaRegistration.parse(response.body) ?: return@withLock HaRegistrationOutcome.BadAnswer
                store(base, registration)
                HaRegistrationOutcome.Registered(registration)
            }
            else -> HaRegistrationOutcome.Failed(response)
        }
    }

    /**
     * Registers every sensor of [HaSensorCatalog] on [webhook] unless that was already done for
     * this webhook id and schema version. Returns null on success, else the failing answer;
     * a partial run is simply repeated next time (register_sensor is an upsert in HA).
     */
    suspend fun ensureSensors(webhook: HttpUrl, registration: HaRegistration, snapshot: HaSnapshot?): HaResponse? {
        val marker = "${registration.webhookId}:${HaSensorCatalog.SCHEMA_VERSION}"
        if (settings.getString(SettingsRepository.KEY_HA_SENSORS_REGISTERED, "") == marker) return null
        for (spec in HaSensorCatalog.ALL) {
            val response = client.postWebhook(webhook, HaPayloads.registerSensor(spec, snapshot))
            if (response !is HaResponse.Ok) return response
        }
        settings.setString(SettingsRepository.KEY_HA_SENSORS_REGISTERED, marker)
        return null
    }

    /** HA forgot the device (410): drop the webhook so the next call registers anew. */
    suspend fun forgetRegistration() = mutex.withLock {
        settings.setStrings(
            mapOf(
                SettingsRepository.KEY_HA_WEBHOOK_ID to "",
                SettingsRepository.KEY_HA_CLOUDHOOK_URL to "",
                SettingsRepository.KEY_HA_REMOTE_UI_URL to "",
                SettingsRepository.KEY_HA_REGISTERED_BASE to "",
                SettingsRepository.KEY_HA_SENSORS_REGISTERED to "",
            )
        )
    }

    /** HA lost some entities: the next publish registers the sensors again. */
    suspend fun forgetSensors() = settings.setString(SettingsRepository.KEY_HA_SENSORS_REGISTERED, "")

    private suspend fun storedRegistration(base: HttpUrl): HaRegistration? {
        val webhookId = settings.getString(SettingsRepository.KEY_HA_WEBHOOK_ID, "")
        val registeredBase = settings.getString(SettingsRepository.KEY_HA_REGISTERED_BASE, "")
        // A webhook id from another HA instance is useless here.
        if (webhookId.isEmpty() || registeredBase != base.toString()) return null
        return HaRegistration(
            webhookId = webhookId,
            cloudhookUrl = settings.getString(SettingsRepository.KEY_HA_CLOUDHOOK_URL, "").ifEmpty { null },
            remoteUiUrl = settings.getString(SettingsRepository.KEY_HA_REMOTE_UI_URL, "").ifEmpty { null },
        )
    }

    private suspend fun store(base: HttpUrl, registration: HaRegistration) {
        settings.setStrings(
            mapOf(
                SettingsRepository.KEY_HA_WEBHOOK_ID to registration.webhookId,
                SettingsRepository.KEY_HA_CLOUDHOOK_URL to registration.cloudhookUrl.orEmpty(),
                SettingsRepository.KEY_HA_REMOTE_UI_URL to registration.remoteUiUrl.orEmpty(),
                SettingsRepository.KEY_HA_REGISTERED_BASE to base.toString(),
                SettingsRepository.KEY_HA_SENSORS_REGISTERED to "",
            )
        )
    }

    /** Created once and kept, so re-registering after a 410 updates the same HA device. */
    private suspend fun deviceId(): String {
        settings.getString(SettingsRepository.KEY_HA_DEVICE_ID, "").takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        settings.setString(SettingsRepository.KEY_HA_DEVICE_ID, id)
        return id
    }

    private companion object {
        const val DEFAULT_MODEL = "DiLink"
    }
}
