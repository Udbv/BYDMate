package com.bydmate.app.data.automation

import android.content.Context
import android.util.Log
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the two rules almost every car wants on start-up, once, so they survive updates without
 * being re-added by hand.
 *
 * Seeding runs a single time and is remembered in settings. After that the rules are ordinary
 * user rules: edit them, disable them or delete them and nothing here will bring them back or
 * overwrite the changes. A rule whose app is not installed is seeded disabled rather than
 * skipped, so it is visible and one tap away once the app appears.
 */
@Singleton
class DefaultRulesSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleDao: RuleDao,
    private val settings: SettingsRepository,
) {
    companion object {
        private const val TAG = "DefaultRulesSeeder"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val WAZE_PACKAGE = "com.waze"

        /** Long enough for a cold player to attach its MediaSession before play is sent. */
        const val PLAYER_WARMUP_MS = 6_000L

        /** The service-start trigger: fires once per ignition, shortly after BYDMate wakes. */
        private fun onStart() = TriggerDef(
            param = "",
            chineseName = "",
            operator = "",
            value = "",
            displayName = "Запуск BYDMate",
            kind = "service_start",
        )

        private fun launch(pkg: String, label: String) = ActionDef(
            command = "app_launch",
            displayName = label,
            kind = "app_launch",
            payload = """{"packageName":"$pkg"}""",
        )
    }

    /** Returns how many rules were inserted; 0 when already seeded. */
    suspend fun runOnce(): Int {
        if (settings.isDefaultRulesSeeded()) return 0
        return try {
            var inserted = 0
            for (rule in build()) {
                ruleDao.insert(rule)
                inserted++
            }
            settings.setDefaultRulesSeeded()
            Log.i(TAG, "seeded $inserted default rules")
            inserted
        } catch (e: Exception) {
            // A failure here must never block start-up; the next launch tries again because the
            // flag is only set on success.
            Log.w(TAG, "seeding default rules failed: ${e.message}")
            0
        }
    }

    internal fun build(): List<RuleEntity> = listOf(
        RuleEntity(
            name = "Spotify при запуске",
            enabled = isInstalled(SPOTIFY_PACKAGE),
            triggers = TriggerDef.listToJson(listOf(onStart())),
            actions = ActionDef.listToJson(
                listOf(
                    launch(SPOTIFY_PACKAGE, "Открыть Spotify"),
                    ActionDef(
                        command = "delay",
                        displayName = "Пауза ${PLAYER_WARMUP_MS / 1000} с",
                        kind = "delay",
                        payload = PLAYER_WARMUP_MS.toString(),
                    ),
                    ActionDef(
                        command = "media_play",
                        displayName = "Включить воспроизведение",
                        kind = "media_play",
                    ),
                )
            ),
            cooldownSeconds = 300,
            fireOncePerTrip = true,
        ),
        RuleEntity(
            name = "Waze при запуске",
            enabled = isInstalled(WAZE_PACKAGE),
            triggers = TriggerDef.listToJson(listOf(onStart())),
            actions = ActionDef.listToJson(listOf(launch(WAZE_PACKAGE, "Открыть Waze"))),
            cooldownSeconds = 300,
            fireOncePerTrip = true,
        ),
    )

    private fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
}
