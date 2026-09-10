package com.bydmate.app.navdata

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lane guidance from the Waze window: how many lanes the junction ahead has, which directions
 * each allows, and which ones the route uses.
 *
 * Waze renders the strip in a `laneGuidanceView`. Its lane children are drawables, so the
 * directions are not in the accessibility tree as text on every build; what the tree does give
 * reliably is the number of lanes and their left-to-right geometry, which is the half the car
 * needs to draw the strip at all. Where a lane exposes a content description or a view id that
 * names its arrow, that is used for the directions; where it does not, the lane is reported with
 * no directions and the writer keeps the slot but draws nothing in it.
 *
 * Recommended lanes are the ones Waze highlights. Waze marks these as selected/activated on most
 * builds; that flag is read here. Pixel classification of each arrow is deliberately not attempted
 * yet: the single-arrow classifier needs per-build tuning and the lane cells are much smaller, so
 * that is left until the strip itself is confirmed on the car.
 */
object WazeLaneReader {
    private const val TAG = "WazeLaneReader"

    /** View ids Waze has used for the lane strip, most specific first. */
    private val CONTAINER_IDS = listOf(
        "laneGuidanceView",
        "laneGuidance",
        "lanesView",
        "navBarLanes",
    )

    /** A lane child whose id or description names its arrow. */
    private val LANE_ID = Regex("lane", RegexOption.IGNORE_CASE)

    private const val MAX_NODES = 240

    /** What the last read saw, for the diagnostics dump. */
    data class Diagnostics(
        val container: String? = null,
        val laneCount: Int = 0,
        val recommended: Int = 0,
        val withDirections: Int = 0,
        val descriptions: List<String> = emptyList(),
    )

    @Volatile var diagnostics: Diagnostics = Diagnostics()
        private set

    /** Reads the strip from a Waze window root. Returns [NavLanes.NONE] when there is none. */
    fun read(root: AccessibilityNodeInfo): NavLanes {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        if (!NavPackages.isWazePackage(pkg)) return NavLanes.NONE
        val container = findContainer(root, pkg!!) ?: run {
            diagnostics = Diagnostics()
            return NavLanes.NONE
        }
        return try {
            val cells = laneCells(container)
            if (cells.isEmpty()) {
                diagnostics = Diagnostics(container = describe(container), laneCount = 0)
                return NavLanes.NONE
            }
            // A lane that allows several directions carries no clue in its text about which one
            // the route takes - Waze shows that by highlighting one arrow inside the cell. The
            // maneuver already in the hub resolves it: on a "turn right", a selected straight+right
            // lane is being recommended for the right. Falling back to the first direction only
            // when the maneuver says nothing.
            val maneuverDir = maneuverDirection()
            val lanes = cells.map { cell ->
                val dirs = directionsOf(cell)
                NavLanes.Lane(
                    directions = dirs,
                    recommended = if (!cell.selected) null
                    else maneuverDir?.takeIf { it in dirs } ?: dirs.firstOrNull(),
                )
            }
            diagnostics = Diagnostics(
                container = describe(container),
                laneCount = lanes.size,
                recommended = lanes.count { it.isRecommended },
                withDirections = lanes.count { it.directions.isNotEmpty() },
                descriptions = cells.mapNotNull { it.label }.take(NavLanes.MAX_LANES),
            )
            NavLanes(lanes)
        } finally {
            recycle(container, root)
        }
    }

    /** One lane child: its horizontal position decides the order, its label the directions. */
    internal data class Cell(val left: Int, val label: String?, val selected: Boolean)

    /** The direction of the maneuver currently in the hub, when it is one a lane can show. */
    internal fun maneuverDirection(
        gaode: Int = runCatching { NavGuidanceHub.snapshot().maneuverGaode }.getOrDefault(0),
    ): NavLanes.Dir? = when (gaode) {
        NavManeuverCodes.GAODE_LEFT, NavManeuverCodes.GAODE_SLIGHT_LEFT, NavManeuverCodes.GAODE_HARD_LEFT ->
            NavLanes.Dir.LEFT
        NavManeuverCodes.GAODE_RIGHT, NavManeuverCodes.GAODE_SLIGHT_RIGHT, NavManeuverCodes.GAODE_HARD_RIGHT ->
            NavLanes.Dir.RIGHT
        NavManeuverCodes.GAODE_STRAIGHT, 12 -> NavLanes.Dir.STRAIGHT
        NavManeuverCodes.GAODE_UTURN, NavManeuverCodes.GAODE_UTURN_RIGHT -> NavLanes.Dir.UTURN_LEFT
        else -> null
    }

    private fun findContainer(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in CONTAINER_IDS) {
            val found = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") }
                .getOrNull().orEmpty()
            val first = found.firstOrNull { runCatching { it.isVisibleToUser }.getOrDefault(true) }
            found.forEach { if (it !== first) recycle(it, root) }
            if (first != null) return first
        }
        return null
    }

    /** Immediate children that look like lanes, ordered left to right by their bounds. */
    private fun laneCells(container: AccessibilityNodeInfo): List<Cell> {
        val cells = mutableListOf<Cell>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited++ >= MAX_NODES || depth > 3) return
            val count = runCatching { node.childCount }.getOrDefault(0)
            if (count == 0) {
                val rect = Rect().also { r -> runCatching { node.getBoundsInScreen(r) } }
                if (rect.width() > 0 && rect.height() > 0) {
                    cells += Cell(
                        left = rect.left,
                        label = label(node),
                        selected = runCatching { node.isSelected || node.isChecked }.getOrDefault(false),
                    )
                }
                return
            }
            for (i in 0 until count) {
                if (visited >= MAX_NODES) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    recycle(child, container)
                }
            }
        }
        visit(container, 0)
        return cells.sortedBy { it.left }.take(NavLanes.MAX_LANES)
    }

    private fun label(node: AccessibilityNodeInfo): String? {
        val desc = runCatching { node.contentDescription?.toString() }.getOrNull()
        if (!desc.isNullOrBlank()) return desc
        val text = runCatching { node.text?.toString() }.getOrNull()
        if (!text.isNullOrBlank()) return text
        val id = runCatching { node.viewIdResourceName }.getOrNull()?.substringAfterLast('/')
        return id?.takeIf { LANE_ID.containsMatchIn(it) }
    }

    private fun describe(node: AccessibilityNodeInfo): String? =
        runCatching { node.viewIdResourceName }.getOrNull()?.substringAfterLast('/')

    /**
     * Directions named by a lane's label. Waze localizes its descriptions, so this matches the
     * language-pack phrases the parser already owns rather than hard-coded English, and falls back
     * to the arrow characters some builds use.
     */
    internal fun directionsOf(cell: Cell): Set<NavLanes.Dir> = directionsOf(cell.label)

    /** Direction keys in the language packs' `lanes` section. */
    private const val KEY_LEFT = "left"
    private const val KEY_STRAIGHT = "straight"
    private const val KEY_RIGHT = "right"
    private const val KEY_UTURN = "uturn"

    /** Arrow characters some builds use instead of words, per direction key. */
    private val ARROWS = mapOf(
        KEY_LEFT to "←⬅↰",
        KEY_STRAIGHT to "↑⬆",
        KEY_RIGHT to "→➡↱",
        KEY_UTURN to "⤺⤻↶",
    )

    internal fun directionsOf(label: String?): Set<NavLanes.Dir> {
        if (label.isNullOrBlank()) return emptySet()
        val normalized = label.lowercase()
        val keys = mutableSetOf<String>()
        runCatching { NavPhraseTables.current.lanePatterns }.getOrNull()?.forEach { (pattern, key) ->
            if (pattern.containsMatchIn(normalized)) keys += key
        }
        ARROWS.forEach { (key, chars) ->
            if (normalized.any { it in chars }) keys += key
        }
        val out = mutableSetOf<NavLanes.Dir>()
        if (KEY_LEFT in keys) out += NavLanes.Dir.LEFT
        if (KEY_STRAIGHT in keys) out += NavLanes.Dir.STRAIGHT
        if (KEY_RIGHT in keys) out += NavLanes.Dir.RIGHT
        if (KEY_UTURN in keys) out += NavLanes.Dir.UTURN_LEFT
        return out
    }

    private fun recycle(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo) {
        if (node === root) return
        @Suppress("DEPRECATION") runCatching { node.recycle() }
    }

    /** One line for the diagnostics dump. */
    fun diagnosticsLine(): String = diagnostics.let {
        "lanes container=${it.container ?: "-"} count=${it.laneCount} recommended=${it.recommended} " +
            "withDirections=${it.withDirections}" +
            if (it.descriptions.isEmpty()) "" else " labels=${it.descriptions.joinToString("|")}"
    }

    internal fun logRead(lanes: NavLanes) {
        Log.i(TAG, diagnosticsLine() + " -> ${lanes.lanes.size} lanes")
        // The raw cell labels are the thing that decides whether lanes work on a given Waze
        // build, and they never reach logcat in release; the trip log is where they belong.
        com.bydmate.app.diagnostics.TripDebugLog.event(
            "LANES",
            diagnosticsLine() + " -> " + lanes.lanes.joinToString(" | ") { lane ->
                lane.directions.joinToString("+").ifEmpty { "?" } +
                    (lane.recommended?.let { ">$it" } ?: "")
            },
        )
    }
}
