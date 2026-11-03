package com.bydmate.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bydmate.app.R
import com.bydmate.app.adas.AdasParkedTest
import com.bydmate.app.adas.RoutePoint
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The parked route test (#53) from the map page; the test itself is the app-wide singleton. */
@HiltViewModel
class AdasRouteTestViewModel @Inject constructor(val test: AdasParkedTest) : ViewModel() {
    fun start(route: RoutePlan): String? =
        test.startRoute(viewModelScope, route.points.map { RoutePoint(it.lat, it.lon) })
}

/**
 * Offers the shown route to the driving computer for 30 s, parked only (#53), so the owner can
 * see whether the ADAS screen draws it. Waze keeps its line to itself; this is the map's route.
 */
@Composable
fun AdasRouteTestButton(route: RoutePlan, viewModel: AdasRouteTestViewModel = hiltViewModel()) {
    val status by viewModel.test.status.collectAsStateWithLifecycle()
    var refusal by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedButton(onClick = { refusal = viewModel.start(route) }, enabled = !status.running) {
            Text(stringResource(R.string.map_adas_route_test))
        }
        when {
            refusal != null -> Text(refusal!!, color = AccentOrange, fontSize = 12.sp)
            status.running && status.route ->
                Text(stringResource(R.string.map_adas_route_running, status.secondsLeft), color = TextPrimary, fontSize = 12.sp)
            status.result != null -> Text(status.result!!, color = TextMuted, fontSize = 12.sp)
        }
    }
}
