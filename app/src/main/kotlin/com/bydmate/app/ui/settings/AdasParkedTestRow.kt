package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary

/**
 * The parked ADAS test (#23 step 2): pick a limit, send it with a camera for 30 s, see what the
 * car's screens and its assistance make of it. The test refuses unless the car is in P and still.
 */
@Composable
fun AdasParkedTestRow(viewModel: AdasParkedTestViewModel = hiltViewModel()) {
    val status by viewModel.test.status.collectAsStateWithLifecycle()
    var limit by remember { mutableIntStateOf(LIMITS[1]) }
    var refusal by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_adas_test_title), color = TextPrimary, fontSize = 15.sp)
        Text(stringResource(R.string.settings_adas_test_desc, CAMERA_M), color = TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIMITS.forEach { kph ->
                FilterChip(
                    selected = limit == kph,
                    onClick = { limit = kph },
                    enabled = !status.running,
                    label = { Text("$kph") },
                )
            }
            Button(
                onClick = { refusal = viewModel.start(limit, CAMERA_M) },
                enabled = !status.running,
            ) { Text(stringResource(R.string.settings_adas_test_start)) }
        }
        val car = status.car?.let {
            stringResource(R.string.settings_adas_test_car, it.signKph?.toString() ?: "-", it.slaKph?.toString() ?: "-")
        }
        when {
            refusal != null -> Text(refusal!!, color = AccentOrange, fontSize = 12.sp)
            status.running -> Text(
                stringResource(R.string.settings_adas_test_running, status.limitKph, status.cameraM, status.secondsLeft) +
                    (car?.let { " · $it" } ?: ""),
                color = TextPrimary, fontSize = 12.sp,
            )
            status.result != null -> Text(status.result + (car?.let { " · $it" } ?: ""), color = TextSecondary, fontSize = 12.sp)
        }
    }
}

/** Limits to send: clearly unlike the street's own, so the source on the screen is obvious. */
private val LIMITS = listOf(30, 70, 110)
private const val CAMERA_M = 300
