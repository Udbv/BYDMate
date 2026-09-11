package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavLaneCodes
import com.bydmate.app.navdata.NavLanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lane guidance on the instrument panel, byte for byte as openbyd's
 * `CarControlImpl.sendLaneGuidanceInfo` (:1400-1467) sends it — the version that is field-proven
 * on this Tang L.
 *
 * One update is two things:
 *  - fourteen writes on the **setting** device: the lane count, twelve lane-state slots
 *    (255 for a slot with no lane), and the distance to the junction;
 *  - **one** write on the **instrument** device carrying all 25 values at once: the count, then
 *    per slot the glyph to draw, the lane-line type and a validity flag. Only eight slots exist
 *    there, so a nine-lane junction reaches the setting device in full but the panel draws eight.
 *
 * The count is deliberately *not* clamped: the donor writes the real number of lanes to both
 * devices and the panel decides what to do with it.
 */
class HudLaneWriter(
    private val helper: HelperClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HudLaneWriter"

        /** SDK device classes the donor writes to (reflective `BYDAuto*Device.set`). */
        const val SDK_DEV_SETTING = HelperBinderProtocol.SDK_DEV_SETTING
        const val SDK_DEV_INSTRUMENT = HelperBinderProtocol.SDK_DEV_INSTRUMENT

        // ---- setting device (CCI:1408-1422) ----

        /** `SET_LANE_NUM`: how many lanes the junction has. */
        const val FID_SET_LANE_NUM = 1_285_554_200

        /** `SET_LANE_STATES`: twelve lane-state slots, in the donor's own order — note that the
         *  last four are not contiguous with the first eight. */
        val SET_LANE_STATES = intArrayOf(
            1_285_554_208, 1_285_554_212, 1_285_554_216, 1_285_554_220,
            1_285_554_224, 1_285_554_228, 1_285_554_232, 1_285_554_236,
            1_285_554_260, 1_285_554_264, 1_285_554_268, 1_285_554_272,
        )

        /** `SET_LANE_DIST`: metres to the junction; -1 clears. */
        const val FID_SET_LANE_DIST = 1_285_554_184

        // ---- instrument device (CCI:1425-1454) ----
        // Names from BYD's own catalogue (com.byd.feature on this car): 0x198020D8
        // INSTRUMENT_TOTAL_LANES_SET, and per slot 0x19802058 INSTRUMENT_LANE_n_GUIDANCE_ARROW_SET,
        // 0x19802060 INSTRUMENT_n_LANE_LINE_TYPE_SET, 0x19802064 INSTRUMENT_IS_LANE_n_RECOMMENDED_SET,
        // repeating every 16.

        /** `INSTRUMENT_TOTAL_LANES_SET`; 0 clears the strip. */
        const val FID_LANE_COUNT = 0x198020D8

        /** `INSTRUMENT_LANE_n_GUIDANCE_ARROW_SET`: which arrow the lane draws. */
        fun fidGlyph(slot: Int): Int = 0x19802058 + slot * 16

        /** `INSTRUMENT_n_LANE_LINE_TYPE_SET`: how the lane's divider is drawn. */
        fun fidLineType(slot: Int): Int = 0x19802060 + slot * 16

        /** `INSTRUMENT_IS_LANE_n_RECOMMENDED_SET`: whether the slot holds a lane at all. */
        fun fidRecommended(slot: Int): Int = 0x19802064 + slot * 16

        /** Line types: 0 alongside a lane the route takes, 5 for a plain lane, 14 unused slot. */
        const val LINE_TYPE_ON_ROUTE = 0
        const val LINE_TYPE_PLAIN = 5
        const val LINE_TYPE_UNUSED = 14

        /** The donor sends 1 for every slot that holds a lane — on or off route — and -1 for the
         *  rest. Despite the feature's name this is a "slot is valid" flag, not a recommendation;
         *  the glyph carries the highlight. */
        const val RECOMMENDED_PRESENT = 1
        const val RECOMMENDED_ABSENT = -1

        const val GLYPH_NONE = -1

        /** "No lane" / "not on the route", as the panel spells it. */
        const val NO_LANE = NavLaneCodes.EMPTY

        /** Lane codes that carry more than one direction and therefore have combined glyphs
         *  (donor `isComplexLane`, CCI:356-358). */
        private val COMPLEX = setOf(2, 4, 6, 7, 9, 10, 11, 12, 16, 17, 18, 19, 20)

        internal fun isComplexLane(code: Int): Boolean = code in COMPLEX

        /** Combined glyph for a multi-direction lane and the direction taken; -1 when the pair
         *  has no glyph (donor `complexGuide`, CCI:172-260). */
        internal fun complexGlyph(code: Int, dir: Int): Int = when (code) {
            2 -> when (dir) { 0 -> 51; 1 -> 52; else -> -1 }
            4 -> when (dir) { 0 -> 53; 3 -> 54; else -> -1 }
            6 -> when (dir) { 1 -> 55; 3 -> 56; else -> -1 }
            7 -> when (dir) { 0 -> 57; 1 -> 58; 3 -> 59; else -> -1 }
            9 -> when (dir) { 0 -> 60; 5 -> 61; else -> -1 }
            10 -> when (dir) { 0 -> 62; 8 -> 63; else -> -1 }
            11 -> when (dir) { 1 -> 64; 5 -> 65; else -> -1 }
            12 -> when (dir) { 3 -> 66; 8 -> 67; else -> -1 }
            16 -> when (dir) { 0 -> 70; 1 -> 71; 5 -> 72; else -> -1 }
            17 -> when (dir) { 3 -> 73; 5 -> 74; else -> -1 }
            18 -> when (dir) { 1 -> 75; 3 -> 76; 5 -> 77; else -> -1 }
            19 -> when (dir) { 0 -> 78; 3 -> 79; 5 -> 80; else -> -1 }
            20 -> when (dir) { 1 -> 81; 8 -> 82; else -> -1 }
            else -> -1
        }

        /** Plain glyph for a lane nobody is being sent down (donor `getSimpleIdBack`, :294-302). */
        internal fun plainGlyph(code: Int): Int = when {
            code in 0 until 13 -> code + 1
            code in 16 until 255 -> code
            else -> -1
        }

        /** Highlighted glyph for the direction the route takes (donor `getSimpleIdFront`, :304-312). */
        internal fun activeGlyph(dir: Int): Int = when {
            dir in 0 until 13 -> dir + 26
            dir in 16 until 255 -> dir + 25
            else -> -1
        }

        /**
         * Glyph for one lane (donor `getLaneGuideVal`, CCI:286-292). A simple lane off the route
         * draws its own code; a simple lane on the route draws the *front*, not its code; a
         * multi-direction lane draws the combined glyph, falling back to its plain one.
         */
        internal fun laneGuideVal(code: Int, front: Int): Int =
            if (!isComplexLane(code)) {
                if (front == NO_LANE || front == -1) plainGlyph(code) else activeGlyph(front)
            } else {
                val combined = complexGlyph(code, front)
                if (combined != -1) combined else plainGlyph(code)
            }

        /**
         * Pure: the fourteen (feature, value) pairs for the setting device, in the donor's order —
         * count, twelve lane states, distance (CCI:1408-1422).
         */
        fun settingWrites(lanes: NavLanes): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>(2 + NavLanes.SETTING_LANES)
            out += FID_SET_LANE_NUM to lanes.size
            for (slot in 0 until NavLanes.SETTING_LANES) {
                out += SET_LANE_STATES[slot] to if (slot < lanes.size) lanes.codes[slot] else NO_LANE
            }
            out += FID_SET_LANE_DIST to lanes.distanceMeters
            return out
        }

        /**
         * Pure: the single 25-value instrument batch (CCI:1425-1454). Index 0 is the lane count;
         * `1+i`, `9+i` and `17+i` are slot i's glyph, line type and validity flag.
         */
        fun instrumentBatch(lanes: NavLanes): Pair<IntArray, IntArray> {
            val fids = IntArray(25)
            val values = IntArray(25)
            fids[0] = FID_LANE_COUNT
            values[0] = lanes.size
            for (i in 0 until NavLanes.MAX_LANES) {
                val glyph: Int
                val lineType: Int
                val present: Int
                if (i < lanes.size) {
                    val front = lanes.frontAt(i)
                    glyph = laneGuideVal(lanes.codes[i], front)
                    lineType = if (front == NO_LANE || front == -1) LINE_TYPE_PLAIN else LINE_TYPE_ON_ROUTE
                    present = RECOMMENDED_PRESENT
                } else {
                    glyph = GLYPH_NONE
                    lineType = LINE_TYPE_UNUSED
                    present = RECOMMENDED_ABSENT
                }
                fids[1 + i] = fidGlyph(i); values[1 + i] = glyph
                fids[9 + i] = fidLineType(i); values[9 + i] = lineType
                fids[17 + i] = fidRecommended(i); values[17 + i] = present
            }
            return fids to values
        }
    }

    private val mutex = Mutex()
    private var lastSent: NavLanes? = null
    @Volatile var writes: Long = 0L
        private set
    @Volatile var failures: Long = 0L
        private set

    /** Pushes [lanes] when they differ from what the panel already shows. Coalesced: a write
     *  in flight makes this tick a no-op, the next tick picks the newest state up. */
    fun update(lanes: NavLanes) {
        if (lanes.isEmpty) { clear(); return }
        if (lanes == lastSent) return
        if (!mutex.tryLock()) return
        scope.launch {
            try {
                send(lanes)
                lastSent = lanes
            } finally {
                mutex.unlock()
            }
        }
    }

    /** Blanks the lane strip once, and only if something was ever sent (donor STRAT:212-229). */
    fun clear() {
        val last = lastSent
        if (last == null || last.isEmpty) return
        scope.launch {
            mutex.withLock {
                send(NavLanes.CLEARED)
                lastSent = NavLanes.CLEARED
            }
        }
    }

    private suspend fun send(lanes: NavLanes) {
        var ok = 0
        var bad = 0
        fun count(status: Int?) {
            writes++
            if (HelperClient.sdkAccepted(status)) ok++ else { bad++; failures++ }
        }
        for ((fid, value) in settingWrites(lanes)) {
            count(runCatching { helper.sdkSetInt(SDK_DEV_SETTING, fid, value) }.getOrNull())
        }
        val (fids, values) = instrumentBatch(lanes)
        count(runCatching { helper.sdkSetIntArray(SDK_DEV_INSTRUMENT, fids, values) }.getOrNull())

        val line = "lanes=${lanes.size} ${lanes.codesLine()} dist=${lanes.distanceMeters} " +
            "accepted=$ok rejected=$bad"
        com.bydmate.app.diagnostics.TripDebugLog.event("PANEL", line)
        Log.i(TAG, line)
    }
}
