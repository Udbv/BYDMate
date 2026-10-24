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

    @Test fun `writes nothing while switched off`() {
        assertFalse(TripDebugLog.isEnabled(context))
        TripDebugLog.startTrip(context, "test")
        TripDebugLog.event("READ", "should not be written")
        TripDebugLog.drain()
        assertFalse(TripDebugLog.active)
        assertTrue(TripDebugLog.files(context).isEmpty())
    }

    @Test fun `enabled writes a header and events`() {
        TripDebugLog.setEnabled(context, true)
        // Switching on already opened the trip, so the header carries that reason; the
        // startTrip below is the deliberate no-op that keeps one drive in one file.
        TripDebugLog.startTrip(context, "unit test")
        assertTrue(TripDebugLog.active)
        TripDebugLog.event("READ", "gaode=2 road='Садова'")
        TripDebugLog.event("PANEL", "guide icon=2 accepted=true")
        TripDebugLog.endTrip("done")

        val files = TripDebugLog.files(context)
        assertEquals(1, files.size)
        val text = body(files.first())
        assertTrue(text.contains("BYDMate trip debug log"))
        assertTrue(text.contains("reason: switch on"))
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

    @Test fun `switching on starts recording immediately, not at the next ignition`() {
        // The regression this exists for: startTrip() was reachable only from
        // TrackingService.onCreate, and the service is long since running by the time anyone
        // opens settings - so switching this on did nothing until the car was restarted, and a
        // drive was lost with an empty folder to show for it.
        assertFalse(TripDebugLog.active)

        TripDebugLog.setEnabled(context, true)

        assertTrue("a trip must be open the moment the switch goes on", TripDebugLog.active)
        TripDebugLog.event("READ", "recorded without any restart")
        TripDebugLog.drain()
        val files = TripDebugLog.files(context)
        assertEquals(1, files.size)
        assertTrue(body(files.first()).contains("recorded without any restart"))
        assertTrue("the header must say why it opened", body(files.first()).contains("switch on"))
    }

    @Test fun `the development channel records unless it is switched off`() {
        // Anti-vacuity: this reads the stored value's DEFAULT, so the key must be absent.
        context.getSharedPreferences(TripDebugLog.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(TripDebugLog.KEY_ENABLED).apply()

        assertEquals(
            "dev builds default to recording; stable builds do not",
            com.bydmate.app.BuildConfig.DEFAULT_UPDATE_CHANNEL == "dev",
            TripDebugLog.isEnabled(context),
        )
        assertEquals(TripDebugLog.DEFAULT_ENABLED, TripDebugLog.isEnabled(context))

        // An explicit "off" still wins over the default, and survives a refresh.
        TripDebugLog.setEnabled(context, false)
        TripDebugLog.refresh(context)
        assertFalse(TripDebugLog.isEnabled(context))
    }
}
