package com.bydmate.app.navdata

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.waze.ArrowSignature
import com.bydmate.app.navdata.waze.WazeArrowTable
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
                            val classification = software?.let { classify(it, target.bounds, exitNumber) }
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
     * The arrow's rectangle: the first `navBarDirection` node with a real size, else the fixed
     * rectangle openbyd falls back to on the DiLink cluster layout.
     *
     * The fallback is not a guess - it is the arrow's position on this head unit, measured by the
     * donor and field-proven - and it matters because Waze sometimes reports the node with zero
     * bounds while still painting the arrow.
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
        return if (bounds != null) {
            Target(displayId, bounds, "navBarDirection")
        } else {
            Target(displayId, Rect(DEFAULT_LEFT, DEFAULT_TOP, DEFAULT_RIGHT, DEFAULT_BOTTOM), "default")
        }
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
    private const val DEFAULT_LEFT = 26
    private const val DEFAULT_TOP = 115
    private const val DEFAULT_RIGHT = 209
    private const val DEFAULT_BOTTOM = 298

    /** Fraction of the value range that separates arrow from background; openbyd's arrow setting. */
    private const val ARROW_THRESHOLD = 0.8f

    /** openbyd captures the arrow every two seconds; anything faster only costs battery. */
    internal const val MIN_ATTEMPT_INTERVAL_MS = 2000L
}
