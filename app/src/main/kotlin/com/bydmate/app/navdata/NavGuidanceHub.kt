package com.bydmate.app.navdata

import android.util.Log

/** Unified guidance snapshot: ONE source of truth for both the HUD push loop and the
 *  voice agent's get_route_info. Written by NavA11yFeed (a11y thread) and the
 *  notification listener (binder thread), read from coroutines; hence @Synchronized
 *  writers and a @Volatile snapshot.
 *
 *  Semantics (donor-derived):
 *  - field-wise merge: a partial update never wipes known values;
 *  - active expires 90 s after the last update of any source;
 *  - speed limit has its own 30 s freshness (a limit sign must not outlive its road);
 *  - the maneuver has its own 30 s freshness (a passed turn must not outlive its balloon);
 *  - a successful Navigator-window read WITHOUT guidance widgets is an explicit
 *    "route ended" signal: 10 s of that deactivates the snapshot (markNoGuidance). */
object NavGuidanceHub {
    private const val TAG = "NavGuidanceHub"
    /**
     * How long a route survives with no source refreshing it.
     *
     * 40 s, down from 90: openbyd drops its navigation state after 40 s of silence, and the keep-
     * alive re-read in [NavA11yFeed] fires every 20 s while a route is live, so nothing legitimate
     * gets close to this. The old value kept a finished route on the glass for a minute and a half.
     */
    const val ACTIVE_TIMEOUT_MS = 40_000L
    const val SPEED_LIMIT_TIMEOUT_MS = 30_000L
    /** Maneuver freshness. The donor holds an a11y maneuver 10 s (20 s while a distance
     *  is known) and then falls back to its SEPARATE notification-enum maneuver; here both
     *  lanes write one merged field, and the notification lane refreshes maneuverGaodeMs
     *  only when it parsed a maneuver AND a11y is not fresher - so the donor's window
     *  would drop the arrow during an a11y blind spell. 30 s, same as the speed limit. */
    const val MANEUVER_TIMEOUT_MS = 30_000L
    const val NO_GUIDANCE_DEACTIVATE_MS = 10_000L
    const val A11Y_PRIORITY_MS = 10_000L

    enum class Source { A11Y, NOTIFICATION }

    data class Snapshot(
        val active: Boolean = false,
        val maneuverGaode: Int = 0,
        val maneuverGaodeMs: Long = 0L,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val totalDistMeters: Int = 0,
        val speedLimit: Int = 0,
        val speedLimitMs: Long = 0L,
        val lastUpdateMs: Long = 0L,
        // Camera / PNG fields — appended with defaults so existing positional
        // constructors remain valid (fleet-safety rule).
        val maneuverPng: ByteArray? = null,
        val cameraAlert: String = "",
        val cameraDistanceMeters: Int = 0,
        val cameraIconPng: ByteArray? = null,
        // openbyd port (3.16.0-dev.7): the panel glyph named by the Waze arrow classifier, its
        // read time (expires with MANEUVER_TIMEOUT_MS like maneuverGaode), and the roundabout
        // exit number from navBarDirectionText. Appended for the same positional-constructor reason.
        val panelIcon: Int = 0,
        val panelIconMs: Long = 0L,
        val exitNumber: Int? = null,
    )

    /** Rich notification payload (donor listener merge). applyCamera=false is the
     *  extras fallback - extras carry no camera info, previous camera state persists. */
    data class RichUpdate(
        val maneuverGaode: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val etaSeconds: Int = 0,
        val totalDistMeters: Int = 0,
        val maneuverPng: ByteArray? = null,
        val cameraAlert: String = "",
        val cameraDistanceMeters: Int = 0,
        val cameraIconPng: ByteArray? = null,
        val applyCamera: Boolean = true,
    )

    @Volatile private var current = Snapshot()
    @Volatile private var noGuidanceSinceMs = 0L
    @Volatile private var lastA11yMs = 0L

    /** Returns a deactivated copy with PNG/camera fields cleared. Used in all places
     *  where active is set to false so HUD/agent never see a stale camera overlay. */
    private fun deactivated(s: Snapshot): Snapshot = s.copy(
        active = false,
        maneuverPng = null,
        cameraAlert = "",
        cameraDistanceMeters = 0,
        cameraIconPng = null,
        // openbyd clears lastExitNumber with the route; a stale exit would otherwise renumber the
        // first roundabout of the NEXT route before its own number is read.
        panelIcon = 0,
        exitNumber = null,
    )

    // @Synchronized because expiry writes back: an unsynchronized write here could
    // clobber a concurrent update() with a stale copy.
    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot {
        var s = current
        if (s.active && nowMs - s.lastUpdateMs > ACTIVE_TIMEOUT_MS) {
            s = deactivated(s)
            current = s
            Log.i(TAG, "guidance inactive: no source updated for ${ACTIVE_TIMEOUT_MS / 1000}s")
        }
        // A started no-guidance streak expires by TIME, not by a second event: after a
        // route ends the Navigator may go silent (window closed, no more a11y events),
        // so the deadline must fire from the reader side (Codex audit fix 2).
        if (s.active && noGuidanceSinceMs != 0L && nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            s = deactivated(s)
            current = s
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: route ended (no-guidance deadline)")
        }
        if (s.speedLimit > 0 && nowMs - s.speedLimitMs > SPEED_LIMIT_TIMEOUT_MS) {
            s = s.copy(speedLimit = 0)
            current = s
        }
        // A maneuver is never overwritten by a "no maneuver" read (the field-wise merge
        // keeps prev), so without an age limit a passed turn stays on the glass for the
        // rest of the route. Icon goes with it: donor sends no f8 without a maneuver.
        // Distance is NOT reset - the donor keeps counting it down in that state too.
        if (s.maneuverGaode > 0 && nowMs - s.maneuverGaodeMs > MANEUVER_TIMEOUT_MS) {
            s = s.copy(maneuverGaode = 0, maneuverPng = null)
            current = s
            Log.i(TAG, "maneuver expired: no maneuver read for ${MANEUVER_TIMEOUT_MS / 1000}s")
        }
        // The panel glyph has its own clock: it is written by the arrow classifier, which can stop
        // matching (arrow off screen, Waze minimized) while the text path keeps a maneuver alive.
        if (s.panelIcon > 0 && nowMs - s.panelIconMs > MANEUVER_TIMEOUT_MS) {
            s = s.copy(panelIcon = 0)
            current = s
            Log.i(TAG, "panel icon expired: no arrow matched for ${MANEUVER_TIMEOUT_MS / 1000}s")
        }
        return s
    }

    /**
     * One classified Waze arrow: the panel glyph openbyd would write, plus the roundabout exit
     * number that was in force when it was classified.
     *
     * Only a matched arrow gets here with a glyph; an unmatched one passes 0 and leaves the text
     * path's maneuver alone. [maneuverGaode] is derived from the glyph so every consumer that
     * still speaks the AutoNavi numbering (the windshield card, the voice agent) keeps working -
     * the panel writer prefers [Snapshot.panelIcon] itself.
     *
     * Never activates a route: a bare arrow with no distance or street is not guidance.
     */
    @Synchronized
    fun updateWazeArrow(
        panelIcon: Int,
        exitNumber: Int?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val snapshot = snapshot(nowMs)
        if (!snapshot.active) return false
        val changed = snapshot.panelIcon != panelIcon && panelIcon > 0
        current = current.copy(
            panelIcon = if (panelIcon > 0) panelIcon else current.panelIcon,
            panelIconMs = if (panelIcon > 0) nowMs else current.panelIconMs,
            maneuverGaode = if (panelIcon > 0) gaodeOf(panelIcon) else current.maneuverGaode,
            maneuverGaodeMs = if (panelIcon > 0) nowMs else current.maneuverGaodeMs,
            exitNumber = exitNumber ?: current.exitNumber,
        )
        return changed
    }

    /**
     * Roundabout exit number as printed inside the Waze arrow, stored verbatim (null included) so a
     * roundabout that no longer prints one stops renumbering the glyph. The donor keeps the same
     * single mutable field beside its route state.
     */
    @Synchronized
    fun setExitNumber(exitNumber: Int?) {
        current = current.copy(exitNumber = exitNumber)
    }

    /**
     * Instrument-panel glyph -> AutoNavi maneuver code. The two numbering spaces only partly
     * overlap, so this is a deliberate table rather than an identity:
     *  - 5 (slight right on the panel) is 4 in AutoNavi;
     *  - 15..24, the roundabout direction variants, have no AutoNavi equivalent at all and collapse
     *    to the plain roundabout 13;
     *  - 25..44, the numbered roundabout exits, pass through (35..44 are the left-hand-traffic
     *    block, which AutoNavi has no name for but which no consumer re-derives);
     *  - anything else answers 11, the neutral "straight", which is what openbyd writes when it
     *    cannot name the arrow.
     */
    fun gaodeOf(panelIcon: Int): Int = when (panelIcon) {
        1, 2, 3 -> panelIcon
        5 -> 4
        9, 10, 11 -> panelIcon
        in 15..24 -> 13
        in 25..44 -> panelIcon
        45 -> 45
        48 -> 48
        else -> 11
    }

    /**
     * Maneuver-only hint for an ACTIVE route (Waze arrow classified from an a11y screenshot or
     * carried by a single a11y event). Never activates a route by itself: a hint without a live
     * distance/street would put a bare arrow on the glass. Returns true when the rendered
     * maneuver actually changed, so callers log edges rather than every 1 Hz re-confirmation.
     */
    @Synchronized
    fun updateManeuverHint(
        maneuverGaode: Int,
        source: Source,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (maneuverGaode <= 0) return false
        val current = snapshot(nowMs)
        if (!current.active) return false
        // Field-wise merge: zeros keep every other field, only the maneuver (and its freshness) moves.
        update(NavGuidance(maneuverGaode = maneuverGaode), source, nowMs)
        return current.maneuverGaode != maneuverGaode
    }

    @Synchronized
    fun update(data: NavGuidance, source: Source, nowMs: Long = System.currentTimeMillis()) {
        noGuidanceSinceMs = 0L
        val prev = current
        if (!prev.active) Log.i(TAG, "guidance active (source=$source)")
        current = prev.copy(
            active = true,
            maneuverGaode = if (data.maneuverGaode > 0) data.maneuverGaode else prev.maneuverGaode,
            maneuverGaodeMs = if (data.maneuverGaode > 0) nowMs else prev.maneuverGaodeMs,
            distanceMeters = if (data.distanceMeters > 0) data.distanceMeters else prev.distanceMeters,
            road = data.road.ifEmpty { prev.road },
            etaSeconds = if (data.etaSeconds > 0) data.etaSeconds else prev.etaSeconds,
            totalDistMeters = if (data.totalDistMeters > 0) data.totalDistMeters else prev.totalDistMeters,
            speedLimit = if (data.speedLimit > 0) data.speedLimit else prev.speedLimit,
            speedLimitMs = if (data.speedLimit > 0) nowMs else prev.speedLimitMs,
            lastUpdateMs = nowMs,
            panelIcon = if (data.panelIcon > 0) data.panelIcon else prev.panelIcon,
            panelIconMs = if (data.panelIcon > 0) nowMs else prev.panelIconMs,
            exitNumber = data.exitNumber ?: prev.exitNumber,
        )
        if (source == Source.A11Y) lastA11yMs = nowMs
    }

    /**
     * Rich notification entry (spec §6). While an a11y update is fresher than
     * A11Y_PRIORITY_MS the guidance fields are ignored (a11y wins the field race),
     * but side effects ALWAYS apply: active=true, lastUpdateMs, no-guidance streak
     * reset, camera merge. Camera two-stage rule mirrors the donor listener:
     * alert replaces; empty alert clears distance/icon; non-empty keeps prev gaps.
     */
    @Synchronized
    fun updateFromNotification(rich: RichUpdate, nowMs: Long = System.currentTimeMillis()) {
        // #170: the first player notification after Alice interrupts guidance often has
        // no navigation payload at all (extras fallback returns an all-empty RichUpdate).
        // Activating the hub on that carries no maneuver/distance, so displayDistance()
        // clamps 0 up to its 11 m floor and the HUD flashes a bogus "11m". A truly empty
        // update must be a no-op: leave active/lastUpdateMs/noGuidanceSinceMs untouched.
        if (rich.maneuverGaode == 0 && rich.distanceMeters == 0 && rich.road.isEmpty() &&
            rich.etaSeconds == 0 && rich.totalDistMeters == 0 && rich.cameraAlert.isEmpty()
        ) {
            return
        }
        noGuidanceSinceMs = 0L
        val prev = current
        if (!prev.active) Log.i(TAG, "guidance active (source=NOTIFICATION)")
        val a11yFresh = lastA11yMs != 0L && nowMs - lastA11yMs <= A11Y_PRIORITY_MS
        val base = if (a11yFresh) prev else prev.copy(
            maneuverGaode = if (rich.maneuverGaode > 0) rich.maneuverGaode else prev.maneuverGaode,
            maneuverGaodeMs = if (rich.maneuverGaode > 0) nowMs else prev.maneuverGaodeMs,
            distanceMeters = if (rich.distanceMeters > 0) rich.distanceMeters else prev.distanceMeters,
            road = rich.road.ifEmpty { prev.road },
            etaSeconds = if (rich.etaSeconds > 0) rich.etaSeconds else prev.etaSeconds,
            totalDistMeters = if (rich.totalDistMeters > 0) rich.totalDistMeters else prev.totalDistMeters,
            maneuverPng = rich.maneuverPng ?: prev.maneuverPng,
        )
        current = base.copy(
            active = true,
            lastUpdateMs = nowMs,
            cameraAlert = if (rich.applyCamera) rich.cameraAlert else prev.cameraAlert,
            cameraDistanceMeters = when {
                !rich.applyCamera -> prev.cameraDistanceMeters
                rich.cameraAlert.isEmpty() -> 0
                else -> rich.cameraDistanceMeters.takeIf { it > 0 } ?: prev.cameraDistanceMeters
            },
            cameraIconPng = when {
                !rich.applyCamera -> prev.cameraIconPng
                rich.cameraAlert.isEmpty() -> null
                else -> rich.cameraIconPng ?: prev.cameraIconPng
            },
        )
    }

    /** Donor removal grace: called by the notification lane's deactivate check when
     *  the guidance notification is gone and nothing refreshed the hub for
     *  ACTIVE_TIMEOUT_MS. Fresh state = no-op. */
    @Synchronized
    fun deactivateFromNotificationGrace(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active) return
        if (nowMs - s.lastUpdateMs < ACTIVE_TIMEOUT_MS) return
        current = deactivated(s)
        noGuidanceSinceMs = 0L
        Log.i(TAG, "guidance inactive: notification removed, grace expired")
    }

    @Synchronized
    fun markNoGuidance(nowMs: Long = System.currentTimeMillis()) {
        val s = current
        if (!s.active) { noGuidanceSinceMs = 0L; return }
        if (noGuidanceSinceMs == 0L) { noGuidanceSinceMs = nowMs; return }
        if (nowMs - noGuidanceSinceMs >= NO_GUIDANCE_DEACTIVATE_MS) {
            current = deactivated(s)
            noGuidanceSinceMs = 0L
            Log.i(TAG, "guidance inactive: route ended (no-guidance streak)")
        }
    }

    @Synchronized
    fun reset() {
        current = Snapshot()
        noGuidanceSinceMs = 0L
        lastA11yMs = 0L
    }
}
