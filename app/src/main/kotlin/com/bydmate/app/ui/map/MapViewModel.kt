package com.bydmate.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.util.AppStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A point the driver picked: a search result or a long press on the map. */
data class MapTarget(val name: String, val address: String, val lat: Double, val lon: Double)

data class MapUiState(
    val lat: Double? = null,
    val lon: Double? = null,
    val reach: Reach? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<FoundPlace> = emptyList(),
    val searchFailed: Boolean = false,
    val target: MapTarget? = null,
    val tileSource: String = SettingsRepository.DEFAULT_MAP_TILE_SOURCE,
) {
    /** Straight-line km from the car to the target; null without both. */
    val targetStraightKm: Double?
        get() {
            val t = target ?: return null
            return haversineKm(lat ?: return null, lon ?: return null, t.lat, t.lon)
        }
}

/**
 * The map page: the car on the map, how far the current range reaches, a place search and the
 * hand-over to Waze. Position and range come from the tracking service as they change.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val placeSearch: PlaceSearch,
    private val settingsRepository: SettingsRepository,
    private val appStrings: AppStrings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(tileSource = settingsRepository.getMapTileSource()) }
        }
        viewModelScope.launch {
            TrackingService.lastLocation.collect { loc ->
                if (loc != null) _uiState.update { it.copy(lat = loc.latitude, lon = loc.longitude) }
            }
        }
        viewModelScope.launch {
            TrackingService.lastRangeKm.collect { km ->
                _uiState.update { it.copy(reach = km?.takeIf { r -> r > 0 }?.let(::Reach)) }
            }
        }
    }

    fun setQuery(q: String) = _uiState.update { it.copy(query = q) }

    fun search() {
        val q = _uiState.value.query.trim()
        if (q.isEmpty() || _uiState.value.searching) return
        _uiState.update { it.copy(searching = true, searchFailed = false) }
        viewModelScope.launch {
            val s = _uiState.value
            val language = appStrings.context.resources.configuration.locales[0].language
            val result = placeSearch.search(q, s.lat, s.lon, language)
            _uiState.update {
                it.copy(
                    searching = false,
                    results = result.getOrDefault(emptyList()),
                    searchFailed = result.isFailure,
                )
            }
        }
    }

    fun pick(place: FoundPlace) =
        _uiState.update { it.copy(target = MapTarget(place.name, place.address, place.lat, place.lon), results = emptyList()) }

    fun pickPoint(lat: Double, lon: Double) =
        _uiState.update { it.copy(target = MapTarget("", "", lat, lon), results = emptyList()) }

    fun clearTarget() = _uiState.update { it.copy(target = null) }

    fun clearResults() = _uiState.update { it.copy(results = emptyList(), searchFailed = false) }
}
