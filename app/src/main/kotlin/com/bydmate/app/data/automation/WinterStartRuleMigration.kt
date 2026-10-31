package com.bydmate.app.data.automation

import android.util.Log
import androidx.room.withTransaction
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot move of the «Зимний старт» template to a heat that steps down instead of staying on.
 *
 * The template switched the driver seat to level 2 for the whole drive. The new actions warm fast
 * and then back off ([stagedActions]): seat 3 and the steering wheel for the first minute, seat 2
 * to the third minute, seat 1 to the eighth, then the seat off; the mirrors as before. Only a rule
 * whose actions are still exactly the template's is rewritten: an edited rule is the owner's own.
 * Its triggers (the temperature threshold too) stay as they are. Same shape as
 * [TrunkRuleMigration], with its own done-flag.
 */
@Singleton
class WinterStartRuleMigration @Inject constructor(
    private val ruleDao: RuleDao,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
) {

    /** Returns the number of rules rewritten (0 when already migrated or nothing matched). */
    @Suppress("TooGenericExceptionCaught") // any failure leaves the flag unset for a retry
    suspend fun runOnce(): Int {
        if (settings.isWinterStartMigrationDone()) return 0
        return try {
            val migrated = db.withTransaction {
                val template = ruleDao.getAllList().filter { rule ->
                    ActionDef.listFromJson(rule.actions).map { it.command } == TEMPLATE_COMMANDS &&
                        TriggerDef.listFromJson(rule.triggers).any { it.kind == "service_start" }
                }
                template.forEach { ruleDao.update(it.copy(actions = ActionDef.listToJson(stagedActions()))) }
                template.size
            }
            settings.setWinterStartMigrationDone()
            Log.i(TAG, "Winter start migration: stepped heat in $migrated rule(s)")
            migrated
        } catch (e: Exception) {
            // Flag stays unset: the next start retries.
            Log.w(TAG, "Winter start migration failed: ${e.message}")
            0
        }
    }

    /**
     * One-shot move of the stepped «Зимний старт» from the outside temperature to the cabin's:
     * the heat is for the people inside, and a cabin that kept warm overnight (garage, sun)
     * needs none however cold it is out. The rule's own threshold and operator stay; only a rule
     * with the stepped actions and a service-start trigger is touched.
     */
    @Suppress("TooGenericExceptionCaught") // any failure leaves the flag unset for a retry
    suspend fun runCabinOnce(): Int {
        if (settings.getString(KEY_CABIN_DONE, "") == "true") return 0
        return try {
            val staged = stagedActions().map { it.command }
            val migrated = db.withTransaction {
                val rules = ruleDao.getAllList().filter { rule ->
                    val triggers = TriggerDef.listFromJson(rule.triggers)
                    ActionDef.listFromJson(rule.actions).map { it.command } == staged &&
                        triggers.any { it.kind == "service_start" } && triggers.any { it.param == OUTSIDE }
                }
                rules.forEach { rule ->
                    val triggers = TriggerDef.listFromJson(rule.triggers).map { t ->
                        if (t.param != OUTSIDE) t else cabinTrigger(t.operator, t.value)
                    }
                    ruleDao.update(rule.copy(triggers = TriggerDef.listToJson(triggers)))
                }
                rules.size
            }
            settings.setString(KEY_CABIN_DONE, "true")
            Log.i(TAG, "Winter start cabin migration: cabin temperature in $migrated rule(s)")
            migrated
        } catch (e: Exception) {
            Log.w(TAG, "Winter start cabin migration failed: ${e.message}")
            0
        }
    }

    companion object {
        private const val TAG = "WinterStartMigration"
        private const val KEY_CABIN_DONE = "winter_start_cabin_v1"
        private const val OUTSIDE = "ExtTemp"
        private const val CABIN = "InsideTemp"

        /** The cabin temperature condition, named like the editor names it. */
        fun cabinTrigger(operator: String, value: String) =
            TriggerDef(CABIN, "车内温度", operator, value, "Темп. в салоне $operator $value°C")

        /** The template's actions before the steps: driver seat 2, mirror heat. */
        val TEMPLATE_COMMANDS = listOf("主驾座椅加热2档", "后视镜加热")

        /** One delay action holds at most a minute (ActionDispatcher.MAX_DELAY_MS). */
        private const val MINUTE_MS = 60_000L

        private fun param(command: String, name: String) = ActionDef(command, name)

        private fun minutes(n: Int): List<ActionDef> =
            List(n) { ActionDef("delay", "Пауза 60 с", kind = "delay", payload = MINUTE_MS.toString()) }

        /** Seat 3 and wheel 1 min, seat 2 to 3 min, seat 1 to 8 min, then off; mirrors on. */
        fun stagedActions(): List<ActionDef> =
            listOf(
                param("主驾座椅加热3档", "Подогрев водителя 3"),
                param("方向盘加热", "Подогрев руля вкл"),
                param("后视镜加热", "Подогрев зеркал вкл"),
            ) + minutes(1) + listOf(
                param("主驾座椅加热2档", "Подогрев водителя 2"),
                param("关闭方向盘加热", "Подогрев руля выкл"),
            ) + minutes(2) + listOf(
                param("主驾座椅加热1档", "Подогрев водителя 1"),
            ) + minutes(5) + listOf(
                param("主驾座椅加热关闭", "Подогрев водителя выкл"),
            )
    }
}
