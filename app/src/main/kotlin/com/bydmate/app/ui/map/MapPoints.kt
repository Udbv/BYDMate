package com.bydmate.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.RoadPointEntity
import com.bydmate.app.data.roadpoints.RoadPointExport
import com.bydmate.app.data.roadpoints.RoadPointRecorder
import com.bydmate.app.data.roadpoints.RoadPointType
import com.bydmate.app.data.roadpoints.RoadPoints
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextPrimary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** The panel's own state: the selected point and the last export's outcome. */
data class MapPointsUi(val selectedId: Long? = null, val exporting: Boolean = false, val message: String? = null)

/** «Мої точки» on the map page (#22): the marked places, one to look at or delete, the export. */
@HiltViewModel
class MapPointsViewModel @Inject constructor(private val recorder: RoadPointRecorder) : ViewModel() {
    val points: StateFlow<List<RoadPointEntity>> =
        recorder.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    private val _ui = MutableStateFlow(MapPointsUi())
    val ui: StateFlow<MapPointsUi> = _ui.asStateFlow()

    fun select(id: Long?) = _ui.update { it.copy(selectedId = id, message = null) }

    fun delete(id: Long) {
        viewModelScope.launch { recorder.delete(id) }
        _ui.update { it.copy(selectedId = null) }
    }

    fun export() {
        if (_ui.value.exporting) return
        _ui.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            val error = recorder.exportToTelegram()
            _ui.update { it.copy(exporting = false, message = error ?: SENT) }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val SENT = "Надіслано в Telegram"
    }
}

/** The count, the export button and, with a point tapped, its card. */
@Composable
internal fun PointsPanel(
    points: List<RoadPointEntity>,
    ui: MapPointsUi,
    onExport: () -> Unit,
    onDelete: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    Column(
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(CardSurface.copy(alpha = 0.94f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val selected = points.firstOrNull { it.id == ui.selectedId }
        if (selected != null) {
            Text(pointTitle(selected), color = TextPrimary, fontSize = 15.sp)
            Text(
                stringResource(
                    R.string.map_point_details,
                    RoadPoints.compass(selected.heading),
                    selected.passes,
                    SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(selected.lastTs)),
                ),
                color = TextMuted, fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDelete(selected.id) }) { Text(stringResource(R.string.map_point_delete)) }
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.map_close)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.map_points_count, points.size), color = TextPrimary, fontSize = 14.sp)
            Button(onClick = onExport, enabled = !ui.exporting) { Text(stringResource(R.string.map_points_export)) }
        }
        ui.message?.let { Text(it, color = AccentOrange, fontSize = 12.sp) }
    }
}

internal fun pointTitle(p: RoadPointEntity): String = RoadPointExport.title(RoadPointType.of(p.type) ?: RoadPointType.OTHER)
