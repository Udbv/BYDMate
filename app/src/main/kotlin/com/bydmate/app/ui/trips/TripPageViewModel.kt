package com.bydmate.app.ui.trips

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.dao.TripMinuteDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.domain.cost.BatteryPriceLookup
import com.bydmate.app.domain.cost.TripEnergyPrice
import com.bydmate.app.domain.prediction.TripForecast
import com.bydmate.app.domain.prediction.TripForecaster
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripPageUiState(
    val loading: Boolean = true,
    val trip: TripEntity? = null,
    val points: List<TripPointEntity> = emptyList(),
    val minutes: List<TripMinuteEntity> = emptyList(),
    val summary: TripEnergySummary? = null,
    val price: TripEnergyPrice? = null,
    val forecast: TripForecast? = null,
    val currencySymbol: String = "",
    val tileSource: String = SettingsRepository.DEFAULT_MAP_TILE_SOURCE,
    val hybrid: Boolean = false,
    /** Index into [minutes] the driver tapped; null = none yet. */
    val selected: Int? = null,
)

/** The trip page: the trip, its track and its minutes, opened from the trips list by id. */
@Suppress("LongParameterList") // the page reads one thing from each of its sources
@HiltViewModel
class TripPageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tripRepository: TripRepository,
    private val tripPointDao: TripPointDao,
    private val tripMinuteDao: TripMinuteDao,
    private val batteryPrice: BatteryPriceLookup,
    private val forecaster: TripForecaster,
    private val settingsRepository: SettingsRepository,
    private val vehicleKindMonitor: VehicleKindMonitor,
) : ViewModel() {

    private val tripId: Long = savedStateHandle.get<Long>(ARG_TRIP_ID) ?: 0L

    private val _uiState = MutableStateFlow(TripPageUiState())
    val uiState: StateFlow<TripPageUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun select(index: Int) {
        _uiState.update { it.copy(selected = index.takeIf { i -> i in it.minutes.indices }) }
    }

    private suspend fun load() {
        val trip = tripRepository.getTripById(tripId)
        if (trip == null) {
            _uiState.update { it.copy(loading = false) }
            return
        }
        val end = trip.endTs ?: System.currentTimeMillis()
        // The minute holding the start began before it.
        val minutes = tripMinuteDao.getInRange(trip.startTs - MINUTE_MS + 1, end)
        _uiState.update {
            it.copy(
                loading = false,
                trip = trip,
                points = tripPointDao.getByTripId(trip.id),
                minutes = minutes,
                summary = TripEnergySummary.of(minutes),
                price = batteryPrice.at(trip.startTs),
                forecast = forecaster.forecast(trip),
                currencySymbol = settingsRepository.getCurrencySymbol(),
                tileSource = settingsRepository.getMapTileSource(),
                hybrid = vehicleKindMonitor.kind.value == VehicleKind.HYBRID,
            )
        }
    }

    companion object {
        const val ARG_TRIP_ID = "tripId"
        const val ROUTE = "trip/{$ARG_TRIP_ID}"
        fun route(id: Long) = "trip/$id"
        private const val MINUTE_MS = 60_000L
    }
}
