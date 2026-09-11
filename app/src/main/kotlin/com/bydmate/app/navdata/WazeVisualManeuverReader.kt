package com.bydmate.app.navdata

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.waze.ArrowSignature
import com.bydmate.app.navdata.waze.WazeArrowTable
import com.bydmate.app.navdata.waze.WazeLaneSegmenter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the Waze maneuver arrow as a picture, because on this build it is only ever a picture.
 *
 * Waze draws the arrow as a drawable with no accessibility semantics whatsoever, so the route tree
 * yields a distance and a street and no maneuver at all. openbyd solved this by recognising the
 * artwork itself: crop the arrow's rectangle out of a screenshot, reduce it to a 15x15 bit grid and
 * find the nearest of the 45 Waze drawables it carries signatures for. That is a verbatim port here
 * ([ArrowSignature], [WazeArrowTable]); the drawable's name is then the instrument-panel glyph.
 *
 * Only the arrow's own rectangle is ever sampled. The screenshot, the crop and the pixels are
 * released immediately; what survives is a glyph number, a drawable name and - when nothing
 * matched - the 225 shape bits, which carry no text, no colour and no map.
 *
 * Deviation from the donor, deliberate: the pixels come from
 * `AccessibilityService.takeScreenshot`, not from a MediaProjection VirtualDisplay. It needs no
 * consent activity, it already works on the Tang L, and the crop is in the same screen coordinates,
 * so the pixels handed to the classifier are the same ones.
 */
object WazeVisualManeuverReader {

    /**
     * What one classified arrow produced.
     *
     * [panelIcon] is 0 when nothing matched. The donor writes 11 (straight) in that case, but here
     * a zero lets the phrase-pack text path still fill the maneuver - on the car that path yields 0
     * for Waze anyway, so the panel sees the donor's 11 either way, and in the emulator or on a
     * build where the text IS readable we do not overwrite a real maneuver with a guess.
     */
    data class Classification(
        val panelIcon: Int,
        val matchedName: String?,
        val hamming: Int?,
        val gridString: String,
    )

    data class Diagnostics(
        val attemptedAtMs: Long? = null,
        val displayId: Int? = null,
        val targetSource: String? = null,
        val targetWidth: Int? = null,
        val targetHeight: Int? = null,
        val panelIcon: Int = 0,
        val matchedName: String? = null,
        val hamming: Int? = null,
        val failure: String? = null,
    )

    internal data class Target(val displayId: Int, val bounds: Rect, val source: String)

    /**
     * Attempt counters plus the last *completed* attempt.
     *
     * [diagnostics] is overwritten at request time with `failure="pending"`, so an export taken
     * while a screenshot is in flight loses the previous verdict. Distinguishing "the visual path
     * is never requested" from "it is requested and the screenshot fails" from "it succeeds but
     * the arrow is unrecognized" is exactly what a single instrumented route has to answer.
     */
    data class Counters(
        val requested: Int = 0,
        val started: Int = 0,
        val completed: Int = 0,
        val lastCompleted: Diagnostics? = null,
    )

    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastAttemptElapsedMs: Long = -MIN_ATTEMPT_INTERVAL_MS
    @Volatile private var latest = Diagnostics()
    @Volatile private var latestCounters = Counters()

    fun diagnostics(): Diagnostics = latest

    fun counters(): Counters = latestCounters

    /** Test hook; the reader never clears its own counters during a route. */
    internal fun resetCounters() {
        latestCounters = Counters()
        latest = Diagnostics()
        lastAttemptElapsedMs = -MIN_ATTEMPT_INTERVAL_MS
        inFlight.set(false)
    }

    @Synchronized
    private fun countRequest() {
        latestCounters = latestCounters.copy(requested = latestCounters.requested + 1)
    }

    @Synchronized
    private fun countStart() {
        latestCounters = latestCounters.copy(started = latestCounters.started + 1)
    }

    @Synchronized
    private fun countCompletion(result: Diagnostics) {
        latestCounters = latestCounters.copy(
            completed = latestCounters.completed + 1,
            lastCompleted = result,
        )
        android.util.Log.i(
            "WazeVisualManeuver",
            "attempt #${latestCounters.completed} display=${result.displayId} target=${result.targetSource} " +
                "${result.targetWidth}x${result.targetHeight} icon=${result.panelIcon} " +
                "name=${result.matchedName} hamming=${result.hamming} failure=${result.failure}",
        )
    }

    /**
     * Schedules at most one bounded screenshot every [MIN_ATTEMPT_INTERVAL_MS].
     *
     * [exitNumber] is the roundabout exit Waze printed inside the arrow at the time of the read; it
     * is applied only when the matched drawable IS a roundabout.
     *
     * Returns false when the root is not Waze, the platform is too old, or an attempt is already
     * in flight or too recent.
     */
    fun request(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
        exitNumber: Int?,
        callback: (Classification) -> Unit,
    ): Boolean {
        countRequest()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val target = findTarget(root) ?: run {
            val result = Diagnostics(
                attemptedAtMs = System.currentTimeMillis(),
                failure = "not_a_waze_window",
            )
            latest = result
            countCompletion(result)
            return false
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (nowElapsed - lastAttemptElapsedMs < MIN_ATTEMPT_INTERVAL_MS) return false
            if (!inFlight.compareAndSet(false, true)) return false
            lastAttemptElapsedMs = nowElapsed
        }
        latest = Diagnostics(
            attemptedAtMs = System.currentTimeMillis(),
            displayId = target.displayId,
            targetSource = target.source,
            targetWidth = target.bounds.width(),
            targetHeight = target.bounds.height(),
            failure = "pending",
        )
        countStart()
        // Read off the service now: the callback runs on the main executor, but the display
        // metrics are what the donor reads at segmentation time and they never change mid-route.
        val density = runCatching { service.resources.displayMetrics.density }.getOrDefault(1f)
        return runCatching {
            service.takeScreenshot(
                target.displayId,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        var wrapped: Bitmap? = null
                        var software: Bitmap? = null
                        try {
                            wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            // Lanes first, exactly as the donor: when Waze is showing a lane
                            // strip, one of its cells IS the maneuver arrow, so the separate
                            // arrow crop is only used when there is no strip to segment.
                            val classification = software?.let { bitmap ->
                                classifyLanes(bitmap, target.displayId, density, exitNumber)
                                    ?: classify(bitmap, target.bounds, exitNumber)
                            }
                            val result = Diagnostics(
                                attemptedAtMs = System.currentTimeMillis(),
                                displayId = target.displayId,
                                targetSource = target.source,
                                targetWidth = target.bounds.width(),
                                targetHeight = target.bounds.height(),
                                panelIcon = classification?.panelIcon ?: 0,
                                matchedName = classification?.matchedName,
                                hamming = classification?.hamming,
                                failure = when {
                                    classification == null -> "crop_failed"
                                    classification.matchedName == null -> "signature_not_matched"
                                    else -> null
                                },
                            )
                            latest = result
                            countCompletion(result)
                            if (classification != null) {
                                logClassification(target, classification, exitNumber)
                                callback(classification)
                            }
                        } catch (_: Throwable) {
                            val result = latest.copy(failure = "bitmap_processing_failed")
                            latest = result
                            countCompletion(result)
                        } finally {
                            software?.recycle()
                            wrapped?.recycle()
                            buffer.close()
                            inFlight.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val result = latest.copy(failure = "screenshot_error_$errorCode")
                        latest = result
                        countCompletion(result)
                        inFlight.set(false)
                    }
                },
            )
            true
        }.getOrElse {
            val result = latest.copy(failure = "screenshot_request_failed")
            latest = result
            countCompletion(result)
            inFlight.set(false)
            false
        }
    }

    /**
     * One line per classification in the trip log: what was cropped, what it matched and how far
     * off it was. An unmatched arrow also carries the 225 shape bits, which is the only way to grow
     * the signature table from a real drive - they are a silhouette, never pixels or text.
     */
    private fun logClassification(target: Target, result: Classification, exitNumber: Int?) {
        val head = "${target.source} ${target.bounds.width()}x${target.bounds.height()} " +
            "icon=${result.panelIcon} exit=$exitNumber"
        com.bydmate.app.diagnostics.TripDebugLog.event(
            "VISUAL",
            if (result.matchedName == null) {
                "$head unmatched grid=${result.gridString}"
            } else {
                "$head name=${result.matchedName} hamming=${result.hamming}"
            },
        )
    }

    /**
     * The arrow's rectangle: the first `navBarDirection` node with a real size, else null.
     *
     * openbyd falls back to a fixed phone-layout rectangle here; on the Tang L that rectangle is
     * map, and the 2026-09-11 drive log shows what a map does to a shape matcher (see below), so
     * this port deliberately classifies nothing without the node.
     */
    internal fun findTarget(root: AccessibilityNodeInfo): Target? {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
            ?.takeIf(NavPackages::isWazePackage)
            ?: return null
        val displayId = runCatching {
            val window = root.window ?: return@runCatching 0
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.displayId else 0
            } finally {
                @Suppress("DEPRECATION") runCatching { window.recycle() }
            }
        }.getOrDefault(0)

        val nodes = runCatching {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/navBarDirection")
        }.getOrNull().orEmpty()
        var bounds: Rect? = null
        for (node in nodes) {
            try {
                if (bounds != null) continue
                val rect = Rect()
                runCatching { node.getBoundsInScreen(rect) }
                if (rect.width() > 0 && rect.height() > 0) bounds = rect
            } finally {
                recycle(node)
            }
        }
        // Field evidence (Tang L, 2026-09-11 drive): when Waze reports no navBarDirection bounds the
        // donor's default rectangle lands on the MAP on this head unit, and the map matched
        // "directions_roundabout" 80 times in one drive (hamming 10-12) while every real arrow
        // matched at 0-8. So a missing node means "no classification", not "classify the default".
        return bounds?.let { Target(displayId, it, "navBarDirection") }
    }

    /**
     * The lane path: segment Waze's lane strip out of the same screenshot and publish the codes.
     *
     * Returns null exactly where the donor's `processSegmentedLanes` returns false - no strip on
     * screen, bounds that do not fit the screenshot, no cells, or a peak column score too weak to
     * be lane artwork - and the caller then classifies the plain arrow crop as before.
     *
     * When it does produce lanes, the first on-route lane's crop is the maneuver arrow (the donor
     * hands that same crop to its arrow classifier). If no lane is on the route the lanes are
     * still published and the arrow comes back unknown, which leaves the panel glyph to the text
     * path rather than guessing.
     */
    private fun classifyLanes(
        bitmap: Bitmap,
        displayId: Int,
        density: Float,
        exitNumber: Int?,
    ): Classification? {
        val container = NavLaneState.containerBounds ?: return null
        if (container.displayId != displayId) return null
        if (!isValidBounds(container, bitmap.width, bitmap.height)) return null
        val w = container.width
        val h = container.height
        val pixels = IntArray(w * h)
        runCatching {
            bitmap.getPixels(pixels, 0, w, container.left, container.top, w, h)
        }.getOrElse { return null }

        val rect = WazeLaneSegmenter.Box(
            container.left,
            container.top,
            container.right,
            container.bottom,
        )
        val segments = WazeLaneSegmenter.segment(pixels, w, h, rect, density)
        val result = WazeLaneSegmenter.process(pixels, w, h, rect, segments) ?: return null

        val distance = runCatching { NavGuidanceHub.snapshot().distanceMeters }.getOrDefault(0)
        NavLaneState.updateFromPixels(
            NavLanes.ofCodes(result.codes, result.fronts, distance),
        )
        val arrow = result.mainArrow
        val classification = if (arrow == null) {
            Classification(0, null, null, "")
        } else {
            classifyPixels(arrow.width, arrow.height, arrow.pixels, exitNumber)
        }
        logLanes(result, distance, classification)
        return classification
    }

    /** openbyd's `isValidBounds` (:488-494): the crop has to lie wholly inside the screenshot. */
    internal fun isValidBounds(
        bounds: NavLaneState.ContainerBounds,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Boolean = bounds.width > 0 && bounds.height > 0 &&
        bounds.left >= 0 && bounds.top >= 0 &&
        bounds.left + bounds.width <= bitmapWidth &&
        bounds.top + bounds.height <= bitmapHeight

    /**
     * One line per segmentation in the trip log: how many cells were found, what each became, and
     * which one was taken as the maneuver arrow. Scores and codes only - never pixels.
     */
    private fun logLanes(
        result: WazeLaneSegmenter.Result,
        distance: Int,
        classification: Classification,
    ) {
        val cells = result.codes.indices.joinToString(" | ") { i ->
            "${result.codes[i]}>${result.fronts[i]}@${result.scores[i].toInt()}" +
                if (i == result.mainArrowIndex) "*" else ""
        }
        com.bydmate.app.diagnostics.TripDebugLog.event(
            "LANES",
            "pixels cells=${result.codes.size} dist=$distance arrow=" +
                (if (result.mainArrowIndex >= 0) "#${result.mainArrowIndex}" else "none") +
                " icon=${classification.panelIcon} name=${classification.matchedName ?: "-"}" +
                (if (cells.isEmpty()) "" else " $cells"),
        )
    }

    /** Crops [crop] out of [bitmap] and classifies the raw pixels - no resampling. */
    private fun classify(bitmap: Bitmap, crop: Rect, exitNumber: Int?): Classification? {
        val bounded = Rect(
            crop.left.coerceIn(0, bitmap.width),
            crop.top.coerceIn(0, bitmap.height),
            crop.right.coerceIn(0, bitmap.width),
            crop.bottom.coerceIn(0, bitmap.height),
        )
        val width = bounded.width()
        val height = bounded.height()
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        runCatching {
            bitmap.getPixels(pixels, 0, width, bounded.left, bounded.top, width, height)
        }.getOrElse { return null }
        return classifyPixels(width, height, pixels, exitNumber)
    }

    /**
     * Pure classification core, used by tests as well as the screenshot path.
     *
     * The roundabout override is the donor's: a matched roundabout drawable ignores its own glyph
     * and becomes `exit + 24`, or `exit + 34` for the left-hand-traffic artwork, unclamped - the
     * panel simply has no picture past the tenth exit and draws nothing, which beats drawing the
     * wrong exit.
     */
    internal fun classifyPixels(
        width: Int,
        height: Int,
        pixels: IntArray,
        exitNumber: Int?,
    ): Classification {
        val signature = ArrowSignature.compute(width, height, pixels, ARROW_THRESHOLD, true)
            ?: return Classification(0, null, null, "")
        val grid = signature.asString()
        val match = WazeArrowTable.match(signature)
            ?: return Classification(0, null, null, grid)
        var icon = WazeArrowTable.iconFor(match.name)
        if (WazeArrowTable.isRoundabout(match.name) && exitNumber != null) {
            icon = exitNumber + if (WazeArrowTable.isLeftHandTraffic(match.name)) 34 else 24
        }
        return Classification(icon, match.name, match.hamming, grid)
    }

    private fun recycle(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION") runCatching { node.recycle() }
    }

    /**
     * Where Waze paints the arrow on the DiLink 150 route bar when the node reports no bounds:
     * 183x183 at (26,115), straight from openbyd's WazeManager.
     *
     * Kept as four ints rather than a `Rect` constant so this object can be loaded by a plain JVM
     * test - the classification core below is pure arithmetic and is tested without a device.
     */

    /** Fraction of the value range that separates arrow from background; openbyd's arrow setting. */
    private const val ARROW_THRESHOLD = 0.8f

    /** openbyd captures the arrow every two seconds; anything faster only costs battery. */
    internal const val MIN_ATTEMPT_INTERVAL_MS = 2000L
}
