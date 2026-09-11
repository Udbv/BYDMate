package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavLanes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The instrument-panel test bench, ported from openbyd's "HUD Capability Showcase"
 * (`da0.l` / `m70` / `yt` / `l70`, openbyd 2.4.3).
 *
 * It exists because the panel cannot be asked what it drew: every write is accepted, so the only
 * way to learn which glyph, which charset and which lane encoding this car actually understands
 * is to put a chosen frame on the glass while parked and look. The screen sends one hand-built
 * frame, or cycles the donor's forty-nine demo maneuvers at the donor's three-second cadence.
 *
 * Two rules keep it out of the way of real guidance:
 *  - it refuses to send anything while [NavGuidanceHub] says a route is active, so a test frame
 *    can never overwrite a maneuver the driver is following;
 *  - it owns *its own* [HudInstrumentFids] and [HudLaneWriter], so its change detection, its
 *    "panel is armed" state and its stop sequence are separate from the push loop's.
 *
 * Unlike the donor it keeps the SDK status of every verb it sent ([State.lastStatus]): openbyd
 * throws those away, but a "status 0" line is what makes a blank glass diagnosable.
 */
@Singleton
class HudPanelTester internal constructor(
    private val helperClient: HelperClient,
    /** Latinise the street name on the automatic run, exactly as the live push loop does. */
    private val transliteratePref: () -> Boolean,
    /** True while the navigator is guiding a real route; the tester then refuses to write. */
    private val routeActive: () -> Boolean = { NavGuidanceHub.snapshot().active },
) {
    @Inject constructor(helperClient: HelperClient, hudController: HudController) :
        this(helperClient, { hudController.streetTransliterate() })

    companion object {
        private const val TAG = "HudPanelTester"

        /** The donor's default lane strip: left, two straights, straight+right and right, both
         *  taken right (`m70.m`). */
        const val DEFAULT_LANE_TEXT = "1,255|0,255|0,255|4,3|3,3"
        const val DEFAULT_LANE_DISTANCE = "100"
        const val DEFAULT_SPEED_LIMIT = "50"

        /** No lane / not on the route, as the panel spells it. */
        const val LANE_EMPTY = 255

        /** Fixed remaining route of every test frame (`c70(…, 15000, 900, …)`) = 0 h 15 min. */
        const val REST_HOUR = 0
        const val REST_MINUTE = 15
        const val REST_MILEAGE_METERS = 15_000L

        /** Donor cadence of the automatic run (`l70`, `delay(3000)`). */
        const val STEP_INTERVAL_MS = 3_000L

        /** How often the SDK-bound badge is refreshed while the screen is open (`m70` ctor). */
        const val POLL_INTERVAL_MS = 1_500L

        /** Speed-limit statistics the donor writes before the camera call (`CCI:1628-1655`). */
        const val STAT_SEGMENT_SPEED_LIMIT = 1_083_203_616   // 0x40906020
        const val STAT_SEGMENT_SPEED_2 = 754_057_272         // 0x2CF20038

        /**
         * Which directions a lane code may be "taken" in, as the donor's tester validates them
         * (`yt.java:88-170`). A front the table does not allow becomes 255, i.e. "this lane is
         * not on the route". Note that this is the *tester's* table, and it is wider than the
         * glyph table in [HudLaneWriter]: `17,0` and `19,1` pass here and fall back to the plain
         * glyph downstream — the donor accepts that, and so does this port.
         */
        private val ALLOWED_FRONTS: Map<Int, Set<Int>> = mapOf(
            2 to setOf(0, 1),
            4 to setOf(0, 3),
            6 to setOf(1, 3),
            7 to setOf(0, 1, 3),
            9 to setOf(0, 5),
            10 to setOf(0, 8),
            11 to setOf(1, 5),
            12 to setOf(3, 8),
            16 to setOf(0, 1, 5),
            17 to setOf(0, 3, 5),
            18 to setOf(1, 3, 5),
            19 to setOf(0, 1, 3, 5),
            20 to setOf(1, 8),
        )

        /**
         * `"code,front|…"` → the two arrays the panel takes, or null when the text cannot be
         * read at all (the donor logs and sends no lanes then).
         *
         * Blank segments are dropped; a segment that is not exactly `code,front` is skipped; a
         * front of -1 or 255 means "not on the route"; a front equal to the code is kept as is;
         * anything else is checked against [ALLOWED_FRONTS] and becomes 255 when it is not there.
         */
        fun parseLanes(text: String, distanceMeters: Int): NavLanes? {
            if (text.isBlank()) return null
            return try {
                val codes = ArrayList<Int>()
                val fronts = ArrayList<Int>()
                for (segment in text.split("|")) {
                    if (segment.isBlank()) continue
                    val parts = segment.split(",")
                    if (parts.size != 2) continue
                    val code = parts[0].trim().toInt()
                    var front = parts[1].trim().toInt()
                    if (front == -1 || front == LANE_EMPTY) {
                        front = LANE_EMPTY
                    } else if (code != front) {
                        if (front !in (ALLOWED_FRONTS[code] ?: emptySet())) front = LANE_EMPTY
                    }
                    codes += code
                    fronts += front
                }
                NavLanes.ofCodes(codes.toIntArray(), fronts.toIntArray(), distanceMeters)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse lanes input: $text", e)
                null
            }
        }

        /**
         * What the card shows under "onto" (`yt.java:59-67`): the raw name when sanitising is
         * off, the name itself when sanitising changes nothing, and both otherwise — which is
         * the whole point, since it says what the panel was actually handed.
         */
        fun displayStreet(road: String, sanitize: Boolean, sanitizer: HudTextSanitizer): String {
            if (!sanitize) return "[RAW] $road"
            val s = sanitizer.sanitize(road)
            return if (s == road) road else "[SANITIZED] $s ($road)"
        }
    }

    /** Everything the test screen draws. One flow, so a recomposition cannot see half a frame. */
    data class State(
        val running: Boolean = false,
        /** 0-based index into [HudPanelTestSteps.STEPS]; the card shows it 1-based. */
        val stepIndex: Int = 0,
        val iconId: Int = 0,
        val streetDisplay: String = "",
        val distanceMeters: Int = 0,
        val elapsedSeconds: Int = 0,
        val manualStep: String = "1",
        val customStreet: String = HudPanelTestSteps.STEPS[0].road,
        val speedLimit: String = DEFAULT_SPEED_LIMIT,
        val laneText: String = DEFAULT_LANE_TEXT,
        val laneDistance: String = DEFAULT_LANE_DISTANCE,
        val sanitize: Boolean = true,
        /** The helper daemon answered a ping: without it nothing below reaches the panel. */
        val sdkBound: Boolean = false,
        /** "guidance=0 street=0/0 …" for the last frame sent; empty before the first one. */
        val lastStatus: String = "",
        /** Set when the last attempt threw; the screen shows it as "Failed: …". */
        val lastError: String? = null,
        /** The navigator is guiding a real route, so the tester declined to write. */
        val routeActiveBlocked: Boolean = false,
    ) {
        /** The demo step the card is showing (the automatic run's), 1-based for display. */
        val stepNumber: Int get() = stepIndex + 1
    }

    /** Own scope: the sim loop outlives any composition, and a stop must run to completion even
     *  when the screen is already gone. Replaced by tests with a TestScope. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal var sanitizer: HudTextSanitizer = HudTextSanitizer.DEFAULT

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var simJob: Job? = null
    private var pollJob: Job? = null

    /** Its own writers: the tester must never disturb the live push loop's change detection. */
    private var fidsImpl: HudInstrumentFids? = null
    private var lanesImpl: HudLaneWriter? = null
    private var lastSpeedLimit: Int? = null
    private var lanesSent = false

    private fun fids(): HudInstrumentFids = fidsImpl
        // Sanitising is decided per call here, so the writer's own preference stays out of it.
        ?: HudInstrumentFids(helperClient, scope, sanitizePref = { false }, sanitizer = sanitizer)
            .also { fidsImpl = it }

    private fun lanes(): HudLaneWriter = lanesImpl
        ?: HudLaneWriter(helperClient, scope).also { lanesImpl = it }

    // ---------------------------------------------------------------- field edits

    /** Digits only, like the donor; a number in range also loads that step's street name. */
    fun setManualStep(text: String) {
        if (text.any { !it.isDigit() }) return
        val n = text.toIntOrNull()
        _state.value = _state.value.copy(
            manualStep = text,
            customStreet = if (n != null && n in 1..HudPanelTestSteps.SIZE)
                HudPanelTestSteps.STEPS[n - 1].road else _state.value.customStreet,
        )
    }

    fun setSpeedLimit(text: String) {
        if (text.any { !it.isDigit() }) return
        _state.value = _state.value.copy(speedLimit = text)
    }

    fun setCustomStreet(text: String) { _state.value = _state.value.copy(customStreet = text) }

    fun setLaneDistance(text: String) {
        if (text.any { !it.isDigit() }) return
        _state.value = _state.value.copy(laneDistance = text)
    }

    fun setLaneText(text: String) { _state.value = _state.value.copy(laneText = text) }

    fun setSanitize(on: Boolean) { _state.value = _state.value.copy(sanitize = on) }

    // ---------------------------------------------------------------- screen lifecycle

    /** Screen opened: start the 1500 ms SDK-bound poll (`m70` ctor / `l70simple:137-157`). */
    fun onScreenOpened() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val alive = runCatching { helperClient.isAlive() }.getOrDefault(false)
                _state.value = _state.value.copy(sdkBound = alive)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Screen closed: the donor's `DisposableEffect` — stop the run and clear the panel. */
    fun onScreenClosed() {
        pollJob?.cancel()
        pollJob = null
        stop()
    }

    // ---------------------------------------------------------------- manual frame

    /** "Send Manual Test Frame" (`yt` case 1), off the caller's thread. */
    fun sendManualFrame() {
        scope.launch { sendManualFrameNow() }
    }

    /** The manual frame, awaited. Returns false when a live route made it refuse. */
    suspend fun sendManualFrameNow(): Boolean {
        stopNow()
        if (routeActive()) {
            _state.value = _state.value.copy(routeActiveBlocked = true)
            Log.i(TAG, "manual frame refused: a route is active")
            return false
        }
        val s = _state.value
        val step = HudPanelTestSteps.stepFor(s.manualStep.toIntOrNull() ?: 1)
        val street = s.customStreet
        _state.value = s.copy(
            routeActiveBlocked = false,
            stepIndex = HudPanelTestSteps.STEPS.indexOf(step),
            iconId = step.iconId,
            distanceMeters = step.distanceMeters,
            streetDisplay = displayStreet(street, s.sanitize, sanitizer),
            elapsedSeconds = 0,
        )
        val parsed = parseLanes(s.laneText, s.laneDistance.toIntOrNull() ?: 100)
        return try {
            val frame = fids().sendFrameNow(
                icon = step.iconId,
                distanceMeters = step.distanceMeters,
                road = street,
                sanitize = s.sanitize,
                hour = REST_HOUR, minute = REST_MINUTE, mileageMeters = REST_MILEAGE_METERS,
            )
            val laneStatus = sendLanes(parsed)
            val speedStatus = sendSpeedLimit(s.speedLimit.toIntOrNull())
            _state.value = _state.value.copy(
                lastStatus = statusLine(frame, laneStatus, speedStatus),
                lastError = null,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send manual HUD step", e)
            _state.value = _state.value.copy(lastError = e.message ?: "Unknown error")
            false
        }
    }

    private suspend fun sendLanes(parsed: NavLanes?): String {
        if (parsed == null || parsed.isEmpty) {
            if (!lanesSent) return "-"
            lanes().clearNow()
            lanesSent = false
            return "cleared"
        }
        lanes().updateNow(parsed)
        lanesSent = true
        return "${parsed.size} lanes"
    }

    /**
     * `sendSpeedLimitInfo` (`CCI:1627-1655`): two statistics and the camera call, and only when
     * the value changed — the donor's own change detection, which is why a restart does not
     * re-send an unchanged limit.
     */
    private suspend fun sendSpeedLimit(value: Int?): String {
        val v = value ?: -1
        if (v == lastSpeedLimit) return "-"
        val clamped = maxOf(v, 0)
        val a = helperClient.sdkSetInt(
            HelperBinderProtocol.SDK_DEV_STATISTIC, STAT_SEGMENT_SPEED_LIMIT, clamped)
        val b = helperClient.sdkSetInt(
            HelperBinderProtocol.SDK_DEV_STATISTIC, STAT_SEGMENT_SPEED_2, clamped)
        val c = helperClient.sdkCameraGuidance(1, 0, if (clamped > 0) 1 else 0)
        lastSpeedLimit = v
        return "$a/$b/$c"
    }

    private fun statusLine(
        frame: HudInstrumentFids.FrameStatus,
        laneStatus: String,
        speedStatus: String,
    ): String = "guidance=${frame.guidance} street=${frame.street}/${frame.streetName} " +
        "rest=${frame.restRoute} lanes=$laneStatus speed=$speedStatus"

    // ---------------------------------------------------------------- automatic run

    /** "Start Automatic HUD Test" / "Stop HUD Test" (`qt.java:47-55`). */
    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    /** Starts the donor's 49-step tour. No-op (and flagged) while a real route is guiding. */
    fun start() {
        if (_state.value.running) return
        if (routeActive()) {
            _state.value = _state.value.copy(routeActiveBlocked = true)
            Log.i(TAG, "automatic run refused: a route is active")
            return
        }
        _state.value = _state.value.copy(
            running = true, routeActiveBlocked = false, elapsedSeconds = 0, stepIndex = 0,
            lastError = null)
        simJob = scope.launch { runSimulation() }
    }

    /** `l70`: arm once, then a step every three seconds, wrapping at 49. */
    private suspend fun runSimulation() {
        runCatching { fids().ensureActiveNow() }
            .onFailure { Log.e(TAG, "Failed to activate HUD navigation", it) }
        var d = 0
        var elapsed = 0
        // The sim never sends lanes; anything the manual frame left on the strip goes first.
        runCatching { sendLanes(null) }
        while (_state.value.running) {
            val step = HudPanelTestSteps.STEPS[d]
            _state.value = _state.value.copy(
                stepIndex = d,
                iconId = step.iconId,
                distanceMeters = step.distanceMeters,
                streetDisplay = displayStreet(step.road, _state.value.sanitize, sanitizer),
                elapsedSeconds = elapsed,
            )
            try {
                // The street goes out Latinised or raw per the live preference, not per the card's
                // switch: the switch says what the card shows, the preference says what the panel
                // is handed — the same split the donor ends up with (`STRAT:142-144`).
                val frame = fids().sendFrameNow(
                    icon = step.iconId,
                    distanceMeters = step.distanceMeters,
                    road = step.road,
                    sanitize = transliteratePref(),
                    hour = REST_HOUR, minute = REST_MINUTE, mileageMeters = REST_MILEAGE_METERS,
                )
                _state.value = _state.value.copy(
                    lastStatus = statusLine(frame, "-", "-"), lastError = null)
            } catch (e: Exception) {
                // The donor logs and keeps cycling: one refused frame is not a reason to stop.
                Log.e(TAG, "Failed to update HUD step", e)
                _state.value = _state.value.copy(lastError = e.message ?: "Unknown error")
            }
            delay(STEP_INTERVAL_MS)
            elapsed += (STEP_INTERVAL_MS / 1000).toInt()
            d = (d + 1) % HudPanelTestSteps.SIZE
        }
    }

    /** Stop and clear, off the caller's thread (`m70.h()`). */
    fun stop() {
        scope.launch { stopNow() }
    }

    /** Stop and clear, awaited: the full donor stop sequence plus the card back to empty. */
    suspend fun stopNow() {
        simJob?.cancel()
        simJob = null
        _state.value = _state.value.copy(
            running = false, iconId = 0, streetDisplay = "", distanceMeters = 0,
            elapsedSeconds = 0, stepIndex = 0)
        val f = fidsImpl ?: return
        runCatching { f.stopNow() }
            .onFailure { Log.e(TAG, "Failed to clear HUD navigation state", it) }
        lanesSent = false
        // The donor deliberately keeps the last speed limit across a stop; so does this port, so
        // that the same "50" is not re-sent on the next frame and the glass shows the same thing.
    }
}
