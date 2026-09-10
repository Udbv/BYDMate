package com.bydmate.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripDebugLogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        TripDebugLog.setEnabled(context, false)
        TripDebugLog.files(context).forEach { it.delete() }
    }

    @After fun tearDown() {
        TripDebugLog.endTrip("test")
        TripDebugLog.setEnabled(context, false)
        TripDebugLog.files(context).forEach { it.delete() }
    }

    private fun body(f: File) = f.readText()

    @Test fun `off by default and writes nothing`() {
        assertFalse(TripDebugLog.isEnabled(context))
        TripDebugLog.startTrip(context, "test")
        TripDebugLog.event("READ", "should not be written")
        TripDebugLog.drain()
        assertFalse(TripDebugLog.active)
        assertTrue(TripDebugLog.files(context).isEmpty())
    }

    @Test fun `enabled writes a header and events`() {
        TripDebugLog.setEnabled(context, true)
        TripDebugLog.startTrip(context, "unit test")
        assertTrue(TripDebugLog.active)
        TripDebugLog.event("READ", "gaode=2 road='Садова'")
        TripDebugLog.event("PANEL", "guide icon=2 accepted=true")
        TripDebugLog.endTrip("done")

        val files = TripDebugLog.files(context)
        assertEquals(1, files.size)
        val text = body(files.first())
        assertTrue(text.contains("BYDMate trip debug log"))
        assertTrue(text.contains("reason: unit test"))
        assertTrue(text.contains("READ gaode=2 road='Садова'"))
        assertTrue(text.contains("PANEL guide icon=2 accepted=true"))
        assertTrue(text.contains("TRIP end: done"))
    }

    @Test fun `a second start does not split one drive`() {
        TripDebugLog.setEnabled(context, true)
        TripDebugLog.startTrip(context, "first")
        TripDebugLog.startTrip(context, "second")
        TripDebugLog.event("READ", "one line")
        TripDebugLog.endTrip("done")
        assertEquals(1, TripDebugLog.files(context).size)
    }

    @Test fun `changed suppresses repeats but lets a new value through`() {
        TripDebugLog.setEnabled(context, true)
        TripDebugLog.startTrip(context, "test")
        repeat(5) { TripDebugLog.changed("HUD", "frame", "gaode=2") }
        TripDebugLog.changed("HUD", "frame", "gaode=1")
        repeat(3) { TripDebugLog.changed("HUD", "frame", "gaode=1") }
        TripDebugLog.endTrip("done")

        val text = body(TripDebugLog.files(context).first())
        assertEquals(1, text.split("gaode=2").size - 1)
        assertEquals(1, text.split("gaode=1").size - 1)
    }

    @Test fun `events after the trip ends are dropped`() {
        TripDebugLog.setEnabled(context, true)
        TripDebugLog.startTrip(context, "test")
        TripDebugLog.endTrip("done")
        TripDebugLog.event("READ", "after the end")
        TripDebugLog.drain()
        assertFalse(body(TripDebugLog.files(context).first()).contains("after the end"))
    }

    @Test fun `only the newest files are kept`() {
        TripDebugLog.setEnabled(context, true)
        repeat(TripDebugLog.MAX_FILES + 4) { i ->
            TripDebugLog.startTrip(context, "trip $i")
            TripDebugLog.event("READ", "trip $i")
            TripDebugLog.endTrip("done")
            // Distinct names come from a seconds-resolution stamp; nudge the timestamps apart.
            TripDebugLog.files(context).firstOrNull()?.setLastModified(1_000_000L + i * 1000L)
        }
        assertTrue(TripDebugLog.files(context).size <= TripDebugLog.MAX_FILES)
    }

    @Test fun `switching the mode off closes an open trip`() {
        TripDebugLog.setEnabled(context, true)
        TripDebugLog.startTrip(context, "test")
        assertTrue(TripDebugLog.active)
        TripDebugLog.setEnabled(context, false)
        assertFalse(TripDebugLog.active)
        assertTrue(body(TripDebugLog.files(context).first()).contains("TRIP end: switch off"))
    }
}
