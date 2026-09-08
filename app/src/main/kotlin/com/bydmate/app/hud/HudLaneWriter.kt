package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavLaneCodes
import com.bydmate.app.navdata.NavLanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lane guidance on the instrument panel, on the same autoservice features that already carry the
 * maneuver arrow (the only channel that reaches the Tang L glass, see
 * docs/investigations/tang-l-hud-someip.md).
 *
 * The panel keeps eight lane slots. Each slot takes three feature writes: the glyph to draw, a
 * state (recommended or not), and a validity flag that switches the slot off. A ninth feature
 * carries how many lanes are in use. Layout and glyph numbering follow openbyd 2.4.3's
 * `sendLaneGuidanceInfo`, which matches what the factory navigation writes.
 *
 * The donor sends all 25 values as one array write. The helper daemon only offers single-integer
 * writes, so this sends them one by one; lanes change once per junction, not per frame, so the
 * extra round trips are irrelevant and no new daemon transaction is needed.
 */
class HudLaneWriter(
    private val helper: HelperClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HudLaneWriter"

        /** Instrument-panel device of the autoservice catalog, as used for the maneuver arrow. */
        const val DEV_INSTRUMENT = HudInstrumentFids.DEV_INSTRUMENT

        // Feature ids and their real names come from BYD's own catalogue, compiled into
        // com.byd.feature on this car: 0x198020D8 INSTRUMENT_TOTAL_LANES_SET, and per slot
        // 0x19802058 INSTRUMENT_LANE_n_GUIDANCE_ARROW_SET, 0x19802060 INSTRUMENT_n_LANE_LINE_TYPE_SET,
        // 0x19802064 INSTRUMENT_IS_LANE_n_RECOMMENDED_SET, repeating every 16.

        /** `INSTRUMENT_TOTAL_LANES_SET`; 0 clears the strip. */
        const val FID_LANE_COUNT = 0x198020D8

        /** `INSTRUMENT_LANE_n_GUIDANCE_ARROW_SET`: which arrow the lane draws. */
        fun fidGlyph(slot: Int): Int = 0x19802058 + slot * 16

        /** `INSTRUMENT_n_LANE_LINE_TYPE_SET`: how the lane's divider is drawn. */
        fun fidLineType(slot: Int): Int = 0x19802060 + slot * 16

        /** `INSTRUMENT_IS_LANE_n_RECOMMENDED_SET`: whether the route uses this lane. */
        fun fidRecommended(slot: Int): Int = 0x19802064 + slot * 16

        /** Line types the donor was seen to send: 0 alongside a lane the route takes, 5 for a
         *  plain lane, 14 for an unused slot. */
        const val LINE_TYPE_ON_ROUTE = 0
        const val LINE_TYPE_PLAIN = 5
        const val LINE_TYPE_UNUSED = 14

        /** Recommended flag. The donor sent 1 for every lane present and -1 for unused slots,
         *  which cannot be right for a feature named "is lane recommended", so this sends 1 only
         *  for the lanes the route takes and 0 for the rest. The arrow glyph carries the highlight
         *  as well, so a panel that ignores this flag still shows the right thing; if the strip
         *  fails to render at all in the car, [RECOMMENDED_PRESENT] restores the donor's values. */
        const val RECOMMENDED_YES = 1
        const val RECOMMENDED_NO = 0
        const val RECOMMENDED_ABSENT = -1

        /** The donor's value for "this slot holds a lane", kept for a fallback experiment. */
        const val RECOMMENDED_PRESENT = 1

        const val GLYPH_NONE = -1

        /** Lane codes that carry more than one direction and therefore have combined glyphs. */
        private val COMPLEX = setOf(2, 4, 6, 7, 9, 10, 11, 12, 16, 17, 18, 19, 20)

        /** Combined glyph for a multi-direction lane and the direction taken; -1 when the pair
         *  has no glyph (donor `complexGuide`). */
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

        /** Plain glyph for a lane nobody is being sent down (donor `getSimpleIdBack`). */
        internal fun plainGlyph(code: Int): Int = when {
            code in 0 until 13 -> code + 1
            code in 16 until 255 -> code
            else -> -1
        }

        /** Highlighted glyph for the direction the route takes (donor `getSimpleIdFront`). */
        internal fun activeGlyph(dir: Int): Int = when {
            dir in 0 until 13 -> dir + 26
            dir in 16 until 255 -> dir + 25
            else -> -1
        }

        /** Glyph for one lane: highlighted when the route uses it, plain otherwise. */
        internal fun glyphFor(code: Int, dir: Int?): Int {
            if (code !in COMPLEX) {
                return if (dir == null) plainGlyph(code) else activeGlyph(dir)
            }
            val combined = if (dir == null) -1 else complexGlyph(code, dir)
            return if (combined != -1) combined else plainGlyph(code)
        }

        /**
         * Pure: the full (feature, value) list for a lane set, in the donor's order — count
         * first, then the eight glyphs, the eight states and the eight validity flags. Lanes
         * beyond [NavLanes.MAX_LANES] are dropped; unused slots are switched off.
         */
        fun buildWrites(lanes: NavLanes, donorRecommendedFlag: Boolean = false): List<Pair<Int, Int>> {
            val used = lanes.lanes.take(NavLanes.MAX_LANES)
            val out = ArrayList<Pair<Int, Int>>(1 + NavLanes.MAX_LANES * 3)
            val glyphs = ArrayList<Pair<Int, Int>>(NavLanes.MAX_LANES)
            val lineTypes = ArrayList<Pair<Int, Int>>(NavLanes.MAX_LANES)
            val recommended = ArrayList<Pair<Int, Int>>(NavLanes.MAX_LANES)
            out += FID_LANE_COUNT to used.size
            for (slot in 0 until NavLanes.MAX_LANES) {
                val lane = used.getOrNull(slot)
                if (lane == null) {
                    glyphs += fidGlyph(slot) to GLYPH_NONE
                    lineTypes += fidLineType(slot) to LINE_TYPE_UNUSED
                    recommended += fidRecommended(slot) to RECOMMENDED_ABSENT
                } else {
                    val code = NavLaneCodes.codeFor(lane.directions)
                    val dir = lane.recommended?.let { NavLaneCodes.dirId(it) }
                    glyphs += fidGlyph(slot) to glyphFor(code, dir)
                    lineTypes += fidLineType(slot) to
                        if (dir == null) LINE_TYPE_PLAIN else LINE_TYPE_ON_ROUTE
                    recommended += fidRecommended(slot) to when {
                        donorRecommendedFlag -> RECOMMENDED_PRESENT
                        dir != null -> RECOMMENDED_YES
                        else -> RECOMMENDED_NO
                    }
                }
            }
            out += glyphs; out += lineTypes; out += recommended
            return out
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

    /** Blanks the lane strip; called when guidance ends or the feature is switched off. */
    fun clear() {
        if (lastSent == null || lastSent?.isEmpty == true) return
        scope.launch {
            mutex.withLock {
                send(NavLanes.NONE)
                lastSent = NavLanes.NONE
            }
        }
    }

    private suspend fun send(lanes: NavLanes) {
        var ok = 0
        var bad = 0
        for ((fid, value) in buildWrites(lanes)) {
            val accepted = runCatching { helper.write(DEV_INSTRUMENT, fid, value) }.getOrDefault(false)
            writes++
            if (accepted) ok++ else { bad++; failures++ }
        }
        Log.i(TAG, "lanes=${lanes.lanes.size} " +
            lanes.lanes.joinToString(",") { l ->
                NavLaneCodes.codeFor(l.directions).toString() + (l.recommended?.let { ">" + NavLaneCodes.dirId(it) } ?: "")
            } +
            " dist=${lanes.distanceMeters} accepted=$ok rejected=$bad")
    }
}
