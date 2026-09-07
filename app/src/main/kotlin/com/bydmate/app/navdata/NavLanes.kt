package com.bydmate.app.navdata

/**
 * Lane guidance for the junction ahead: one entry per physical lane, left to right.
 *
 * The car's instrument panel draws lanes from a pair of numbers per lane (see
 * [com.bydmate.app.hud.HudLaneWriter]): a *lane icon code* describing which directions the lane
 * allows, and a *recommended direction* saying which of them the route takes. This model keeps
 * the same two facts in readable form; the mapping to the car's numbers lives in [NavLaneCodes].
 */
data class NavLanes(
    val lanes: List<Lane>,
    /** Metres to the junction the lanes belong to; 0 when unknown. */
    val distanceMeters: Int = 0,
) {
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

    val isEmpty: Boolean get() = lanes.isEmpty()

    companion object {
        /** The instrument panel draws at most eight lanes. */
        const val MAX_LANES = 8

        val NONE = NavLanes(emptyList())
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

    fun update(value: NavLanes, nowMs: Long = System.currentTimeMillis()) {
        lanes = value
        updatedMs = if (value.isEmpty) 0L else nowMs
    }

    /** The current strip, or [NavLanes.NONE] when there is none or it has gone stale. */
    fun current(nowMs: Long = System.currentTimeMillis()): NavLanes =
        if (updatedMs == 0L || nowMs - updatedMs > TTL_MS) NavLanes.NONE else lanes

    fun clear() {
        lanes = NavLanes.NONE
        updatedMs = 0L
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
