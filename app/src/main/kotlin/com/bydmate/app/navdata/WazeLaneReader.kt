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
            val lanes = cells.map { cell ->
                NavLanes.Lane(
                    directions = directionsOf(cell),
                    recommended = if (cell.selected) directionsOf(cell).firstOrNull() else null,
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

    internal fun directionsOf(label: String?): Set<NavLanes.Dir> {
        if (label.isNullOrBlank()) return emptySet()
        val out = mutableSetOf<NavLanes.Dir>()
        val normalized = label.lowercase()
        val tables = runCatching { NavPhraseTables.current }.getOrNull()
        fun matches(code: Int): Boolean =
            tables?.phrasePatterns?.any { (pattern, c) ->
                c == code && pattern.containsMatchIn(normalized)
            } ?: false
        if (matches(NavManeuverCodes.GAODE_LEFT) || normalized.contains('←') || normalized.contains('⬅')) {
            out += NavLanes.Dir.LEFT
        }
        if (matches(NavManeuverCodes.GAODE_RIGHT) || normalized.contains('→') || normalized.contains('➡')) {
            out += NavLanes.Dir.RIGHT
        }
        if (matches(NavManeuverCodes.GAODE_STRAIGHT) || normalized.contains('↑') || normalized.contains('⬆')) {
            out += NavLanes.Dir.STRAIGHT
        }
        if (matches(NavManeuverCodes.GAODE_UTURN) || normalized.contains('↰') || normalized.contains('⤺')) {
            out += NavLanes.Dir.UTURN_LEFT
        }
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
    }
}
