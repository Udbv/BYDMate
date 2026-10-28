package com.bydmate.app.data.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Зимний старт» as the template left it gets the stepped heat; an edited rule is left alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WinterStartRuleMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var ruleDao: RuleDao
    private val settings: SettingsRepository = mockk()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        ruleDao = db.ruleDao()
        coEvery { settings.isWinterStartMigrationDone() } returns false
        coEvery { settings.setWinterStartMigrationDone() } returns Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val triggers = TriggerDef.listToJson(
        listOf(
            TriggerDef("ExtTemp", "车外温度", "<", "15", "ExtTemp < 15"),
            TriggerDef("ServiceStart", "", "==", "true", "start", kind = "service_start"),
        ),
    )

    private suspend fun insert(vararg commands: String): Long = ruleDao.insert(
        RuleEntity(
            name = "Зимний старт",
            triggers = triggers,
            actions = ActionDef.listToJson(commands.map { ActionDef(it, it) }),
        ),
    )

    private suspend fun commandsOf(id: Long) = ActionDef.listFromJson(ruleDao.getById(id)!!.actions).map { it.command }

    private fun migration() = WinterStartRuleMigration(ruleDao, settings, db)

    @Test
    fun `template rule steps the seat down and warms the wheel for a minute`() = runTest {
        val id = insert("主驾座椅加热2档", "后视镜加热")
        assertEquals(1, migration().runOnce())
        val commands = commandsOf(id)
        assertEquals(listOf("主驾座椅加热3档", "方向盘加热", "后视镜加热", "delay"), commands.take(4))
        assertEquals("主驾座椅加热关闭", commands.last())
        val delays = ActionDef.listFromJson(ruleDao.getById(id)!!.actions).filter { it.kind == "delay" }
        assertEquals(8 * 60_000L, delays.sumOf { it.payload!!.toLong() })
        assertEquals(triggers, ruleDao.getById(id)!!.triggers)
        coVerify(exactly = 1) { settings.setWinterStartMigrationDone() }
    }

    @Test
    fun `edited rule is left alone`() = runTest {
        val id = insert("主驾座椅加热1档", "后视镜加热")
        assertEquals(0, migration().runOnce())
        assertEquals(listOf("主驾座椅加热1档", "后视镜加热"), commandsOf(id))
    }

    @Test
    fun `runs once`() = runTest {
        coEvery { settings.isWinterStartMigrationDone() } returns true
        val id = insert("主驾座椅加热2档", "后视镜加热")
        assertEquals(0, migration().runOnce())
        assertEquals(listOf("主驾座椅加热2档", "后视镜加热"), commandsOf(id))
    }
}
