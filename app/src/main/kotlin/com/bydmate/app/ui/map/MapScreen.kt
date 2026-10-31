package com.bydmate.app.ui.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.data.automation.RouteNavigatorUris
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.NavyDark
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.BoundingBox
import kotlin.math.roundToInt

/**
 * The map page: where the car is, how far the current range reaches (the outer ring: the whole
 * range on roads, the inner one: with a reserve), a place search and the hand-over to Waze.
 * A long press on the map picks a point too.
 */
@Composable
fun MapScreen(viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(NavyDark)) {
        ReachMap(state, onLongPress = viewModel::pickPoint, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).width(420.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchBar(state, viewModel, onWazeSearch = { openWaze(context, RouteNavigatorUris.search(RouteNavigatorUris.WAZE, it)) })
            Results(state, viewModel)
        }
        ReachLegend(state, Modifier.align(Alignment.TopEnd).padding(12.dp))
        state.target?.let { target ->
            TargetCard(
                state, target,
                onRoute = { openWaze(context, RouteNavigatorUris.route(RouteNavigatorUris.WAZE, target.lat, target.lon)) },
                onClose = viewModel::clearTarget,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).width(420.dp),
            )
        }
    }
}

@Composable
private fun SearchBar(state: MapUiState, viewModel: MapViewModel, onWazeSearch: (String) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.map_search_hint), color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = viewModel::search, enabled = !state.searching && state.query.isNotBlank()) {
                Text(stringResource(if (state.searching) R.string.map_searching else R.string.map_search))
            }
        }
        if (state.searchFailed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.map_search_failed), color = AccentOrange, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onWazeSearch(state.query) }) { Text(stringResource(R.string.map_search_in_waze)) }
            }
        }
    }
}

@Composable
private fun Results(state: MapUiState, viewModel: MapViewModel) {
    if (state.results.isEmpty()) return
    Panel {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(state.results) { place ->
                Column(Modifier.fillMaxWidth().clickable { viewModel.pick(place) }.padding(vertical = 6.dp)) {
                    Text(place.name, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(place.address, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        TextButton(onClick = viewModel::clearResults) { Text(stringResource(R.string.map_close)) }
    }
}

@Composable
private fun ReachLegend(state: MapUiState, modifier: Modifier) {
    val s = state.scenarios
    Panel(modifier) {
        if (s == null) {
            Text(stringResource(R.string.map_range_unknown), color = TextMuted, fontSize = 13.sp)
            return@Panel
        }
        Text(stringResource(R.string.map_scenarios_title), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        LegendRow(bandColor(ReachScenarios.Band.SURE), stringResource(R.string.map_scenario_heavy, s.heavyKm.roundToInt()))
        LegendRow(bandColor(ReachScenarios.Band.LIKELY), stringResource(R.string.map_scenario_normal, s.normalKm.roundToInt()))
        LegendRow(bandColor(ReachScenarios.Band.ECO_ONLY), stringResource(R.string.map_scenario_eco, s.ecoKm.roundToInt()))
        LegendRow(bandColor(ReachScenarios.Band.OUT), stringResource(R.string.map_scenario_out))
    }
}

@Composable
private fun LegendRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(18.dp).heightIn(min = 5.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(text, color = TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun TargetCard(
    state: MapUiState,
    target: MapTarget,
    onRoute: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Panel(modifier) {
        Text(
            target.name.ifBlank { "%.5f, %.5f".format(target.lat, target.lon) },
            color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
        )
        if (target.address.isNotBlank()) Text(target.address, color = TextMuted, fontSize = 12.sp, maxLines = 2)
        val route = state.route
        when {
            state.routing -> Text(stringResource(R.string.map_routing), color = TextMuted, fontSize = 14.sp)
            route != null -> RouteSummary(route, state.scenarios)
            state.routeFailed -> Text(stringResource(R.string.map_route_failed), color = AccentOrange, fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRoute, colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)) {
                Text(stringResource(R.string.map_route_waze), color = NavyDark)
            }
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.map_close)) }
        }
    }
}

/** The route's length and time, then per scenario what is left at the end or what is missing. */
@Composable
private fun RouteSummary(route: RoutePlan, s: ReachScenarios?) {
    Text(
        stringResource(R.string.map_route_summary, route.distanceKm.roundToInt(), route.durationMin.roundToInt()),
        color = TextPrimary, fontSize = 14.sp,
    )
    if (s == null) return
    listOf(
        Triple(R.string.map_scenario_name_heavy, s.heavyKm, ReachScenarios.Band.SURE),
        Triple(R.string.map_scenario_name_normal, s.normalKm, ReachScenarios.Band.LIKELY),
        Triple(R.string.map_scenario_name_eco, s.ecoKm, ReachScenarios.Band.ECO_ONLY),
    ).forEach { (name, reach, band) ->
        val left = reach - route.distanceKm
        val text = if (left >= 0) {
            stringResource(R.string.map_scenario_left, stringResource(name), left.roundToInt())
        } else {
            stringResource(R.string.map_scenario_short, stringResource(name), (-left).roundToInt())
        }
        LegendRow(if (left >= 0) bandColor(band) else bandColor(ReachScenarios.Band.OUT), text)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(CardSurface.copy(alpha = 0.94f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** Waze by its own scheme; without Waze the web link still opens a browser or Waze's site. */
private fun openWaze(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(RouteNavigatorUris.WAZE_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val web = uri.replaceFirst("waze://", "https://waze.com/ul")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
