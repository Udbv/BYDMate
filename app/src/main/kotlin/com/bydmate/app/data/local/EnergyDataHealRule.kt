package com.bydmate.app.data.local

import com.bydmate.app.data.repository.OdometerMarks

/** A stretch of time in which the app itself saw the odometer move by [km]. */
data class DriveWindow(val startMs: Long, val endMs: Long, val km: Double)

/**
 * When a dead-marked energydata file may be trusted again (2026-11-14, missing trip): only when it
 * really gained a drive. A changed mtime or size is no evidence: on a car whose file never records
 * drives, something (an overnight charge) touched it, the old rule healed the verdict on that alone,
 * the app went passive and every drive of the morning was lost. Evidence is a row with a distance
 * that ends inside a drive the app observed itself (its odometer marks), with room for the head
 * unit's clock and for BYD writing the row a little after the car stopped.
 */
object EnergyDataHealRule {

    data class Verdict(val heal: Boolean, val reason: String)

    /** BYD's row may end this long before the first reading of the drive (clock skew). */
    internal const val BEFORE_DRIVE_MS = 2 * 60_000L

    /** ... and this long after the last one: the row is written when the car stops. */
    internal const val AFTER_DRIVE_MS = 10 * 60_000L

    /** A drive needs at least this much odometer movement to count as one. */
    internal const val MIN_DRIVE_KM = 0.5

    /** Rows this short are idle drain or noise, not a drive. */
    internal const val MIN_RECORD_KM = 0.1

    fun decide(records: List<BydTripRecord>, drives: List<DriveWindow>): Verdict {
        if (records.isEmpty()) return Verdict(false, "no rows in the lookback window")
        val driving = records.filter { it.tripKm >= MIN_RECORD_KM }
        if (driving.isEmpty()) return Verdict(false, "${records.size} rows, none with a distance (idle/charging only)")
        val observed = drives.filter { it.km >= MIN_DRIVE_KM }
        if (observed.isEmpty()) return Verdict(false, "${driving.size} drive rows, but no drive observed by the app")
        for (record in driving) {
            val endMs = record.endTimestamp * MS_PER_S
            val drive = observed.firstOrNull { endMs >= it.startMs - BEFORE_DRIVE_MS && endMs <= it.endMs + AFTER_DRIVE_MS }
            if (drive != null) {
                return Verdict(
                    true,
                    "row id=${record.id} km=${record.tripKm} end=$endMs inside observed drive " +
                        "${drive.startMs}..${drive.endMs} (${round1(drive.km)} km)",
                )
            }
        }
        val newest = driving.maxOf { it.endTimestamp } * MS_PER_S
        return Verdict(
            false,
            "${driving.size} drive rows, none ends inside the ${observed.size} observed drives (newest row end=$newest)",
        )
    }

    /**
     * Drives the app saw itself: each process (odometer mark entry) in which the odometer moved,
     * from its first to its last reading. A gap between two processes is not one: the app was not
     * running then.
     */
    fun drivesFromMarks(marks: List<OdometerMarks.Mark>): List<DriveWindow> =
        marks.map { DriveWindow(it.firstTs, it.lastTs, it.lastKm - it.firstKm) }.filter { it.km > 0.0 }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private const val MS_PER_S = 1_000L
}
