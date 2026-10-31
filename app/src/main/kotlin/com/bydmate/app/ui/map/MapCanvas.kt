package com.bydmate.app.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/** OSM tiles (the Amap tiles are empty outside China), the car, the target and the coloured route. */
@Composable
internal fun ReachMap(state: MapUiState, onLongPress: (Double, Double) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(DEFAULT_ZOOM)
        }
    }
    // The map centres on the car once and fits a new route once; after that the driver pans.
    val view = remember { ViewMemory() }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier, update = { map ->
        map.overlays.clear()
        map.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { onLongPress(it.latitude, it.longitude) }
                return p != null
            }
        })
        drawRoute(map, state, view)
        val lat = state.lat
        val lon = state.lon
        if (lat != null && lon != null) {
            val here = GeoPoint(lat, lon)
            map.overlays += Marker(map).apply {
                position = here
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = context.getString(R.string.map_you)
            }
            if (!view.centered) {
                view.centered = true
                map.controller.setCenter(here)
            }
        }
        state.target?.let { t ->
            map.overlays += Marker(map).apply {
                position = GeoPoint(t.lat, t.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = t.name
            }
            if (view.shownTarget != t && state.route == null) {
                view.shownTarget = t
                map.controller.animateTo(GeoPoint(t.lat, t.lon))
            }
        }
        map.invalidate()
    })
}

/** The route in its bands over a dark outline, with a dot where each scenario's charge runs out. */
private fun drawRoute(map: MapView, state: MapUiState, view: ViewMemory) {
    val route = state.route ?: return
    val all = route.points.map { GeoPoint(it.lat, it.lon) }
    map.overlays += line(all, Color.Black.copy(alpha = 0.7f), OUTLINE_WIDTH)
    for (piece in state.pieces) map.overlays += line(piece.points.map { GeoPoint(it.lat, it.lon) }, bandColor(piece.band), ROUTE_WIDTH)
    state.scenarios?.let { s ->
        for ((band, p) in RouteBands.limitPoints(route.points, s)) {
            map.overlays += Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot(map.context, bandColor(band))
                infoWindow = null
            }
        }
    }
    if (view.shownRoute != route && all.size >= 2) {
        view.shownRoute = route
        runCatching {
            val box = BoundingBox.fromGeoPoints(all)
            map.post { map.zoomToBoundingBox(box, true, FIT_PADDING_PX) }
        }
    }
}

private fun line(points: List<GeoPoint>, color: Color, width: Float) = Polyline().apply {
    setPoints(points)
    outlinePaint.color = color.toArgb()
    outlinePaint.strokeWidth = width
    outlinePaint.isAntiAlias = true
    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    infoWindow = null
}

private fun dot(context: Context, color: Color): android.graphics.drawable.Drawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color.toArgb())
        setStroke(DOT_STROKE_PX, android.graphics.Color.BLACK)
        val px = (DOT_DP * context.resources.displayMetrics.density).roundToInt()
        setSize(px, px)
    }

private class ViewMemory {
    var centered = false
    var shownTarget: MapTarget? = null
    var shownRoute: RoutePlan? = null
}

internal fun bandColor(band: ReachScenarios.Band): Color = when (band) {
    ReachScenarios.Band.SURE -> AccentGreen
    ReachScenarios.Band.LIKELY -> Color(0xFFFACC15)
    ReachScenarios.Band.ECO_ONLY -> AccentOrange
    ReachScenarios.Band.OUT -> Color(0xFFEF4444)
}

private const val DEFAULT_ZOOM = 12.0
private const val OUTLINE_WIDTH = 16f
private const val ROUTE_WIDTH = 10f
private const val FIT_PADDING_PX = 80
private const val DOT_DP = 16
private const val DOT_STROKE_PX = 3
