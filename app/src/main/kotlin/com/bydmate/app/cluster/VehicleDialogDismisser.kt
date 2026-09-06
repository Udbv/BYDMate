package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Auto-closes the DiLink warning window "This car is not from official export" (BYD's
 * `vehicledialog` window on cars sold outside their official market). Off by default; the switch
 * lives in Settings -> Display.
 *
 * Detection mirrors the RoamingManager donor (docs/investigations/roamingmanager-export-dialog.md):
 * the window TITLE contains "vehicledialog". Where the donor blind-taps fixed screen coordinates
 * over ADB, this rides the accessibility service BYDMate already runs: it clicks the dialog's
 * single button as a node (any language, any resolution) and falls back to the back key when the
 * window exposes no clickable node. Nothing else on screen is ever touched: the click is issued
 * only inside a window whose title matched.
 */
object VehicleDialogDismisser {
    private const val TAG = "VehicleDialogDismisser"
    const val PREFS_NAME = "vehicle_dialog"
    const val KEY_ENABLED = "auto_dismiss_export_warning"
    const val TITLE_TOKEN = "vehicledialog"
    /** The window-state event fires before the new window shows up in getWindows(): scan a
     *  little later, and coalesce the burst of events a dialog produces into one scan. */
    private const val SCAN_DELAY_MS = 350L
    /** After a click, ignore the events the dialog's own dismissal produces. */
    private const val COOLDOWN_MS = 1_500L
    private const val MAX_NODES = 400

    @Volatile private var enabled = false
    @Volatile private var lastDismissMs = 0L
    @Volatile private var scanPending = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile var dismissals: Long = 0L
        private set

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        enabled = on
        Log.i(TAG, "auto-dismiss ${if (on) "enabled" else "disabled"}")
    }

    /** Called when the accessibility service connects: picks up the persisted switch and looks at
     *  what is ALREADY on screen. The warning appears at boot, usually before this service is
     *  bound, so no window event will ever announce it: scan now and keep re-checking for a
     *  while, like the donor's 20 x 1 s loop. */
    fun refresh(context: Context, service: AccessibilityService? = null) {
        enabled = isEnabled(context)
        Log.i(TAG, "a11y service connected, auto-dismiss ${if (enabled) "enabled" else "disabled"}")
        if (!enabled || service == null) return
        longArrayOf(500, 2_000, 5_000, 10_000, 20_000, 40_000).forEach { delay ->
            handler.postDelayed({ if (enabled) runCatching { scan(service, logMisses = delay >= 5_000) } }, delay)
        }
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (!enabled) return
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        if (System.currentTimeMillis() - lastDismissMs < COOLDOWN_MS) return
        if (scanPending) return
        scanPending = true
        handler.postDelayed({
            scanPending = false
            if (enabled) runCatching { scan(service) }.onFailure { Log.w(TAG, "scan failed: ${it.message}") }
        }, SCAN_DELAY_MS)
    }

    private fun scan(service: AccessibilityService, logMisses: Boolean = false) {
        val windows: List<AccessibilityWindowInfo> = if (Build.VERSION.SDK_INT >= 30) {
            val byDisplay = service.windowsOnAllDisplays
            (0 until byDisplay.size()).flatMap { byDisplay.valueAt(it) }
        } else service.windows
        val seen = ArrayList<String>()
        for (window in windows) {
            val title = runCatching { window.title?.toString() }.getOrNull()
            val root = runCatching { window.root }.getOrNull()
            val pkg = runCatching { root?.packageName?.toString() }.getOrNull()
            // Three independent ways to recognize the warning, because the a11y window title is
            // the app LABEL (not the component name the donor greps in dumpsys): the label or the
            // package carries "vehicledialog", or the window shows the warning text itself.
            val matched = matchesTitle(title) || matchesTitle(pkg) ||
                (root != null && runCatching { containsWarningText(root) }.getOrDefault(false))
            if (!matched) {
                seen += "'${title ?: "-"}'/${pkg ?: "-"}"
                @Suppress("DEPRECATION") runCatching { root?.recycle() }
                continue
            }
            lastDismissMs = System.currentTimeMillis()
            val clicked = root != null && try {
                clickFirstButton(root)
            } finally {
                @Suppress("DEPRECATION") runCatching { root?.recycle() }
            }
            val done = clicked || service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (done) dismissals++
            Log.i(TAG, "export warning window '$title' -> ${if (clicked) "button clicked" else if (done) "back key" else "no action"} (#$dismissals)")
            // A re-shown or stacked instance produces no fresh event once the cooldown passes:
            // look once more on our own.
            handler.postDelayed({ if (enabled) runCatching { scan(service) } }, COOLDOWN_MS + 100)
            return
        }
        // Field diagnostics: what the service can see when the warning is NOT recognized, so a
        // dump from the car shows the real window label/package to match on.
        if (logMisses) Log.i(TAG, "no export warning among ${seen.size} windows: ${seen.joinToString(" | ")}")
    }

    /** Pure: the title match, case-insensitive substring like the donor's dumpsys grep. */
    fun matchesTitle(title: String?): Boolean =
        title != null && title.contains(TITLE_TOKEN, ignoreCase = true)

    /** The warning's own words, English as shown on the export cars; a shallow text scan. */
    internal val WARNING_PHRASES = listOf("not from official export", "official export", "非官方出口", "不是官方出口")

    internal fun containsWarningText(root: AccessibilityNodeInfo, maxNodes: Int = 120): Boolean {
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var found = false
        while (queue.isNotEmpty() && visited++ < maxNodes && !found) {
            val node = queue.removeFirst()
            val text = runCatching { node.text?.toString() }.getOrNull().orEmpty()
            if (WARNING_PHRASES.any { text.contains(it, ignoreCase = true) }) found = true
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
            if (node !== root) @Suppress("DEPRECATION") runCatching { node.recycle() }
        }
        queue.forEach { if (it !== root) @Suppress("DEPRECATION") runCatching { it.recycle() } }
        return found
    }

    /** Clicks the first clickable node, preferring a Button class. The dialog has one button. */
    internal fun clickFirstButton(root: AccessibilityNodeInfo): Boolean {
        val target = pickButton(root) ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            @Suppress("DEPRECATION") runCatching { target.recycle() }
        }
    }

    /** Pure traversal: first enabled clickable Button, else first enabled clickable node.
     *  Caller owns the returned node; intermediate nodes are recycled. */
    internal fun pickButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            val isRoot = node === root
            val clickable = runCatching { node.isClickable && node.isEnabled }.getOrDefault(false)
            val isButton = runCatching { node.className?.toString()?.contains("Button") == true }.getOrDefault(false)
            if (clickable && isButton) {
                @Suppress("DEPRECATION") runCatching { fallback?.recycle() }
                queue.forEach { @Suppress("DEPRECATION") runCatching { it.recycle() } }
                return if (isRoot) AccessibilityNodeInfo.obtain(node) else node
            }
            if (clickable && fallback == null) {
                fallback = if (isRoot) AccessibilityNodeInfo.obtain(node) else node
            } else {
                val count = runCatching { node.childCount }.getOrDefault(0)
                for (i in 0 until count) {
                    runCatching { node.getChild(i) }.getOrNull()?.let(queue::add)
                }
                if (!isRoot) @Suppress("DEPRECATION") runCatching { node.recycle() }
            }
        }
        queue.forEach { @Suppress("DEPRECATION") runCatching { it.recycle() } }
        return fallback
    }
}
