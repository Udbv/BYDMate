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

/** Track points recorded before 27 keep their rows, with no temperature or wipers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Migration26to27Test {

    private val dbName = "migration-test-26-27.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun `old points keep their rows and get no conditions`() {
        helper.createDatabase(dbName, 26).use { old ->
            old.execSQL("INSERT INTO trip_points (trip_id, timestamp, lat, lon, altitude) VALUES (1, 1000, 0.0, 0.0, 120.0)")
        }
        val migrated = helper.runMigrationsAndValidate(dbName, 27, true, AppModule.MIGRATION_26_27)
        migrated.query("SELECT altitude, ext_temp, wiping FROM trip_points").use { c ->
            c.moveToFirst()
            assertEquals(120.0, c.getDouble(0), 1e-9)
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }
    }
}
