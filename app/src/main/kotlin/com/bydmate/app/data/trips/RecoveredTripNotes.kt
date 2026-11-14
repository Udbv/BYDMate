package com.bydmate.app.data.trips

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trips TripAnomalyDetector recovered in this process: the Telegram power-off report says so in
 * one line (TelegramReporter). In memory on purpose: the report of this power-off mentions them,
 * the next process starts with none.
 */
@Singleton
class RecoveredTripNotes @Inject constructor() {

    data class Note(val startTs: Long, val endTs: Long, val km: Double)

    private val notes = CopyOnWriteArrayList<Note>()

    fun add(note: Note) {
        notes.add(note)
    }

    fun all(): List<Note> = notes.toList()
}
