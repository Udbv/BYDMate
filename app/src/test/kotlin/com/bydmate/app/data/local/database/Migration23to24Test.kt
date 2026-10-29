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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration23to24Test {

    private val dbName = "migration-test-23-24.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `23 to 24 adds an empty trip_minutes table and keeps the trips`() {
        helper.createDatabase(dbName, 23).use { old ->
            old.execSQL(
                "INSERT INTO trips (start_ts, end_ts, distance_km, kwh_consumed, cost, source) " +
                    "VALUES (1000, 2000, 50.5, 9.1, 1.82, 'energydata')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(dbName, 24, true, AppModule.MIGRATION_23_24)

        migrated.query("SELECT count(*) FROM trips").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        migrated.execSQL(
            "INSERT INTO trip_minutes (minute_ts, samples, motors_kwh, front_kwh, rear_kwh, regen_kwh, " +
                "climate_kwh, other_kwh, peak_kw, min_kw, avg_speed, max_speed, soc) " +
                "VALUES (60000, 58, 0.4, 0.1, 0.3, -0.05, 0.02, 0.01, 85.0, -20.0, 54.0, 71, 80)"
        )
        migrated.query("SELECT count(*), sum(rear_kwh) FROM trip_minutes").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
            assertEquals(0.3, c.getDouble(1), 1e-9)
        }
    }
}
