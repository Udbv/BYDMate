package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavGuidanceHub
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Guidance over the instrument panel's autoservice features (the "CAN" path of openbyd 2.4.3,
 * `CarControlImpl.sendSimpleGuidanceInfo` / `sendRestRouteInfo` / `turnOnNavi`): the same
 * `BYDAutoInstrumentDevice` feature ids the BYD navigation SDK writes, sent through the helper
 * daemon. Writes happen only when a value changes, like the donor. The next-street name is a
 * byte-array feature and goes through the daemon's TX_WRITE_BYTES, which calls BYD's own SDK.
 */
class HudInstrumentFids(
    private val helper: HelperClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HudInstrumentFids"
        /** Instrument-panel device of the autoservice catalog (ClusterFrameUi7.DEV_INSTRUMENT). */
        const val DEV_INSTRUMENT = 1007
        const val FID_NAVI_STATUS = 1138753594
        const val NAVI_ACTIVE = 2
        const val NAVI_STOPPED = 4
        const val FID_GUIDE_ICON = 1139806224
        const val FID_GUIDE_ICON_DUAL = 1139806256
        const val FID_GUIDE_DISTANCE = 1139806232
        const val FID_TRIP_HOUR = 1139810320
        const val FID_TRIP_MINUTE = 1139810328
        const val FID_TRIP_MILEAGE = 1139810344
        const val FID_ARRIVE_MINUTE = 1139839008
        const val REST_MILEAGE_MAX = 999_999L

        /**
         * Next-street name, a byte-array feature. BYD ships two: the domestic one and an
         * `_OVERASEA_` variant, which is the likelier fit for a car showing Cyrillic. Both are
         * tried, overseas first, and whichever the panel accepts is remembered for the session.
         */
        const val FID_STREET_NAME_OVERSEAS = 0x1F7A1008
        const val FID_STREET_NAME = 0x43FA1008

        /** The panel truncates anyway; keep a corrupted a11y read from sending a huge buffer. */
        const val MAX_STREET_BYTES = 96
    }

    private val mutex = Mutex()
    private var active = false
    private var lastIcon = -1
    private var lastDistance = -1
    private var lastHour = -1
    private var lastMinute = -1
    private var lastMileage = -1L
    @Volatile var writes: Long = 0L
        private set
    @Volatile var failures: Long = 0L
        private set

    private suspend fun write(fid: Int, value: Int): Boolean {
        val ok = runCatching { helper.write(DEV_INSTRUMENT, fid, value) }.getOrDefault(false)
        writes++
        if (!ok) failures++
        return ok
    }

    /** Called every push-loop tick; coalesces into one in-flight write batch. */
    fun update(s: NavGuidanceHub.Snapshot) {
        if (!mutex.tryLock()) return
        scope.launch {
            try { apply(s) } finally { mutex.unlock() }
        }
    }

    private suspend fun apply(s: NavGuidanceHub.Snapshot) {
        if (!active) {
            val ok = write(FID_NAVI_STATUS, NAVI_ACTIVE)
            active = true
            Log.i(TAG, "navi status -> active ok=$ok")
        }
        // The panel numbers its glyphs differently from our Gaode codes; writing the raw code
        // drew a detour for a roundabout, a left arrow for a slight right, and the Chinese
        // destination glyph on arrival (see HudInstrumentIcons).
        val icon = HudInstrumentIcons.fromGaode(s.maneuverGaode)
        if (icon != lastIcon || s.distanceMeters != lastDistance) {
            val a = write(FID_GUIDE_ICON, icon)
            val b = write(FID_GUIDE_ICON_DUAL, icon)
            val c = write(FID_GUIDE_DISTANCE, s.distanceMeters)
            if (icon != lastIcon) {
                Log.i(TAG, "guide gaode=${s.maneuverGaode} -> icon=$icon dist=${s.distanceMeters} ok=$a/$b/$c")
                com.bydmate.app.diagnostics.TripDebugLog.event(
                    "PANEL",
                    "guide gaode=${s.maneuverGaode} -> icon=$icon dist=${s.distanceMeters} accepted=$a/$b/$c",
                )
            }
            lastIcon = icon
            lastDistance = s.distanceMeters
        }
        writeStreetName(s.road)
        if (s.etaSeconds > 0 && s.totalDistMeters > 0) {
            val totalMin = s.etaSeconds / 60
            val hour = (totalMin / 60).coerceIn(0, 254)
            val minute = (totalMin % 60).coerceIn(0, 59)
            val mileage = s.totalDistMeters.toLong().coerceIn(0L, REST_MILEAGE_MAX)
            if (hour != lastHour || minute != lastMinute || mileage != lastMileage) {
                write(FID_TRIP_MILEAGE, mileage.toInt())
                write(FID_TRIP_HOUR, hour)
                write(FID_TRIP_MINUTE, minute)
                val arrive = Calendar.getInstance().apply { add(Calendar.MINUTE, hour * 60 + minute) }
                write(FID_ARRIVE_MINUTE, arrive.get(Calendar.MINUTE))
                lastHour = hour; lastMinute = minute; lastMileage = mileage
            }
        }
    }


    /** Last name pushed, so an unchanged street costs nothing. */
    private var lastStreet: String? = null

    /** The feature the panel accepted; null until one has, so both are tried once. */
    private var streetFid: Int? = null

    /**
     * Pushes the next-street name. Which of the two features this firmware honours is not
     * knowable ahead of time, so the overseas one is tried first and the domestic one second;
     * the first that reports a real write is kept for the rest of the session.
     */
    private suspend fun writeStreetName(road: String) {
        val name = road.take(MAX_STREET_BYTES)
        if (name == lastStreet) return
        val bytes = truncateUtf8(name, MAX_STREET_BYTES)
        val candidates = streetFid?.let { listOf(it) } ?: listOf(FID_STREET_NAME_OVERSEAS, FID_STREET_NAME)
        for (fid in candidates) {
            val status = runCatching { helper.writeBytes(DEV_INSTRUMENT, fid, bytes) }.getOrNull()
            writes++
            if (status != null && status > 0) {
                if (streetFid != fid) {
                    streetFid = fid
                    Log.i(TAG, "street name feature 0x${fid.toString(16)} accepted")
                    com.bydmate.app.diagnostics.TripDebugLog.event(
                        "PANEL", "street name feature 0x${fid.toString(16)} accepted")
                }
                lastStreet = name
                com.bydmate.app.diagnostics.TripDebugLog.changed("PANEL", "street", "street='$name'")
                return
            }
        }
        failures++
        // Remember the attempt so a panel that takes neither is not retried on every maneuver.
        lastStreet = name
        com.bydmate.app.diagnostics.TripDebugLog.changed(
            "PANEL", "street", "street='$name' rejected by both features")
    }

    /** Cuts UTF-8 on a character boundary so the panel never receives half a code point. */
    internal fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        var out = text.toByteArray(Charsets.UTF_8)
        var end = text.length
        while (out.size > maxBytes && end > 0) {
            end--
            out = text.substring(0, end).toByteArray(Charsets.UTF_8)
        }
        return out
    }

    /** Route ended: guidance cleared, navigation status back to stopped (donor `turnOffNavi`). */
    fun stop() {
        scope.launch { stopNow() }
    }

    /** Same, awaited: the controller calls this before it re-opens the channel so the old
     *  session's "stopped" can never land after the new session's "active". */
    suspend fun stopNow() {
        mutex.withLock {
            if (!active) return
            write(FID_GUIDE_ICON, 0)
            write(FID_GUIDE_ICON_DUAL, 0)
            write(FID_GUIDE_DISTANCE, 0)
            val ok = write(FID_NAVI_STATUS, NAVI_STOPPED)
            Log.i(TAG, "navi status -> stopped ok=$ok writes=$writes failures=$failures")
            active = false
            lastIcon = -1; lastDistance = -1; lastHour = -1; lastMinute = -1; lastMileage = -1L
        }
    }
}
