package com.bydmate.app.data.vehicle

import android.content.pm.PackageManager
import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.helper.KeptDisabledApps

/**
 * App half of "keep the stock multimedia apps disabled": reads the per-app choice from the
 * settings and hands it to the daemon as two masks. Called when the daemon comes up (service
 * start, so every boot and every quickboot wake, which restarts the app), on every screen wake and
 * whenever the driver flips a switch. The daemon rate-limits and does nothing for apps already in
 * the wanted state; a driver who never chose anything sends nothing.
 */
object KeptDisabledAppsSync {

    suspend fun choices(settingsRepository: SettingsRepository): Map<String, String> =
        KeptDisabledApps.ALLOWLIST.associate { app ->
            app.id to settingsRepository.getString(KeptDisabledApps.settingsKey(app), "")
        }

    /** What the system says about one app right now, for the settings row. */
    enum class SystemState { ENABLED, DISABLED, MISSING }

    /** One settings row: the switch is on only when the driver chose "keep disabled". */
    data class Row(val app: KeptDisabledApps.App, val checked: Boolean, val systemState: SystemState)

    /** PackageManager.getApplicationEnabledSetting; null when the package is not installed. */
    fun enabledSettingReader(packageManager: PackageManager): (String) -> Int? = { pkg ->
        runCatching { packageManager.getApplicationEnabledSetting(pkg) }.getOrNull()
    }

    fun systemStateOf(enabledSetting: Int?): SystemState = when (enabledSetting) {
        null -> SystemState.MISSING
        KeptDisabledApps.STATE_DEFAULT, KeptDisabledApps.STATE_ENABLED -> SystemState.ENABLED
        else -> SystemState.DISABLED
    }

    fun rows(choices: Map<String, String>, enabledSetting: (String) -> Int?): List<Row> =
        KeptDisabledApps.ALLOWLIST.map { app ->
            Row(app, choices[app.id] == "true", systemStateOf(enabledSetting(app.pkg)))
        }

    /**
     * The driver flipped the switch of [id]: on = keep disabled (disabled now if it is enabled),
     * off = enable it again. An id outside the allowlist is ignored.
     */
    suspend fun setChoice(
        helperClient: HelperClient,
        settingsRepository: SettingsRepository,
        id: String,
        keepDisabled: Boolean,
    ): Int? {
        val app = KeptDisabledApps.ALLOWLIST.firstOrNull { it.id == id } ?: run {
            Log.w(KeptDisabledApps.TAG, "choice refused: unknown app id")
            return null
        }
        settingsRepository.setString(KeptDisabledApps.settingsKey(app), keepDisabled.toString())
        Log.i(KeptDisabledApps.TAG, "choice ${app.id} keepDisabled=$keepDisabled")
        return apply(helperClient, settingsRepository, "settings")
    }

    /** Returns the daemon status, or null when nothing was sent or the daemon is unreachable. */
    suspend fun apply(helperClient: HelperClient, settingsRepository: SettingsRepository, trigger: String): Int? {
        val (keep, restore) = KeptDisabledApps.masksOf(choices(settingsRepository))
        if (keep == 0 && restore == 0) return null
        val status = helperClient.keepAppsDisabled(keep, restore)
        val line = "apply on $trigger: keep=$keep restore=$restore status=$status"
        if (status == null || status < 0) Log.w(KeptDisabledApps.TAG, line) else Log.i(KeptDisabledApps.TAG, line)
        return status
    }
}
