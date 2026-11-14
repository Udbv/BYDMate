package com.bydmate.app.data.trips

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripMinuteDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.data.repository.OdometerMarks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How far the last anomaly check got: its time and the odometer it had reached. */
interface AnomalyCheckpoint {
    fun load(): Pair<Long, Double?>?
    fun save(ts: Long, km: Double?)
}

class AnomalyCheckpointPrefs(private val prefs: SharedPreferences) : AnomalyCheckpoint {
    override fun load(): Pair<Long, Double?>? {
        if (!prefs.contains(KEY_TS)) return null
        val km = if (prefs.contains(KEY_KM)) prefs.getFloat(KEY_KM, 0f).toDouble() else null
        return prefs.getLong(KEY_TS, 0L) to km
    }

    override fun save(ts: Long, km: Double?) {
        val edit = prefs.edit().putLong(KEY_TS, ts)
        if (km != null) edit.putFloat(KEY_KM, km.toFloat()) else edit.remove(KEY_KM)
        edit.apply()
    }

    companion object {
        const val PREFS_NAME = "trip_anomaly"
        private const val KEY_TS = "checked_until_ts"
        private const val KEY_KM = "checked_until_km"
    }
}

/**
 * Missing-trip detector (2026-11-14: ~1 h of driving was not in the trip list). At every service
 * start and at every end of a drive it compares where the odometer moved (odometer marks: one
 * entry per process and the gaps between them, [TripAnomalyScan]) with the trips that cover it:
 * stored trips, the passive-mode shadow trips still waiting for an import and the trip open right
 * now. More than [TripAnomalyScan.MIN_UNCOVERED_KM] of odometer no trip covers is a lost drive:
 * one log line `TRIP anomaly uncovered=<km> soc=<from>-><to> window=<start>..<end>` and a trip
 * rebuilt from the odometer, the per-minute rows (energy, SoC, temperature) and the time window,
 * stored with source [TripSource.RECOVERED]; its GPS points are attached by time and the power-off
 * report gets one line about it ([RecoveredTripNotes]). A SoC drop of
 * [TripAnomalyScan.MIN_SOC_DROP] points or more without odometer movement is standing consumption
 * (warm-up, climate while parked): logged, never a trip.
 *
 * Each check continues from where the last one got ([AnomalyCheckpoint]: time and odometer), so a
 * recovered trip the owner deleted does not come back. The very first check after the update has
 * no checkpoint: it looks back [HISTORY_LOOKBACK_MS], as far as the odometer marks reach, and
 * recovers the drives lost before the update.
 */
@Suppress("LongParameterList") // the data it compares, its two outputs, and test clocks
class TripAnomalyDetector(
    private val marks: () -> List<OdometerMarks.Mark>,
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao,
    private val tripMinuteDao: TripMinuteDao,
    private val lastStateDao: LastStateDao,
    private val shadows: () -> List<TripEntity>,
    private val checkpoint: AnomalyCheckpoint,
    private val notes: RecoveredTripNotes,
    private val capacityKwh: suspend () -> Double,
    private val hybrid: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var lastPowerState: Int? = null
    private var lastEdgeMs: Long? = null

    /** The end of a drive: powerState leaves 2 (DRIVE). Debounced to one per [EDGE_DEBOUNCE_MS]. */
    fun isDriveEndEdge(powerState: Int?, nowMs: Long): Boolean {
        val rested = lastEdgeMs?.let { nowMs - it >= EDGE_DEBOUNCE_MS } ?: true
        val edge = lastPowerState == DRIVE && powerState != DRIVE && rested
        lastPowerState = powerState
        if (edge) lastEdgeMs = nowMs
        return edge
    }

    /** One check; returns how many trips it recovered. [reason] goes into the log lines. */
    suspend fun check(reason: String): Int = mutex.withLock {
        val nowMs = now()
        val cp = checkpoint.load()
        val sinceTs = cp?.first ?: (nowMs - HISTORY_LOOKBACK_MS)
        val allMarks = marks()
        val segments = TripAnomalyScan.segments(allMarks, sinceTs, cp?.second)
        if (cp == null) {
            Log.i(TAG, "TRIP anomaly scan ($reason): first check, history since ${stamp(sinceTs)}, " +
                "${allMarks.size} marks, ${segments.size} movements")
        }
        val trips = (tripDao.getStartingBetween(sinceTs - TRIP_LOOKBACK_MS, nowMs + TripAnomalyScan.MINUTE_MS) +
            shadows() + listOfNotNull(openTrip(nowMs))).toMutableList()
        var recovered = 0
        for (seg in segments) {
            val minutes = tripMinuteDao.getInRange(seg.startTs - SOC_MARGIN_MS, seg.aliveTo + SOC_MARGIN_MS)
            val finding = TripAnomalyScan.finding(seg, trips)
            when {
                TripMath.round1(finding.uncoveredKm) > TripAnomalyScan.MIN_UNCOVERED_KM &&
                    recovered < MAX_RECOVERED_PER_CHECK -> {
                    trips += recover(finding, minutes, reason)
                    recovered++
                }
                !seg.gap && seg.km <= TripAnomalyScan.MIN_UNCOVERED_KM -> logStanding(seg, minutes, sinceTs)
            }
        }
        checkpoint.save(nowMs, allMarks.maxByOrNull { it.lastTs }?.lastKm)
        recovered
    }

    private suspend fun recover(finding: TripAnomalyScan.Finding, minutes: List<TripMinuteEntity>, reason: String): TripEntity {
        val trip = TripAnomalyScan.recoveredTrip(finding, minutes, capacityKwh(), hybrid())
        val seg = finding.segment
        val id = tripDao.insert(trip)
        val endTs = trip.endTs ?: trip.startTs
        val points = tripPointDao.attachToTrip(id, trip.startTs, endTs)
        notes.add(RecoveredTripNotes.Note(trip.startTs, endTs, trip.distanceKm ?: 0.0))
        val line = "TRIP anomaly uncovered=${TripMath.round1(finding.uncoveredKm)} " +
            "soc=${trip.socStart ?: "-"}->${trip.socEnd ?: "-"} window=${stamp(trip.startTs)}..${stamp(endTs)}"
        Log.i(TAG, "$line odo=${TripMath.round1(seg.fromKm)}->${TripMath.round1(seg.toKm)} " +
            "covered=${TripMath.round1(finding.coveredKm)} ${if (seg.gap) "app not running" else "in process"} " +
            "($reason) -> recovered trip id=$id kwh=${trip.kwhConsumed} gps=$points")
        com.bydmate.app.diagnostics.TripDebugLog.event("TRIP", "${line.removePrefix("TRIP ")} -> recovered trip id=$id")
        return trip.copy(id = id)
    }

    /** SoC fell without the odometer moving (warm-up, climate while parked): a log line, not a trip. */
    private fun logStanding(seg: TripAnomalyScan.Segment, minutes: List<TripMinuteEntity>, sinceTs: Long) {
        val from = maxOf(seg.startTs, sinceTs)
        val (socFrom, socTo) = TripAnomalyScan.socAround(minutes.filter { it.minuteTs <= seg.aliveTo }, from, seg.aliveTo)
        if (socFrom == null || socTo == null || socFrom - socTo < TripAnomalyScan.MIN_SOC_DROP) return
        Log.i(TAG, "TRIP anomaly uncovered=0.0 soc=$socFrom->$socTo window=${stamp(from)}..${stamp(seg.aliveTo)} " +
            "-> standing consumption (odometer still), no trip")
    }

    /** The trip open right now (last_state), so a drive under way is never "missing". */
    private suspend fun openTrip(nowMs: Long): TripEntity? {
        val s = lastStateDao.getCurrent() ?: return null
        val start = s.tripStartTs ?: return null
        if (s.openTripId == null) return null
        val km0 = s.tripStartMileage?.takeIf { it >= TripMath.MIN_VALID_ODOMETER_KM }
        val km1 = s.mileage?.takeIf { it >= TripMath.MIN_VALID_ODOMETER_KM && km0 != null && it >= km0 }
        return TripEntity(
            startTs = start,
            endTs = nowMs,
            distanceKm = if (km0 != null && km1 != null) km1 - km0 else null,
            odometerStartKm = km1?.let { km0 },
            odometerEndKm = km1,
            source = TripSource.NATIVE_POLLING,
        )
    }

    private fun stamp(ts: Long): String = synchronized(FORMAT) { FORMAT.format(Date(ts)) }

    companion object {
        private const val TAG = "TripRecorder"
        private const val DRIVE = 2

        /** The first check looks back this far: the odometer marks keep 14 days. */
        const val HISTORY_LOOKBACK_MS = 14L * 24 * 60 * 60_000L

        /** A trip that started this long before the window can still cover its start. */
        private const val TRIP_LOOKBACK_MS = 24L * 60 * 60_000L

        /** Minutes this far around a movement are read, for the SoC just before and after a gap. */
        private const val SOC_MARGIN_MS = 30 * 60_000L

        /** A garbage odometer must not flood the trip list. */
        const val MAX_RECOVERED_PER_CHECK = 10

        private const val EDGE_DEBOUNCE_MS = 60_000L

        private val FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
