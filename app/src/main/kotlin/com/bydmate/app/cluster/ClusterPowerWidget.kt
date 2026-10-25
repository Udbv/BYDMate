package com.bydmate.app.cluster

import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bydmate.app.camera.blindSpotClusterDisplay
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.domain.calculator.PowerHistory
import com.bydmate.app.ui.overlay.OverlayLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Потужність на приборці»: a small card on the cluster projection panel with the battery power
 * and its front/rear split (issue #5, stage 2). Always on while the car is on, once switched on.
 *
 * Same channel as the blind-spot camera on the cluster: an overlay window on the
 * XDJAScreenProjection display, the compositor powered up through the daemon, and on platformized
 * (UI7) firmware the Map frame taken so the panel shows the Android layer — that frame also hides
 * the stock side cards, which is where the card goes. Not yet seen on a Tang L: every step logs
 * under [TAG] and journals into the cluster ring, so the first drive tells which one fails.
 *
 * While the card is up, the camera and the navigation projection leave the compositor powered
 * ([active]); the card powers it down only when it is switched off with the car on.
 */
@Singleton
class ClusterPowerWidget @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
) {
    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(PowerCardState())

    private var root: FrameLayout? = null
    private var lifecycle: OverlayLifecycleOwner? = null
    private var windowManager: WindowManager? = null
    private var shownLayout: CardLayout? = null
    private var permissionAsked = false
    private var lastFailedShowAt = 0L
    private var missingDisplayLogged = false

    /** Called on every poll tick from TrackingService. */
    suspend fun onTick(data: DiParsData) {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val carOn = data.powerState?.let { it >= 1 } == true
        state.value = powerCardState(data)
        when {
            enabled && carOn -> {
                // Moved or resized in Settings: put the window where it now belongs.
                if (root != null && shownLayout != layoutPrefs()) withContext(Dispatchers.Main) { detach() }
                show()
            }
            root != null -> hide(powerDown = !enabled)
            // Switched off while the car was off: the compositor it powered up goes down now.
            !enabled && carOn && prefs.getBoolean(KEY_COMPOSITOR_POWERED, false) -> hide(powerDown = true)
        }
    }

    private fun layoutPrefs(): CardLayout = CardLayout(
        prefs.getInt(KEY_X_PCT, DEFAULT_X_PCT), prefs.getInt(KEY_Y_PCT, DEFAULT_Y_PCT),
        prefs.getInt(KEY_WIDTH_PCT, DEFAULT_WIDTH_PCT), prefs.getInt(KEY_HEIGHT_PCT, DEFAULT_HEIGHT_PCT),
    )

    private suspend fun show() {
        if (root != null) return
        // A failed attach is retried, but not at every 1 s tick: each try powers the compositor
        // and writes the UI7 frame.
        if (System.currentTimeMillis() - lastFailedShowAt < RETRY_MS) return
        val display = blindSpotClusterDisplay(context) ?: run {
            if (!missingDisplayLogged) Log.i(TAG, "no cluster projection display; card not shown")
            missingDisplayLogged = true
            return
        }
        if (!permissionAsked) {
            permissionAsked = true
            val granted = runCatching { helper.grantOverlayPermission() }.getOrDefault(false)
            Log.i(TAG, "overlay permission granted=$granted")
        }
        active = true
        if (!ClusterProjectionManager.isProjectionActive()) {
            val up = runCatching { helper.setClusterContainerMode(true) }.getOrDefault(false)
            prefs.edit().putBoolean(KEY_COMPOSITOR_POWERED, up).apply()
            journal("power card: compositor power-up ok=$up")
        }
        val frame = runCatching {
            ClusterProjectionManager.frameFor(context).apply(helper, ClusterFrameUi7.Owner.POWER_CARD)
        }.getOrDefault(false)
        val attached = withContext(Dispatchers.Main) { attach(display) }
        Log.i(TAG, "card on display ${display.displayId} '${display.name}': frame=$frame attached=$attached")
        journal("power card: shown display=${display.displayId} frame=$frame attached=$attached")
        if (!attached) {
            active = false
            lastFailedShowAt = System.currentTimeMillis()
        }
    }

    private suspend fun hide(powerDown: Boolean) {
        withContext(Dispatchers.Main) { detach() }
        active = false
        runCatching { ClusterProjectionManager.frameFor(context).restore(helper, ClusterFrameUi7.Owner.POWER_CARD) }
        if (powerDown && prefs.getBoolean(KEY_COMPOSITOR_POWERED, false) &&
            !ClusterProjectionManager.isProjectionActive()
        ) {
            val off = runCatching { helper.setClusterContainerMode(false) }.getOrDefault(false)
            if (off) prefs.edit().putBoolean(KEY_COMPOSITOR_POWERED, false).apply()
            journal("power card: compositor power-down ok=$off")
        }
        Log.i(TAG, "card hidden (switched off=$powerDown)")
    }

    private fun attach(display: Display): Boolean {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return false
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            display.getRealMetrics(it)
        }
        val layout = layoutPrefs()
        val geometry = cardGeometry(metrics.widthPixels, metrics.heightPixels, layout)
        val params = WindowManager.LayoutParams(
            geometry.width, geometry.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            OVERLAY_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geometry.x
            y = geometry.y
        }
        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        val compose = ComposeView(displayContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val card by state.collectAsState()
                val samples by PowerHistory.samples.collectAsState()
                ClusterPowerCard(card, samples)
            }
        }
        val frameLayout = FrameLayout(displayContext).apply {
            addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // WindowManager attaches the FrameLayout, so the owners must sit on it (see WidgetController).
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        return runCatching { wm.addView(frameLayout, params) }.fold(
            onSuccess = {
                shownLayout = layout
                root = frameLayout
                lifecycle = owner
                windowManager = wm
                true
            },
            onFailure = {
                Log.w(TAG, "addView failed: ${it.message}")
                owner.onDestroy()
                false
            },
        )
    }

    private fun detach() {
        val view = root ?: return
        runCatching { windowManager?.removeViewImmediate(view) }
        lifecycle?.onDestroy()
        root = null
        lifecycle = null
        windowManager = null
    }

    private fun journal(line: String) {
        runCatching { ClusterJournal.shared(context).append(line) }
    }

    companion object {
        private const val TAG = "ClusterPowerCard"
        const val PREFS_NAME = "cluster_power_card"
        const val KEY_ENABLED = "enabled"
        const val KEY_X_PCT = "x_pct"
        const val KEY_Y_PCT = "y_pct"
        const val KEY_WIDTH_PCT = "width_pct"
        const val KEY_HEIGHT_PCT = "height_pct"
        private const val KEY_COMPOSITOR_POWERED = "compositor_powered"
        private const val RETRY_MS = 30_000L

        /** Right side of the panel, where the stock right card sits; tuned in Settings. */
        const val DEFAULT_X_PCT = 70
        const val DEFAULT_Y_PCT = 25
        const val DEFAULT_WIDTH_PCT = 26
        const val DEFAULT_HEIGHT_PCT = 50

        private const val OVERLAY_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /** The card is on the cluster: the camera and the projection keep the compositor powered. */
        @Volatile
        var active: Boolean = false
            private set
    }
}

/** Where the card sits, in percent of the panel, as set in Settings. */
internal data class CardLayout(val xPct: Int, val yPct: Int, val widthPct: Int, val heightPct: Int)

internal data class CardGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

/** The card's window on a [displayWidth] x [displayHeight] panel; percentages clamped onto it. */
internal fun cardGeometry(displayWidth: Int, displayHeight: Int, layout: CardLayout): CardGeometry {
    val width = displayWidth * layout.widthPct.coerceIn(MIN_PCT, MAX_PCT) / MAX_PCT
    val height = displayHeight * layout.heightPct.coerceIn(MIN_PCT, MAX_PCT) / MAX_PCT
    val x = (displayWidth * layout.xPct.coerceIn(0, MAX_PCT) / MAX_PCT).coerceAtMost(displayWidth - width)
    val y = (displayHeight * layout.yPct.coerceIn(0, MAX_PCT) / MAX_PCT).coerceAtMost(displayHeight - height)
    return CardGeometry(x, y, width, height)
}

private const val MIN_PCT = 10
private const val MAX_PCT = 100
