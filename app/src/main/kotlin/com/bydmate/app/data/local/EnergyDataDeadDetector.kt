package com.bydmate.app.data.local

import android.util.Log

/**
 * Detects a dead leftover energydata DB (issue #63): the car keeps driving (mileage grows
 * across ignition sessions) while the DB file never changes.
 *
 * A live energydata (Leopard 3 / DiLink 4/5) gains a record around every drive cycle, so
 * its fingerprint changes between sessions and the streak below keeps resetting — by
 * construction the detector can never fire there. On a dead leftover the fingerprint is
 * frozen forever, the streak reaches [DEAD_STREAK_THRESHOLD] and the device is switched
 * to native trip recording until reset (Settings button) or auto-heal: the file gains a drive row
 * that ends inside a drive the app saw itself. A bare change of mtime/size is not enough
 * ([checkHealIfDue], 2026-11-14).
 *
 * A file frozen for months (#148: energydata stuck since Nov 2025 on a driving car) does not need
 * the full [DEAD_STREAK_THRESHOLD] to be judged: a driving session over a DB older than
 * [STALE_FILE_AGE_MS] shortens the verdict to two sessions instead of days of an empty trip list.
 * It shortens it, never skips it — the age branch also requires an already-standing streak, so the
 * verdict still rests on two independent anomalies. The head unit's RTC is not trustworthy on its
 * own: it can boot with a forward-jumped clock and evaluate() runs at ignition-on, before time
 * sync, so a single age reading on a live car whose latest record had not landed yet could mark it
 * dead. That verdict is effectively irreversible in its consequences — auto-heal flips the mode
 * back, but HistoryImporter's dedup cleanup is one-shot, so the trips recorded natively in the
 * meantime stay duplicated.
 *
 * The age is checked ONLY in the pendingDriving branch: a car parked for a month has an equally
 * old file and would false-positive on the first-fingerprint branch, while on a live car the
 * fingerprint-change branch is evaluated earlier and heals/resets anyway.
 *
 * All calls come from TripRecorder on the polling collector — no internal locking needed.
 * The "drove this session" marker is persisted mid-session, the moment the distance
 * threshold is crossed, rather than on the ignition-off edge: in the field the head unit
 * powers down with the car, killing the process before an off tick is ever delivered.
 */
class EnergyDataDeadDetector(
    private val reader: EnergyDataReader,
    private val store: EnergyDataLivenessStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Drives the app saw itself (odometer marks): the only evidence a dead verdict is healed on. */
    private val observedDrives: () -> List<DriveWindow> = { emptyList() },
) {

    /**
     * Fingerprint of a dead-marked file seen changing at the last ignition-on: the heal check reads
     * its rows ([checkHealIfDue]) before anything changes. Null when no check is due.
     */
    @Volatile
    private var healCandidate: Pair<Long, Long>? = null

    private var sessionRunning = false
    private var sessionStartMileage: Double? = null
    private var sessionPendingMarked = false

    /** Cached verdict so the polling hot path never hits SharedPreferences per tick. */
    @Volatile
    private var cachedDead: Boolean? = null

    fun isDead(): Boolean {
        cachedDead?.let { return it }
        return store.isDead().also { cachedDead = it }
    }

    /** Feed every polling tick; edges are derived internally, repeats are no-ops. */
    fun onTick(ignition: Boolean, mileage: Double?) {
        if (ignition && !sessionRunning) {
            evaluate()
            sessionRunning = true
            // SentinelDecoder can pass a garbage raw 0.0 as a valid odometer read; a real
            // Leopard 3 fleet never has a genuinely 0.0 km odometer, so treat it the same
            // as "not known yet" and let the mid-session backfill below try again.
            sessionStartMileage = mileage?.takeIf { it > 0.0 }
            sessionPendingMarked = false
        } else if (ignition && sessionRunning) {
            // #63 regression: in the field the process dies WITH the car, so the
            // ignition-off edge below is almost never observed and pendingDriving was
            // never persisted (streak stayed 0 forever). Persist the "drove this
            // session" marker the moment the distance threshold is crossed instead —
            // one prefs write per session, guarded by sessionPendingMarked. The off
            // edge below still marks too (kept as a fallback for when it IS observed),
            // guarded by the same flag so it never double-writes.
            if (sessionStartMileage == null) sessionStartMileage = mileage?.takeIf { it > 0.0 }
            val start = sessionStartMileage
            if (!sessionPendingMarked && start != null && mileage != null &&
                mileage - start >= MIN_SESSION_KM
            ) {
                sessionPendingMarked = true
                store.setPendingDriving(true)
            }
        } else if (!ignition && sessionRunning) {
            sessionRunning = false
            val start = sessionStartMileage
            sessionStartMileage = null
            if (!sessionPendingMarked && start != null && mileage != null &&
                mileage - start >= MIN_SESSION_KM
            ) {
                store.setPendingDriving(true)
            }
            sessionPendingMarked = false
        }
    }

    /**
     * Heals a dead verdict only on evidence (2026-11-14): after the file of a dead-marked device
     * changed, its rows are read and the verdict goes only when one with a distance ends inside a
     * drive the app observed itself ([EnergyDataHealRule]). Otherwise it stays and the new
     * fingerprint is taken, so the next change is checked again. Every decision is one log line
     * with its reason. A read failure leaves the change unchecked: the next ignition-on retries it.
     */
    @Suppress("TooGenericExceptionCaught") // a failed read must never stop the poll loop
    suspend fun checkHealIfDue() {
        val fp = healCandidate ?: return
        val sinceSec = (now() - HEAL_LOOKBACK_MS) / 1000L
        healCandidate = null
        val records = try {
            reader.readTripsForProbe(sinceSec)
        } catch (e: Exception) {
            // The fingerprint stays untaken: the next ignition-on sees the change again and retries.
            Log.w(TAG, "dead verdict heal check: read failed ${e.javaClass.simpleName}, retried next ignition")
            return
        }
        val verdict = EnergyDataHealRule.decide(records.orEmpty(), observedDrives())
        if (verdict.heal) {
            Log.i(TAG, "dead-marked energydata gained a drive row — healing back to passive: ${verdict.reason}")
            store.reset()
            cachedDead = false
        } else {
            Log.i(TAG, "dead-marked energydata changed but gained no drive — verdict kept: ${verdict.reason}")
        }
        store.setFingerprint(fp.first, fp.second)
    }

    /** Settings button: forget the verdict, re-detect from scratch. */
    fun reset() {
        store.reset()
        cachedDead = null
        healCandidate = null
        sessionRunning = false
        sessionStartMileage = null
        sessionPendingMarked = false
    }

    /** One line for the diagnostic dump. */
    fun debugState(): String =
        "dead=${store.isDead()} streak=${store.streak()} pending=${store.pendingDriving()} " +
            "stored_fp=${store.fingerprint()} current_fp=${reader.sourceFingerprint()}"

    private fun evaluate() {
        val fpNow = reader.sourceFingerprint() ?: return
        val stored = store.fingerprint()
        when {
            stored == null -> {
                store.setFingerprint(fpNow.first, fpNow.second)
                store.setPendingDriving(false)
            }
            fpNow != stored && store.isDead() -> {
                // 2026-11-14: a bare mtime/size change is no proof the file records drives (an
                // overnight charge touched it, the app went passive, the morning's drives were
                // lost). The verdict stays until checkHealIfDue() finds a real drive row.
                Log.i(TAG, "dead-marked energydata changed on disk (fp $stored -> $fpNow) — " +
                    "checking its rows before healing")
                healCandidate = fpNow
            }
            fpNow != stored -> {
                // The car is appending to the file — energydata is alive: the streak restarts.
                store.reset()
                store.setFingerprint(fpNow.first, fpNow.second)
                cachedDead = false
            }
            store.pendingDriving() -> {
                store.setPendingDriving(false)
                val ageMs = reader.sourceLastModifiedMs()?.let { now() - it }
                if (ageMs != null && ageMs >= STALE_FILE_AGE_MS && store.streak() >= 1) {
                    Log.i(
                        TAG,
                        "driving session over energydata frozen for ${ageMs / 86_400_000L}d " +
                            "— marking DEAD immediately"
                    )
                    store.setDead()
                    cachedDead = true
                    return
                }
                if (now() - store.lastIncrementTs() < MIN_INCREMENT_SPACING_MS) return
                val streak = store.streak() + 1
                store.setStreak(streak)
                store.setLastIncrementTs(now())
                Log.i(TAG, "driving session over a frozen energydata file ($streak/$DEAD_STREAK_THRESHOLD)")
                if (streak >= DEAD_STREAK_THRESHOLD) {
                    Log.i(TAG, "energydata marked DEAD for this device — native trip recording ON")
                    store.setDead()
                    cachedDead = true
                }
            }
        }
    }

    companion object {
        // Reuse the tag already whitelisted in the in-app log dump.
        private const val TAG = "EnergyDataReader"
        internal const val DEAD_STREAK_THRESHOLD = 3
        internal const val MIN_SESSION_KM = 1.0
        internal const val MIN_INCREMENT_SPACING_MS = 30 * 60_000L
        internal const val STALE_FILE_AGE_MS = 30L * 24 * 60 * 60_000L
        /** Rows read by the heal check; the odometer marks it is compared with live 14 days. */
        internal const val HEAL_LOOKBACK_MS = 14L * 24 * 60 * 60_000L
    }
}
