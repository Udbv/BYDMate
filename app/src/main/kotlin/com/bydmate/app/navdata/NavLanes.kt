package com.bydmate.app.navdata

/**
 * Lane guidance for the junction ahead: one entry per physical lane, left to right.
 *
 * The panel takes the two arrays `sendLaneGuidanceInfo` takes (openbyd `CarControlImpl` :1400):
 * one *lane code* per lane saying which directions it allows, and one *front* per lane saying
 * which of them the route takes (255 = this lane is not on the route). [lanes] is the readable
 * direction-set view the accessibility reader still produces; [codes]/[fronts] are what reach the
 * car, and they alone (with [distanceMeters]) decide whether anything needs sending again. The
 * mapping between the two views lives in [NavLaneCodes].
 */
class NavLanes private constructor(
    /** Donor lane codes (0..25, 255 = no lane), one per physical lane, left to right. */
    val codes: IntArray,
    /** Donor front per lane: the arrow the route takes, 255 when the lane is off route. */
    val fronts: IntArray,
    /** Metres to the junction the lanes belong to; 0 when unknown, -1 on a clear. */
    val distanceMeters: Int,
    /** Direction-set view of the same lanes; empty when the codes came from the pixel path. */
    val lanes: List<Lane>,
) {
    /** The reader's view: directions per lane, translated to codes/fronts by [NavLaneCodes]. */
    constructor(lanes: List<Lane>, distanceMeters: Int = 0) : this(
        codes = IntArray(lanes.size) { NavLaneCodes.codeFor(lanes[it].directions) },
        fronts = IntArray(lanes.size) { i ->
            lanes[i].recommended?.let { NavLaneCodes.dirId(it) } ?: NavLaneCodes.EMPTY
        },
        distanceMeters = distanceMeters,
        lanes = lanes,
    )

    data class Lane(
        /** Every direction this lane allows. Empty means the lane was read but not understood. */
        val directions: Set<Dir>,
        /** The direction the route takes here, or null when this lane is not for the route. */
        val recommended: Dir? = null,
    ) {
        val isRecommended: Boolean get() = recommended != null
    }

    /** Directions the car's lane glyphs distinguish. */
    enum class Dir { LEFT, STRAIGHT, RIGHT, UTURN_LEFT, UTURN_RIGHT }

    /** How many lanes the junction has. Never clamped: the donor sends the real count. */
    val size: Int get() = codes.size

    val isEmpty: Boolean get() = codes.isEmpty()

    /** The front of lane [i] the way the donor reads it: past the end of the array it is 255. */
    fun frontAt(i: Int): Int = if (i < fronts.size) fronts[i] else NavLaneCodes.EMPTY

    /** `code>front` per lane, the front omitted when the lane is not on the route. This is the
     *  shape the emulator scenarios grep for (`dilink5-sim/scripts/bydmate-scenarios.sh`). */
    fun codesLine(): String = codes.indices.joinToString(",") { i ->
        val front = frontAt(i)
        if (front == NavLaneCodes.EMPTY) codes[i].toString() else "${codes[i]}>$front"
    }

    /** Identity is exactly the donor's change detection (`CanBydFidStrategy` :202): the two
     *  arrays and the distance. The derived [lanes] view is left out. */
    override fun equals(other: Any?): Boolean =
        other is NavLanes && codes.contentEquals(other.codes) &&
            fronts.contentEquals(other.fronts) && distanceMeters == other.distanceMeters

    override fun hashCode(): Int =
        (codes.contentHashCode() * 31 + fronts.contentHashCode()) * 31 + distanceMeters

    override fun toString(): String = "NavLanes(${codesLine()} dist=$distanceMeters)"

    companion object {
        /** The instrument panel draws at most eight lanes. */
        const val MAX_LANES = 8

        /** Lane slots on the setting device (`SET_LANE_STATES`). */
        const val SETTING_LANES = 12

        val NONE = NavLanes(emptyList())

        /** The panel's own arrays, as the pixel path produces them. */
        fun ofCodes(codes: IntArray, fronts: IntArray, distanceMeters: Int = 0): NavLanes =
            NavLanes(codes, fronts, distanceMeters, emptyList())

        /** The donor's clear: no lanes, distance -1 (`sendLaneGuidanceInfo(int[0], int[0], -1)`). */
        val CLEARED = ofCodes(IntArray(0), IntArray(0), -1)
    }
}

/**
 * Newest lane strip read from the navigator, with its own freshness clock.
 *
 * Lanes are kept beside [NavGuidanceHub] rather than inside its snapshot because they have a
 * different lifetime: the strip appears for a few hundred metres before a junction and vanishes
 * again, while the hub's fields persist for the whole route. A stale strip is worse than none,
 * so anything older than [TTL_MS] reads as empty.
 */
object NavLaneState {
    /** Waze shows the strip only near a junction; past this the car should stop drawing it. */
    const val TTL_MS = 12_000L

    @Volatile private var lanes: NavLanes = NavLanes.NONE
    @Volatile private var updatedMs: Long = 0L
    @Volatile private var pixelMs: Long = 0L

    /**
     * Screen bounds of Waze's `laneGuidanceView`, and the display it was found on.
     *
     * This is all the accessibility tree gives for the strip (openbyd reads the same node for the
     * same reason). It is the crop the pixel path segments; null means Waze is not showing lanes.
     * Plain ints rather than a `Rect` so the lane pipeline stays testable off-device.
     */
    data class ContainerBounds(
        val displayId: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    @Volatile var containerBounds: ContainerBounds? = null

    /**
     * Lanes from the accessibility labels - the secondary source.
     *
     * The emulator stub is the only place this path ever produced anything; on the car the lane
     * children carry no text at all. It runs on every a11y read (five times a second), so it must
     * not overwrite a pixel result that only refreshes every two seconds: while a pixel strip is
     * still fresh, the label path is ignored entirely.
     */
    fun update(value: NavLanes, nowMs: Long = System.currentTimeMillis()) {
        if (pixelMs != 0L && nowMs - pixelMs <= TTL_MS) return
        lanes = value
        updatedMs = if (value.isEmpty) 0L else nowMs
    }

    /** Lanes from the pixel path (openbyd's own source). Wins over [update] for [TTL_MS]. */
    fun updateFromPixels(value: NavLanes, nowMs: Long = System.currentTimeMillis()) {
        lanes = value
        updatedMs = if (value.isEmpty) 0L else nowMs
        pixelMs = if (value.isEmpty) 0L else nowMs
    }

    /** The current strip, or [NavLanes.NONE] when there is none or it has gone stale. */
    fun current(nowMs: Long = System.currentTimeMillis()): NavLanes =
        if (updatedMs == 0L || nowMs - updatedMs > TTL_MS) NavLanes.NONE else lanes

    fun clear() {
        lanes = NavLanes.NONE
        updatedMs = 0L
        pixelMs = 0L
        containerBounds = null
    }
}

/**
 * Lane icon codes as the BYD instrument panel numbers them, and the mapping from a set of
 * directions onto one of them. Values are the donor's `LANE_ICON_*` table (openbyd 2.4.3,
 * `HudController`), which matches what the factory navigation writes.
 */
object NavLaneCodes {
    const val STRAIGHT = 0
    const val LEFT = 1
    const val STRAIGHT_LEFT = 2
    const val RIGHT = 3
    const val STRAIGHT_RIGHT = 4
    const val UTURN_LEFT = 5
    const val LEFT_RIGHT = 6            // "T junction": both turns, no straight
    const val UTURN_RIGHT = 8
    const val UTURN_LEFT_STRAIGHT = 9
    const val UTURN_LEFT_STRAIGHT_RIGHT = 10
    const val UTURN_LEFT_LEFT = 11
    const val UTURN_RIGHT_RIGHT = 12
    const val STRAIGHT_LEFT_RIGHT = 16
    const val UTURN_LEFT_RIGHT = 17
    const val LEFT_RIGHT_MERGE = 18
    const val UTURN_RIGHT_LEFT = 20

    /** No lane / unused slot, as the panel expects it. */
    const val EMPTY = 255

    /** Direction ids the panel uses for the recommended direction of a lane. */
    const val DIR_STRAIGHT = 0
    const val DIR_LEFT = 1
    const val DIR_RIGHT = 3
    const val DIR_UTURN_LEFT = 5
    const val DIR_UTURN_RIGHT = 8

    fun dirId(dir: NavLanes.Dir): Int = when (dir) {
        NavLanes.Dir.LEFT -> DIR_LEFT
        NavLanes.Dir.STRAIGHT -> DIR_STRAIGHT
        NavLanes.Dir.RIGHT -> DIR_RIGHT
        NavLanes.Dir.UTURN_LEFT -> DIR_UTURN_LEFT
        NavLanes.Dir.UTURN_RIGHT -> DIR_UTURN_RIGHT
    }

    /**
     * Direction set -> lane icon code. Only the combinations the panel has a glyph for are
     * listed; anything else falls back to the single most important direction, because a lane
     * drawn with one of its real directions is better than a blank slot.
     */
    fun codeFor(directions: Set<NavLanes.Dir>): Int {
        val l = NavLanes.Dir.LEFT in directions
        val s = NavLanes.Dir.STRAIGHT in directions
        val r = NavLanes.Dir.RIGHT in directions
        val ul = NavLanes.Dir.UTURN_LEFT in directions
        val ur = NavLanes.Dir.UTURN_RIGHT in directions
        return when {
            directions.isEmpty() -> EMPTY
            ul && s && r -> UTURN_LEFT_STRAIGHT_RIGHT
            ul && s -> UTURN_LEFT_STRAIGHT
            ul && l -> UTURN_LEFT_LEFT
            ul && r -> UTURN_LEFT_RIGHT
            ur && r -> UTURN_RIGHT_RIGHT
            ur && l -> UTURN_RIGHT_LEFT
            ul -> UTURN_LEFT
            ur -> UTURN_RIGHT
            l && s && r -> STRAIGHT_LEFT_RIGHT
            l && s -> STRAIGHT_LEFT
            s && r -> STRAIGHT_RIGHT
            l && r -> LEFT_RIGHT
            l -> LEFT
            r -> RIGHT
            s -> STRAIGHT
            else -> EMPTY
        }
    }
}
