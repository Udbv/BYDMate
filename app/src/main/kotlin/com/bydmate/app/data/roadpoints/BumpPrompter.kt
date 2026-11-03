package com.bydmate.app.data.roadpoints

import android.location.Location
import android.util.Log
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.helper.push.FidRecBump
import com.bydmate.app.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * A speed bump the car felt, offered to the driver (#22): the daemon's detector reports a body
 * pitch swing, this asks «Лежачий?» and opens the microphone; «да / так» saves the point where the
 * bump was felt (the fix of that moment, not of the answer), «нет / ні» does not. Every offer and
 * its answer goes to the trip log with the swing and the speed: the labels the detector's
 * thresholds are tuned on.
 */
@Singleton
class BumpPrompter @Inject constructor(
    private val voice: VoiceController,
    private val points: RoadPointRecorder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fixes = ArrayDeque<Location>()
    private var lastSeen = 0L
    private var lastAsked: Long? = null

    /** The car's fixes of the last minute: a bump is placed where it was felt. */
    @Synchronized
    fun noteLocation(loc: Location?) {
        if (loc == null || fixes.lastOrNull()?.time == loc.time) return
        fixes.addLast(loc)
        while (fixes.size > MAX_FIXES) fixes.removeFirst()
    }

    /** The detector's candidates as the daemon reports them; only new ones are considered. */
    fun onBumps(bumps: List<FidRecBump>, nowMs: Long = System.currentTimeMillis()) {
        val fresh = bumps.filter { it.atMs > lastSeen }
        if (fresh.isEmpty()) return
        lastSeen = fresh.maxOf { it.atMs }
        val bump = fresh.last()
        val what = "pp=%.1f v=%.0f".format(Locale.ROOT, bump.peakToPeak, bump.speedKph)
        val skip = when {
            nowMs - bump.atMs > MAX_AGE_MS -> "late"
            lastAsked?.let { nowMs - it < MIN_GAP_MS } == true -> "too soon"
            else -> null
        }
        if (skip != null) {
            note("bump? $what not offered: $skip")
            return
        }
        val at = fixAt(bump.atMs)
        lastAsked = nowMs
        voice.askYesNo(QUESTION) { answer ->
            scope.launch {
                val saved = if (answer == true) points.markAt(RoadPointType.SPEED_BUMP, at) else null
                note("bump? $what answer=${answer ?: "none"}${saved?.let { " -> $it" } ?: ""}")
            }
        }
    }

    @Synchronized
    private fun fixAt(ts: Long): Location? =
        fixes.minByOrNull { abs(it.time - ts) }?.takeIf { abs(it.time - ts) <= MAX_FIX_GAP_MS }

    private fun note(line: String) {
        Log.i(TAG, line)
        TripDebugLog.event("BUMP", line)
    }

    companion object {
        private const val TAG = "BumpPrompter"
        const val QUESTION = "Лежачий?"
        /** A bump reported later than this is too far behind to ask about. */
        const val MAX_AGE_MS = 20_000L
        /** Not one question after another: a run of humps is asked about once. */
        const val MIN_GAP_MS = 30_000L
        private const val MAX_FIX_GAP_MS = 3_000L
        private const val MAX_FIXES = 120
    }
}
