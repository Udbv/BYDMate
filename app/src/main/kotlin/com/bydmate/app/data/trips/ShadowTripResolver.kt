package com.bydmate.app.data.trips

import android.util.Log
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripEntity

/**
 * Safety net of a passive device (2026-11-14 missing trips): decides the fate of the shadow trips
 * TripRecorder kept while it trusted the energydata file. Runs after every import attempt
 * (HistoryImporter, under its sync lock, after BYD's new records are in the trips table):
 *
 * - a stored trip covers the shadow ([TripCoverage]: odometer range, else time ± a few minutes) ->
 *   BYD (or another source) has the drive: the shadow is dropped, never a duplicate;
 * - the import succeeded and brought nothing for it (and [GRACE_MS] passed since its end, room for
 *   BYD to write its row) -> the app's own trip is saved as [TripSource.NATIVE_FALLBACK];
 * - the import failed -> it waits, but not past [MAX_WAIT_MS]: a file that cannot be read cannot
 *   bring the trip either. A BYD record that turns up after all is merged into the saved trip by
 *   HistoryImporter (TripDao.getOverlappingOwnTripId), so it is still one trip.
 *
 * Its GPS points are attached by time right away (the same step HistoryImporter runs).
 * Log tag TripRecorder (in the log dump whitelist); times and km only.
 */
class ShadowTripResolver(
    private val store: PendingTripStore,
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao,
) {

    /** TripRecorder's sink in passive mode: a drive worth keeping waits here for the next import. */
    fun add(trip: TripEntity) {
        if ((trip.distanceKm ?: 0.0) < MIN_SHADOW_KM) {
            Log.i(TAG, "shadow trip discarded: km=${trip.distanceKm} (no drive)")
            return
        }
        store.add(trip.copy(source = TripSource.NATIVE_FALLBACK))
        Log.i(TAG, "shadow trip kept: start=${trip.startTs} end=${trip.endTs} km=${trip.distanceKm} " +
            "odo=${trip.odometerStartKm}/${trip.odometerEndKm} -> waits for the next import")
    }

    fun pending(): List<TripEntity> = store.all()

    /** [importOk]: the import ran to the end (an unchanged file counts: BYD wrote nothing new). */
    suspend fun resolve(importOk: Boolean, nowMs: Long): Int {
        val pending = store.all()
        if (pending.isEmpty()) return 0
        var saved = 0
        for (shadow in pending) {
            if (resolveOne(shadow, importOk, nowMs)) saved++
        }
        return saved
    }

    /** True when the shadow became a trip. */
    private suspend fun resolveOne(shadow: TripEntity, importOk: Boolean, nowMs: Long): Boolean {
        val endTs = shadow.endTs ?: shadow.startTs
        val from = shadow.startTs - LOOKUP_MARGIN_MS
        val covering = tripDao.getStartingBetween(from, endTs + LOOKUP_MARGIN_MS)
            .firstOrNull { TripCoverage.covers(it, shadow) }
        val age = nowMs - endTs
        val decision = when {
            covering != null -> "dropped: covered by trip id=${covering.id} src=${covering.source}"
            importOk && age >= GRACE_MS -> "saved: import brought no record for it"
            !importOk && age >= MAX_WAIT_MS -> "saved: imports failing for ${age / MS_PER_MIN} min"
            else -> null
        }
        if (decision == null) {
            Log.i(TAG, "shadow trip start=${shadow.startTs} waits (import ok=$importOk, age ${age / MS_PER_S}s)")
            return false
        }
        val save = covering == null
        if (save) {
            val id = tripDao.insert(shadow.copy(id = 0, source = TripSource.NATIVE_FALLBACK))
            val points = tripPointDao.attachToTrip(id, shadow.startTs, endTs)
            Log.i(TAG, "shadow trip $decision -> trip id=$id gps=$points")
        } else {
            Log.i(TAG, "shadow trip start=${shadow.startTs} km=${shadow.distanceKm} $decision")
        }
        store.remove(shadow.startTs)
        return save
    }

    companion object {
        private const val TAG = "TripRecorder"

        /** Shorter shadows are a car switched on and off, not a drive. */
        const val MIN_SHADOW_KM = 0.1

        /** BYD writes its row around the power-off; an import this soon after may not see it yet. */
        const val GRACE_MS = 2 * 60_000L

        /** Imports failing this long: the shadow is saved anyway. */
        const val MAX_WAIT_MS = 6 * 60 * 60_000L

        /** Trips starting this far around the shadow are looked at (a long BYD record may start earlier). */
        const val LOOKUP_MARGIN_MS = 6 * 60 * 60_000L

        private const val MS_PER_S = 1_000L
        private const val MS_PER_MIN = 60_000L
    }
}
