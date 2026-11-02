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

/** 28 adds «Мої точки» (#22): an empty table, the trips untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration27to28Test {

    private val dbName = "migration-test-27-28.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `the points table comes empty and takes a point`() {
        helper.createDatabase(dbName, 27).use { old ->
            old.execSQL("INSERT INTO trip_points (trip_id, timestamp, lat, lon) VALUES (1, 1000, 0.0, 0.0)")
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 28, true, AppModule.MIGRATION_27_28)
        migrated.query("SELECT COUNT(*) FROM road_points").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        migrated.execSQL(
            "INSERT INTO road_points (ts, last_ts, lat, lon, heading, type, passes, status) " +
                "VALUES (1, 1, 50.0, 30.0, NULL, 'SPEED_BUMP', 1, 'CANDIDATE')",
        )
        migrated.query("SELECT COUNT(*) FROM trip_points").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
}
