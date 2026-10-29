package com.bydmate.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A database at 24 is either upstream's (tombstones with a start time, no trip minutes) or the
 * fork's 4.0.1-dev.12 (trip minutes, tombstones without a start time): both reach 25 whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration24to25Test {

    private val dbName = "migration-test-24-25.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `upstream 24 gets the trip minutes`() {
        helper.createDatabase(dbName, 24).use { old ->
            old.execSQL("INSERT INTO trip_tombstones (byd_id, start_ts) VALUES (7, 1000)")
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 25, true, AppModule.MIGRATION_24_25)
        migrated.query("SELECT byd_id, start_ts FROM trip_tombstones").use { c ->
            c.moveToFirst()
            assertEquals(7L, c.getLong(0))
            assertEquals(1000L, c.getLong(1))
        }
        migrated.query("SELECT count(*) FROM trip_minutes").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `the fork's dev 12 database at 24 gets the tombstone start time and keeps its minutes`() {
        helper.createDatabase(dbName, 24).use { old ->
            // dev.12's 24: tombstones as at 23, plus trip_minutes with a row.
            old.execSQL("DROP TABLE trip_tombstones")
            old.execSQL("CREATE TABLE trip_tombstones (byd_id INTEGER PRIMARY KEY NOT NULL)")
            old.execSQL("INSERT INTO trip_tombstones (byd_id) VALUES (9)")
            old.execSQL(
                "CREATE TABLE trip_minutes (minute_ts INTEGER PRIMARY KEY NOT NULL, samples INTEGER NOT NULL, " +
                    "motors_kwh REAL NOT NULL, front_kwh REAL, rear_kwh REAL, regen_kwh REAL NOT NULL, " +
                    "climate_kwh REAL NOT NULL, other_kwh REAL NOT NULL, peak_kw REAL NOT NULL, min_kw REAL NOT NULL, " +
                    "avg_speed REAL, max_speed INTEGER, soc INTEGER)"
            )
            old.execSQL(
                "INSERT INTO trip_minutes (minute_ts, samples, motors_kwh, regen_kwh, climate_kwh, other_kwh, peak_kw, min_kw) " +
                    "VALUES (60000, 60, 0.4, 0.0, 0.02, 0.01, 50.0, -5.0)"
            )
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 25, true, AppModule.MIGRATION_24_25)
        migrated.query("SELECT byd_id, start_ts FROM trip_tombstones").use { c ->
            c.moveToFirst()
            assertEquals(9L, c.getLong(0))
            assertEquals(true, c.isNull(1))
        }
        migrated.query("SELECT count(*) FROM trip_minutes").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
}
