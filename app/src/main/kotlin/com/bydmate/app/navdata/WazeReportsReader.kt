package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Waze's "reports on route" list, read as text.
 *
 * Everything else Waze knows about a camera is a drawable or a notification this head unit never
 * delivers; this list is the one place the camera is a *word*. The 2026-09-14 drive named its
 * parts: `navListReportsList` is the ListView, and each row carries `navListReportItemType`
 * ("Глухий затор", "Камера", "Поліція") beside `navListReportItemDistance` ("1.6 км попереду",
 * "130 м попереду"), with `navListNoReportsText` in place of the rows when there is nothing ahead.
 *
 * Rows keep the list's own order, which is Waze's own "nearest first"; the reader does not
 * re-sort, so a Waze that changes its mind about the order changes ours with it.
 *
 * Every word this matches lives in the language packs (`assets/navi/phrases/<lang>.json`,
 * `"reports"`), never in Kotlin - the car runs Waze in Ukrainian and the driver may switch it.
 */
object WazeReportsReader {

    /** What a row is, as far as the panel cares. */
    enum class Kind { CAMERA, POLICE, OTHER }

    /**
     * One row: its kind, the type text verbatim, and the distance in metres (0 when the row does
     * not say how far ahead it is).
     */
    data class NavReport(
        val kind: Kind,
        val typeText: String,
        val distanceMeters: Int,
    )

    const val ID_LIST = "navListReportsList"
    const val ID_TYPE = "navListReportItemType"
    const val ID_DISTANCE = "navListReportItemDistance"
    const val ID_EMPTY = "navListNoReportsText"

    /** Rows are short and few; this bounds a tree that is being rebuilt while we walk it. */
    private const val MAX_NODES = 240
    private const val MAX_ROWS = 24

    /** Report keys in the language packs' `reports` section. */
    private const val KEY_CAMERA = "camera"
    private const val KEY_POLICE = "police"
    private const val KEY_AHEAD = "ahead"

    /** Reads the list from a Waze window root. Empty when Waze is not showing one. */
    fun read(root: AccessibilityNodeInfo): List<NavReport> {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull() ?: return emptyList()
        if (!NavPackages.isWazePackage(pkg)) return emptyList()
        val lists = runCatching {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$ID_LIST")
        }.getOrNull().orEmpty()
        if (lists.isEmpty()) return emptyList()
        return try {
            lists.firstNotNullOfOrNull { list ->
                rowsOf(list, pkg).takeIf { it.isNotEmpty() }
            }.orEmpty()
        } finally {
            lists.forEach { recycle(it, root) }
        }
    }

    /**
     * The list's rows in order.
     *
     * The two texts of a row are paired in the order they are met, whichever comes first: the
     * 2026-09-14 drive recorded the distance before the type inside a row, and a flat list (no row
     * wrapper at all) reads the same way.
     */
    private fun rowsOf(list: AccessibilityNodeInfo, pkg: String): List<NavReport> {
        val texts = mutableListOf<Pair<String, String>>()   // (id, text), in tree order
        collect(list, pkg, texts, intArrayOf(MAX_NODES))
        val out = mutableListOf<NavReport>()
        var type: String? = null
        var distance: String? = null
        for ((id, text) in texts) {
            when (id) {
                ID_TYPE -> {
                    if (distance != null) {
                        out += report(text, distance)       // the drive's own order: distance first
                        distance = null
                    } else {
                        if (type != null) out += report(type, null)
                        type = text
                    }
                }
                ID_DISTANCE -> {
                    if (type != null) {
                        out += report(type, text)
                        type = null
                    } else {
                        // A second distance with no type between them belongs to the newer row.
                        distance = text
                    }
                }
            }
            if (out.size >= MAX_ROWS) return out
        }
        if (type != null) out += report(type, null)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        pkg: String,
        out: MutableList<Pair<String, String>>,
        budget: IntArray,
    ) {
        if (budget[0] <= 0) return
        budget[0]--
        val id = runCatching { node.viewIdResourceName }.getOrNull()?.substringAfterLast('/')
        if (id == ID_TYPE || id == ID_DISTANCE) {
            val text = runCatching { node.text?.toString() }.getOrNull()
                ?: runCatching { node.contentDescription?.toString() }.getOrNull()
            if (!text.isNullOrBlank()) out += id to text.trim()
        }
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            if (budget[0] <= 0) return
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collect(child, pkg, out, budget)
            } finally {
                recycle(child, node)
            }
        }
    }

    internal fun report(typeText: String, distanceText: String?): NavReport =
        NavReport(kindOf(typeText), typeText, distanceMetersOf(distanceText))

    /**
     * Which of the panel's signs this row is.
     *
     * Substring matching, not whole words: the packs carry stems ("поліц") because Ukrainian
     * inflects them, and Waze writes "Камера" inside longer phrases.
     */
    fun kindOf(typeText: String): Kind {
        val text = typeText.lowercase()
        if (matchesAny(KEY_CAMERA, text)) return Kind.CAMERA
        if (matchesAny(KEY_POLICE, text)) return Kind.POLICE
        return Kind.OTHER
    }

    /**
     * Metres from a row's distance text ("1.6 км попереду", "130 м попереду").
     *
     * The units come from the packs through [WazeGuidanceParser.parseDistanceText]; the "ahead"
     * word is what makes the number a distance to something in front of the car, so a row that
     * does not say it reports 0 rather than a number the panel would count down to.
     */
    fun distanceMetersOf(distanceText: String?): Int {
        val text = distanceText?.trim().orEmpty()
        if (text.isEmpty()) return 0
        if (!matchesAny(KEY_AHEAD, text.lowercase())) return 0
        return WazeGuidanceParser.parseDistanceText(text)
    }

    private fun matchesAny(key: String, lowercaseText: String): Boolean =
        NavPhraseTables.current.reportWords(key).any { it in lowercaseText }

    private fun recycle(node: AccessibilityNodeInfo, parent: AccessibilityNodeInfo) {
        if (node === parent) return
        @Suppress("DEPRECATION") runCatching { node.recycle() }
    }
}
