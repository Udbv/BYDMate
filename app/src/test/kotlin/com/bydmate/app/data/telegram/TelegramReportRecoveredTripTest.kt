package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.trips.RecoveredTripNotes
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The power-off report says in one line that the missing-trip detector recovered a trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReportRecoveredTripTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_794_000_000_000L

    private fun build(notes: List<RecoveredTripNotes.Note>, lang: String = "ru"): String {
        val lc = ctx.localizedContext(lang)
        val inputs = ReportInputs(diParsData(soc = 64), 312.0, null, null, null, null, recovered = notes)
        return TelegramReportBuilder.build(
            "BYDMate", "", setOf(ReportField.SOC), inputs, lang, ReportStrings { id, args -> lc.getString(id, *args) }, now,
        ).text
    }

    private val drive1 = RecoveredTripNotes.Note(now - 90 * 60_000L, now - 60 * 60_000L, 29.2)
    private val drive2 = RecoveredTripNotes.Note(now - 58 * 60_000L, now - 42 * 60_000L, 27.0)

    @Test
    fun `one recovered trip is one line with its time and distance`() {
        val text = build(listOf(drive1))
        val line = text.lines().single { it.startsWith("♻️") }
        assertEquals(
            "♻️ Восстановлена пропущенная поездка ${TelegramReportBuilder.formatTime(drive1.startTs)}–" +
                "${TelegramReportBuilder.formatTime(drive1.endTs)}, 29,2 км",
            line,
        )
    }

    @Test
    fun `several recovered trips are one line with their count and km`() {
        val text = build(listOf(drive1, drive2), lang = "en")
        assertEquals(1, text.lines().count { it.startsWith("♻️") })
        assertTrue(text, text.contains("♻️ Recovered missed trips: 2 (56.2 km)"))
    }

    @Test
    fun `no recovered trip, no line`() {
        assertFalse(build(emptyList()).contains("♻️"))
    }
}
