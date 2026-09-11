package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavGuidanceHub
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Guidance over the instrument panel, ported from openbyd 2.4.3 (`CarControlImpl` +
 * `CanBydFidStrategy`): the same `BYDAutoInstrumentDevice` / `BYDAutoSettingDevice` /
 * `BYDAutoStatisticDevice` feature ids BYD's own navigation writes, sent through the helper
 * daemon, plus the SDK's high-level `sendXxx` calls the donor makes alongside them.
 *
 * Two channels, always both: the raw feature write puts the value on the bus, and the SDK call
 * tells the cluster's own navigation service about it. The donor sends both and never inspects
 * the result of either; BYDMate logs the SDK status (0 = accepted) but likewise never retries on
 * it, because a non-zero code has never been observed to mean "try it differently".
 *
 * Writes happen only when a value changes, like the donor.
 */
class HudInstrumentFids(
    private val helper: HelperClient,
    private val scope: CoroutineScope,
    /** Whether to Latinise the street name; read per write so the setting applies immediately. */
    private val sanitizePref: () -> Boolean = { true },
    private val sanitizer: HudTextSanitizer = HudTextSanitizer.DEFAULT,
) {
    companion object {
        private const val TAG = "HudInstrumentFids"

        /** Instrument-panel device of the autoservice catalog (ClusterFrameUi7.DEV_INSTRUMENT). */
        const val DEV_INSTRUMENT = 1007

        // ---- Instrument features (raw autoservice writes) ----
        const val FID_NAVI_STATUS = 1138753594          // 0x43E0003A
        const val FID_GUIDE_ICON = 1139806224           // 0x43F01010
        const val FID_GUIDE_DISTANCE = 1139806232       // 0x43F01018
        const val FID_GUIDE_ICON_DUAL = 1139806256      // 0x43F01030
        const val FID_TRIP_HOUR = 1139810320            // 0x43F02010
        const val FID_TRIP_MINUTE = 1139810328          // 0x43F02018
        const val FID_TRIP_SECOND = 1139810334          // 0x43F0201E
        const val FID_TRIP_MILEAGE = 1139810344         // 0x43F02028
        const val FID_ARRIVE_MINUTE = 1139839008        // 0x43F09020
        /** Next-street name, a byte-array feature. The donor knows exactly one. */
        const val FID_STREET_NAME = 0x43FA1008          // 1140461576

        // ---- Navigation status values (BYDAutoInstrumentDevice NAVI_*) ----
        const val NAVI_ACTIVE = 2                       // NAVI_OPEN_SET_DEST
        const val NAVI_STOPPED = 4                      // NAVI_CLOSE
        /** The cluster reports this while its own SOME/IP navigation drives the panel; the donor
         *  treats it as "already armed" and does not re-arm over it. */
        const val NAVI_SOMEIP_ACTIVE = 621              // 0x26D

        // ---- Setting-device features ----
        const val SET_NAVI_SCREEN_STATUS = 1276174357   // 0x4C10E015
        const val HUD_SCREEN_NAV_LAYOUT = 3
        const val SET_LANE_DIST = 1285554184            // 0x4CA00008
        const val SET_LANE_NUM = 1285554200             // 0x4CA00018
        const val LANE_EMPTY = 255
        /** 0x4CA00020, 24, 28, 2C, 30, 34, 38, 3C, 54, 58, 5C, 60 (CarControlImpl:168). */
        val SET_LANE_STATES = intArrayOf(
            1285554208, 1285554212, 1285554216, 1285554220, 1285554224, 1285554228,
            1285554232, 1285554236, 1285554260, 1285554264, 1285554268, 1285554272,
        )

        // ---- Statistic-device features, all cleared on stop ----
        const val STAT_NAVI_STATUS = 1083203624         // 0x40906028
        const val STAT_CUR_SEGMENT = 1083203600         // 0x40906010
        const val STAT_SEGMENT_LENGTH = 1083203608      // 0x40906018
        const val STAT_SEGMENT_SPEED_LIMIT = 1083203616 // 0x40906020
        const val STAT_SEGMENT_SIZE = 1083203632        // 0x40906030
        const val STAT_SEGMENT_INDEX_2 = 754057256      // 0x2CF20028
        const val STAT_SEGMENT_LENGTH_2 = 754057264     // 0x2CF20030, a double
        const val STAT_SEGMENT_SPEED_2 = 754057272      // 0x2CF20038
        const val STAT_SEGMENT_LIGHT_2 = 754057276      // 0x2CF2003C

        // ---- Lane instrument batch (25 features written in one SDK set() call) ----
        const val LANE_BATCH_COUNT = 427827416          // 0x198020D8
        const val LANE_BATCH_GUIDE = 427827288          // 0x19802058 + 16i
        const val LANE_BATCH_FLAG_A = 427827296         // 0x19802060 + 16i
        const val LANE_BATCH_FLAG_B = 427827300         // 0x19802064 + 16i

        /** Donor's SDK ceiling for the remaining distance (0xFFFFFFFE), narrowed to an int on
         *  the feature write. Int.MAX_VALUE is the same thing after that cast and keeps the
         *  clamp honest on our side. */
        const val REST_MILEAGE_MAX = Int.MAX_VALUE.toLong()

        /**
         * Not a donor behaviour — openbyd sends the street name at whatever length it arrives.
         * This is a guard against a corrupted accessibility read: a screen-reader label that
         * lost its bounds can be thousands of characters, and none of that belongs on a vehicle
         * bus. Real street names are far below this.
         */
        const val MAX_STREET_CHARS = 200

        /** First bytes as hex, for the trip log: the one piece of evidence that survives a drive. */
        fun preview(bytes: ByteArray, count: Int = 8): String =
            bytes.take(count).joinToString(" ") { "%02x".format(it) }
    }

    private val mutex = Mutex()
    private var active = false
    private var lastIcon = -1
    private var lastDistance = -1
    private var lastHour = -1
    private var lastMinute = -1
    private var lastMileage = -1L
    private var lastStreet: String? = null
    @Volatile var writes: Long = 0L
        private set
    @Volatile var failures: Long = 0L
        private set

    /** Raw autoservice int write to the instrument device. */
    private suspend fun write(fid: Int, value: Int): Boolean {
        val ok = runCatching { helper.write(DEV_INSTRUMENT, fid, value) }.getOrDefault(false)
        writes++
        if (!ok) failures++
        return ok
    }

    /** One BYD-SDK call. Counts like a write; a null status means the daemon never got there. */
    private suspend fun sdk(call: suspend () -> Int?): Int? {
        val status = runCatching { call() }.getOrNull()
        writes++
        if (!HelperClient.sdkAccepted(status)) failures++
        return status
    }

    /** Called every push-loop tick; coalesces into one in-flight write batch. */
    fun update(s: NavGuidanceHub.Snapshot) {
        if (!mutex.tryLock()) return
        scope.launch {
            try { apply(s) } finally { mutex.unlock() }
        }
    }

    private suspend fun apply(s: NavGuidanceHub.Snapshot) {
        ensureActive()
        writeGuidance(s)
        writeStreetName(s.road)
        writeRestRoute(s)
    }

    /**
     * The donor's self-healing re-arm (`CanBydFidStrategy.ensureHudActive`): on every update it
     * reads the live navigation status back and re-runs the start sequence unless the cluster
     * says it is already showing navigation. That is what survives the cluster rebooting, the
     * car's own map taking the panel, or a firmware that quietly drops the arming write — no
     * timer, no reconnect logic, just a read per tick.
     *
     * A failed read (null) is not evidence of anything, so the cached state stands.
     */
    private suspend fun ensureActive() {
        var start = !active
        if (active) {
            val live = runCatching { helper.read(DEV_INSTRUMENT, FID_NAVI_STATUS) }.getOrNull()
            if (live != null && live.toInt() != NAVI_ACTIVE && live.toInt() != NAVI_SOMEIP_ACTIVE) {
                Log.i(TAG, "navi status read back as $live, re-arming")
                TripDebugLog.event("PANEL", "navi status read back as $live, re-arming")
                start = true
            }
        }
        if (start) startSequence()
    }

    /** `sendAutoNaviStatus(2)`: the feature write, the screen layout, the statistic flag, the
     *  SDK call. Exactly this order (CarControlImpl:1243-1321). */
    private suspend fun startSequence() {
        val a = write(FID_NAVI_STATUS, NAVI_ACTIVE)
        val b = sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_SETTING, SET_NAVI_SCREEN_STATUS, HUD_SCREEN_NAV_LAYOUT) }
        val c = sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_STATISTIC, STAT_NAVI_STATUS, 1) }
        val d = sdk { helper.sdkNaviStatus(NAVI_ACTIVE) }
        active = true
        Log.i(TAG, "navi status -> active raw=$a screen=$b stat=$c sdk=$d")
        TripDebugLog.event("PANEL", "navi status -> active raw=$a screen=$b stat=$c sdk=$d")
    }

    /**
     * `sendSimpleGuidanceInfo(icon, distance)`. When the guidance source already names a panel
     * glyph (the Waze arrow-signature path does, including the clockwise roundabout exits that
     * have no Gaode number), that wins; otherwise the Gaode code is translated.
     */
    private suspend fun writeGuidance(s: NavGuidanceHub.Snapshot) {
        val icon = if (s.panelIcon > 0) s.panelIcon else HudInstrumentIcons.fromGaode(s.maneuverGaode)
        sendGuidance(icon, s.distanceMeters, "gaode=${s.maneuverGaode} panel=${s.panelIcon}")
    }

    /** The three features and the SDK call; null when nothing changed and nothing was sent. */
    private suspend fun sendGuidance(icon: Int, distanceMeters: Int, origin: String): Int? {
        if (icon == lastIcon && distanceMeters == lastDistance) return null
        val a = write(FID_GUIDE_ICON, icon)
        val b = write(FID_GUIDE_ICON_DUAL, icon)
        val c = write(FID_GUIDE_DISTANCE, distanceMeters)
        val status = sdk { helper.sdkSimpleGuidance(icon, distanceMeters) }
        if (icon != lastIcon) {
            val line = "guide $origin -> icon=$icon " +
                "(${HudInstrumentIcons.name(icon)}) dist=$distanceMeters raw=$a/$b/$c sdk=$status"
            Log.i(TAG, line)
            TripDebugLog.event("PANEL", line)
        }
        lastIcon = icon
        lastDistance = distanceMeters
        return status
    }

    /**
     * `sendNextPathName(name)`: UTF-16LE bytes with no BOM into the one byte-array feature, then
     * the SDK call with the Java string.
     *
     * UTF-16LE is not a guess. openbyd hardcodes `Charset.forName("UTF-16LE")` and has drawn
     * correct street names on BYD clusters for years; BYDMate's earlier charset picker existed
     * only because the write status is positive for every encoding, so the panel could not be
     * asked. The donor settles it.
     *
     * An empty name becomes a single space, as in the donor — an empty buffer leaves the last
     * street on the glass, a space clears it.
     */
    private suspend fun writeStreetName(road: String) {
        sendStreetName(road, sanitizePref())
    }

    /** Byte feature + SDK call; both statuses, or (null, null) when the name has not changed. */
    private suspend fun sendStreetName(road: String, sanitize: Boolean): Pair<Int?, Int?> {
        val sanitised = if (sanitize) sanitizer.sanitize(road) else road
        val name = sanitised.take(MAX_STREET_CHARS).ifEmpty { " " }
        if (name == lastStreet) return null to null
        val bytes = name.toByteArray(Charsets.UTF_16LE)
        val status = sdk { helper.sdkSetBytes(HelperBinderProtocol.SDK_DEV_INSTRUMENT, FID_STREET_NAME, bytes) }
        val nameStatus = sdk { helper.sdkNextPathName(name) }
        // The charset and the first bytes are the whole point of logging this: an accepted status
        // says the panel took the buffer, never that it drew what we meant. If the glass shows
        // Chinese, these bytes and this charset name identify the mismatch.
        TripDebugLog.changed(
            "PANEL", "street",
            "street='$name' charset=UTF-16LE bytes=${bytes.size} [${preview(bytes)}] " +
                "status=$status sdk=$nameStatus accepted=${HelperClient.sdkAccepted(status)}")
        // Remembered whatever the status: the donor never inspects it, and a panel that rejects
        // the feature must not be hammered with the same name on every maneuver.
        lastStreet = name
        return status to nameStatus
    }

    /** `sendRestRouteInfo(hour, minute, mileage)` — five features then the SDK call, this order. */
    private suspend fun writeRestRoute(s: NavGuidanceHub.Snapshot) {
        if (s.etaSeconds <= 0 || s.totalDistMeters <= 0) return
        val totalMin = s.etaSeconds / 60
        val hour = (totalMin / 60).coerceIn(0, 254)
        val minute = (totalMin % 60).coerceIn(0, 59)
        val mileage = s.totalDistMeters.toLong().coerceIn(0L, REST_MILEAGE_MAX)
        sendRestRoute(hour, minute, mileage)
    }

    /** Five features then the SDK call; null when the remaining route has not changed. */
    private suspend fun sendRestRoute(hour: Int, minute: Int, mileageMeters: Long): Int? {
        val mileage = mileageMeters.coerceIn(0L, REST_MILEAGE_MAX)
        if (hour == lastHour && minute == lastMinute && mileage == lastMileage) return null
        write(FID_TRIP_MILEAGE, mileage.toInt())
        write(FID_TRIP_HOUR, hour)
        write(FID_TRIP_MINUTE, minute)
        write(FID_TRIP_SECOND, 0)
        val arrive = Calendar.getInstance().apply { add(Calendar.MINUTE, hour * 60 + minute) }
        write(FID_ARRIVE_MINUTE, arrive.get(Calendar.MINUTE))
        val status = sdk { helper.sdkRestRoute(hour, minute, mileage) }
        lastHour = hour; lastMinute = minute; lastMileage = mileage
        return status
    }

    // ---- Entry points for the HUD panel test screen (HudPanelTester) ----
    // The push loop never needs these: it feeds whole snapshots and fires and forgets. The test
    // screen picks the values by hand, must await each frame, and shows the SDK status of every
    // verb, so it needs the same writes reachable one frame at a time.

    /** Statuses of one manual frame; a null means that verb had nothing new to send. */
    data class FrameStatus(
        val guidance: Int?,
        val street: Int?,
        val streetName: Int?,
        val restRoute: Int?,
    )

    /** The donor's `ensureHudActive`, awaited: arms the panel when it is not already navigating. */
    suspend fun ensureActiveNow() {
        mutex.withLock { ensureActive() }
    }

    /**
     * One hand-built frame in the donor's order (openbyd `yt` case 1 / `CanBydFidStrategy`):
     * guidance, street name, remaining route. Change detection is the donor's, so sending the
     * same frame twice writes nothing the second time.
     */
    suspend fun sendFrameNow(
        icon: Int,
        distanceMeters: Int,
        road: String,
        sanitize: Boolean,
        hour: Int = 0,
        minute: Int = 15,
        mileageMeters: Long = 15_000L,
    ): FrameStatus = mutex.withLock {
        ensureActive()
        val guidance = sendGuidance(icon, distanceMeters, "manual")
        val (street, streetName) = sendStreetName(road, sanitize)
        val rest = sendRestRoute(hour, minute, mileageMeters)
        FrameStatus(guidance, street, streetName, rest)
    }

    /** Route ended: guidance cleared, navigation status back to stopped (donor `turnOffNavi`). */
    fun stop() {
        scope.launch { stopNow() }
    }

    /**
     * `sendAutoNaviStatus(4)`, the whole donor list in the donor's order: the status feature,
     * every guidance feature back to its idle value, the statistic block, the setting block, the
     * lane batch, then the SDK call.
     *
     * Awaited, because the controller calls this before it re-opens the channel and the old
     * session's "stopped" must never land after the new session's "active".
     */
    suspend fun stopNow() {
        mutex.withLock {
            if (!active) return
            write(FID_NAVI_STATUS, NAVI_STOPPED)

            // (c) instrument features back to idle. Distance is -1, not 0: 0 is a real distance
            // and the panel draws "in 0 m" for it.
            write(FID_GUIDE_ICON, 0)
            write(FID_GUIDE_ICON_DUAL, 0)
            write(FID_GUIDE_DISTANCE, -1)
            sdk { helper.sdkSetBytes(HelperBinderProtocol.SDK_DEV_INSTRUMENT, FID_STREET_NAME, "".toByteArray(Charsets.UTF_16LE)) }
            write(FID_TRIP_MILEAGE, -1)
            write(FID_TRIP_HOUR, 0)
            write(FID_TRIP_MINUTE, 0)
            write(FID_TRIP_SECOND, 0)
            write(FID_ARRIVE_MINUTE, 0)

            // (d) statistic block; one of them is a double.
            for (fid in intArrayOf(
                STAT_NAVI_STATUS, STAT_CUR_SEGMENT, STAT_SEGMENT_LENGTH,
                STAT_SEGMENT_SPEED_LIMIT, STAT_SEGMENT_SIZE, STAT_SEGMENT_INDEX_2,
            )) {
                sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_STATISTIC, fid, 0) }
            }
            sdk { helper.sdkSetDouble(HelperBinderProtocol.SDK_DEV_STATISTIC, STAT_SEGMENT_LENGTH_2, 0.0) }
            sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_STATISTIC, STAT_SEGMENT_SPEED_2, 0) }
            sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_STATISTIC, STAT_SEGMENT_LIGHT_2, 0) }

            // (e) setting block: no lanes, no lane distance, all twelve slots empty.
            sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_SETTING, SET_LANE_NUM, 0) }
            sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_SETTING, SET_LANE_DIST, 0) }
            for (fid in SET_LANE_STATES) {
                sdk { helper.sdkSetInt(HelperBinderProtocol.SDK_DEV_SETTING, fid, LANE_EMPTY) }
            }

            // (f) one 25-feature instrument batch clearing the lane strip. This is the donor's
            // own stop code, not the lane writer's: stopping must clear the strip even if lanes
            // were never sent this session.
            val (fids, values) = laneClearBatch()
            sdk { helper.sdkSetIntArray(HelperBinderProtocol.SDK_DEV_INSTRUMENT, fids, values) }

            // (g) and finally the SDK.
            val status = sdk { helper.sdkNaviStatus(NAVI_STOPPED) }
            Log.i(TAG, "navi status -> stopped sdk=$status writes=$writes failures=$failures")
            TripDebugLog.event("PANEL", "navi status -> stopped sdk=$status")

            active = false
            lastIcon = -1; lastDistance = -1
            lastHour = -1; lastMinute = -1; lastMileage = -1L
            lastStreet = null
        }
    }

    /** Count 0, and every one of the eight lane slots at the donor's empty triple (-1, 14, -1). */
    private fun laneClearBatch(): Pair<IntArray, IntArray> {
        val fids = IntArray(25)
        val values = IntArray(25)
        fids[0] = LANE_BATCH_COUNT; values[0] = 0
        for (i in 0 until 8) {
            fids[1 + i] = LANE_BATCH_GUIDE + 16 * i; values[1 + i] = -1
            fids[9 + i] = LANE_BATCH_FLAG_A + 16 * i; values[9 + i] = 14
            fids[17 + i] = LANE_BATCH_FLAG_B + 16 * i; values[17 + i] = -1
        }
        return fids to values
    }
}
