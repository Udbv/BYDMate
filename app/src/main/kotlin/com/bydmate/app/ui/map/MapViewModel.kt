package com.bydmate.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.calculator.ConsumptionBoundsCache
import com.bydmate.app.service.TrackingService
import com.bydmate.app.util.AppStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A point the driver picked: a search result or a long press on the map. */
data class MapTarget(val name: String, val address: String, val lat: Double, val lon: Double)

data class MapUiState(
    val lat: Double? = null,
    val lon: Double? = null,
    val scenarios: ReachScenarios? = null,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<FoundPlace> = emptyList(),
    val searchFailed: Boolean = false,
    val target: MapTarget? = null,
    val routing: Boolean = false,
    val route: RoutePlan? = null,
    val routeFailed: Boolean = false,
    val startKwh: Double? = null,
    val capacityKwh: Double? = null,
    val outsideC: Double? = null,
    val dcPrice: Double? = null,
    val currency: String = "",
) {
    /** The cruise speeds compared over the route, from the energy the battery holds now. */
    val speedPlans: List<SpeedPlan>
        get() {
            val r = route ?: return emptyList()
            val start = startKwh ?: return emptyList()
            val capacity = capacityKwh?.takeIf { it > 0 } ?: return emptyList()
            val from = PlanStart(start, capacity, outsideC, dcPrice)
            return SpeedPlanner.CRUISE_SPEEDS.map { SpeedPlanner.plan(r.distanceKm, it, from) }
        }

    val pieces: List<RoutePiece>
        get() {
            val r = route ?: return emptyList()
            val s = scenarios ?: return listOf(RoutePiece(ReachScenarios.Band.LIKELY, r.points))
            return RouteBands.split(r.points, s)
        }
}

/**
 * The map page: the car on the map, a place search, the road route to the picked target coloured
 * by how far the charge reaches in three scenarios, and the hand-over to Waze. Position, range and
 * the energy left come from the tracking service as they change.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val placeSearch: PlaceSearch,
    private val routeClient: RouteClient,
    private val boundsCache: ConsumptionBoundsCache,
    private val settingsRepository: SettingsRepository,
    private val appStrings: AppStrings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()
    private var routeJob: Job? = null

    init {
        viewModelScope.launch {
            TrackingService.lastLocation.collect { loc ->
                if (loc != null) _uiState.update { it.copy(lat = loc.latitude, lon = loc.longitude) }
            }
        }
        viewModelScope.launch {
            val bounds = boundsCache.get()
            val capacity = settingsRepository.getBatteryCapacity()
            val dc = settingsRepository.getDcTariff().takeIf { it > 0 }
            val currency = settingsRepository.getCurrencySymbol()
            _uiState.update { it.copy(capacityKwh = capacity, dcPrice = dc, currency = currency) }
            combine(TrackingService.lastRangeKm, TrackingService.lastData) { km, data -> km to data }
                .collect { (km, data) ->
                    val remain = data?.batteryRemainKwh ?: data?.soc?.let { it / PERCENT * capacity }
                    val scenarios = km?.takeIf { it > 0 }?.let { ReachScenarios.of(it, remain, bounds) }
                    _uiState.update {
                        it.copy(scenarios = scenarios, startKwh = remain, outsideC = data?.exteriorTemp?.toDouble())
                    }
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
                it.copy(searching = false, results = result.getOrDefault(emptyList()), searchFailed = result.isFailure)
            }
        }
    }

    fun pick(place: FoundPlace) = setTarget(MapTarget(place.name, place.address, place.lat, place.lon))

    fun pickPoint(lat: Double, lon: Double) = setTarget(MapTarget("", "", lat, lon))

    fun clearTarget() {
        routeJob?.cancel()
        _uiState.update { it.copy(target = null, route = null, routing = false, routeFailed = false) }
    }

    fun clearResults() = _uiState.update { it.copy(results = emptyList(), searchFailed = false) }

    private fun setTarget(target: MapTarget) {
        _uiState.update { it.copy(target = target, results = emptyList(), route = null, routeFailed = false) }
        val s = _uiState.value
        val lat = s.lat ?: return
        val lon = s.lon ?: return
        routeJob?.cancel()
        _uiState.update { it.copy(routing = true) }
        routeJob = viewModelScope.launch {
            val result = routeClient.route(LatLon(lat, lon), LatLon(target.lat, target.lon))
            _uiState.update { it.copy(routing = false, route = result.getOrNull(), routeFailed = result.isFailure) }
        }
    }

    private companion object {
        const val PERCENT = 100.0
    }
}
