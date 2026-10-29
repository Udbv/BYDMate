package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Trip minutes recorded before 26 keep their rows, with the weather unknown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration25to26Test {

    private val dbName = "migration-test-25-26.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `old minutes keep their rows and get no weather`() {
        helper.createDatabase(dbName, 25).use { old ->
            old.execSQL(
                "INSERT INTO trip_minutes (minute_ts, samples, motors_kwh, regen_kwh, climate_kwh, other_kwh, peak_kw, min_kw) " +
                    "VALUES (60000, 60, 0.4, 0.0, 0.02, 0.01, 50.0, -5.0)"
            )
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 26, true, AppModule.MIGRATION_25_26)
        migrated.query("SELECT motors_kwh, wiper_samples, ext_temp FROM trip_minutes").use { c ->
            c.moveToFirst()
            assertEquals(0.4, c.getDouble(0), 1e-9)
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
    }
}
