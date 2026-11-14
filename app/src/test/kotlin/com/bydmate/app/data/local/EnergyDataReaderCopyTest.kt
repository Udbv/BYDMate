package com.bydmate.app.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2026-11-14 11:00:10: `Sync: .../files/vehicle_ec.db: open failed: ENOENT` - the app's external
 * files folder was not there right after boot, the import failed. The private copy now creates the
 * folder first and falls back to internal storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EnergyDataReaderCopyTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var energyDir: File

    @Before
    fun setUp() {
        energyDir = File(app.cacheDir, "energydata-copy-test").apply { deleteRecursively(); mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(File(energyDir, "EC_database.db"), null)
        db.execSQL(
            """CREATE TABLE EnergyConsumption (
                _id INTEGER PRIMARY KEY, start_timestamp INTEGER, end_timestamp INTEGER,
                duration INTEGER, trip REAL, electricity REAL, is_deleted INTEGER DEFAULT 0)"""
        )
        db.execSQL("INSERT INTO EnergyConsumption VALUES (1, 1000, 1600, 600, 5.0, 1.2, 0)")
        db.execSQL("INSERT INTO EnergyConsumption VALUES (2, 5000, 9000, 4000, 29.2, 10.0, 0)")
        db.close()
    }

    private fun reader(external: File?) = object : EnergyDataReader(object : ContextWrapper(app) {
        override fun getExternalFilesDir(type: String?): File? = external
    }) {
        override fun energyDataDir(): File = energyDir
    }

    @Test
    fun `a missing external files folder is created before the copy`() = runTest {
        val external = File(app.cacheDir, "ext-not-yet-${System.nanoTime()}/files")
        val rows = reader(external).readTripsSince(0L)
        assertEquals(2, rows.size)
        assertTrue(File(external, "vehicle_ec.db").exists())
    }

    @Test
    fun `an unusable external folder falls back to internal storage`() = runTest {
        // A plain file where the folder should be: mkdirs() cannot create it.
        val blocker = File(app.cacheDir, "ext-blocked-${System.nanoTime()}").apply { writeText("x") }
        val rows = reader(File(blocker, "files")).readTripsSince(0L)
        assertEquals(2, rows.size)
        assertTrue(File(app.filesDir, "vehicle_ec.db").exists())
    }

    @Test
    fun `no external storage at all falls back to internal storage`() = runTest {
        assertEquals(2, reader(null).readTripsSince(0L).size)
    }

    @Test
    fun `the heal probe reads its own copy, rows ending since the bound`() = runTest {
        val rows = reader(null).readTripsForProbe(sinceSec = 2000L)
        assertEquals(listOf(2L), rows!!.map { it.id })
        assertTrue(File(app.filesDir, "vehicle_ec_probe.db").exists())
    }
}
