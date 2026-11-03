package com.bydmate.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.R
import com.bydmate.app.data.homeassistant.HaSnapshot
import com.bydmate.app.data.homeassistant.HaTestResult
import com.bydmate.app.data.homeassistant.HaUrls
import com.bydmate.app.data.homeassistant.HomeAssistantPublisher
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

data class HomeAssistantUiState(
    val enabled: Boolean = false,
    val url: String = "",
    val token: String = "",
    val status: String? = null,
    val testRunning: Boolean = false,
)

/**
 * Settings card of the Home Assistant device (#26). Kept apart from [SettingsViewModel], which
 * is already the largest class in the app; the card talks only to its own keys.
 */
@HiltViewModel
class HomeAssistantSettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val publisher: HomeAssistantPublisher,
    private val strings: AppStrings,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeAssistantUiState())
    val state: StateFlow<HomeAssistantUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    enabled = settings.getString(SettingsRepository.KEY_HA_ENABLED, "false") == "true",
                    url = settings.getString(SettingsRepository.KEY_HA_URL, ""),
                    token = settings.getString(SettingsRepository.KEY_HA_TOKEN, ""),
                )
            }
        }
    }

    fun updateUrl(value: String) = _state.update { it.copy(url = value, status = null) }

    fun updateToken(value: String) = _state.update { it.copy(token = value, status = null) }

    /** The switch only sticks with an address and a token saved: without them there is nothing to send to. */
    fun toggleEnabled(enabled: Boolean) {
        val s = _state.value
        val effective = enabled && HaUrls.normalizeBase(s.url) != null && s.token.isNotBlank()
        _state.update { it.copy(enabled = effective) }
        viewModelScope.launch { settings.setString(SettingsRepository.KEY_HA_ENABLED, effective.toString()) }
        if (enabled && !effective) {
            _state.update { it.copy(status = strings.get(R.string.settings_ha_invalid_url)) }
        }
    }

    fun save() {
        val s = _state.value
        val base = HaUrls.normalizeBase(s.url)
        if (s.url.isNotBlank() && base == null) {
            _state.update { it.copy(status = strings.get(R.string.settings_ha_invalid_url)) }
            return
        }
        val url = base?.toString().orEmpty()
        val token = s.token.trim()
        val enabled = s.enabled && base != null && token.isNotEmpty()
        viewModelScope.launch {
            settings.setStrings(
                mapOf(
                    SettingsRepository.KEY_HA_URL to url,
                    SettingsRepository.KEY_HA_TOKEN to token,
                    SettingsRepository.KEY_HA_ENABLED to enabled.toString(),
                )
            )
            _state.update {
                it.copy(url = url, token = token, enabled = enabled, status = strings.get(R.string.settings_ha_saved))
            }
        }
    }

    /** Tests what is in the fields now, saved or not, so a typo shows before the save. */
    fun testConnection() {
        if (_state.value.testRunning) return
        _state.update { it.copy(testRunning = true, status = strings.get(R.string.settings_ha_test_running)) }
        viewModelScope.launch {
            val s = _state.value
            val snapshot = TrackingService.lastData.value?.let { HaSnapshot(it, TrackingService.lastRangeKm.value) }
            val result = publisher.testConnection(s.url, s.token, snapshot)
            _state.update { it.copy(testRunning = false, status = describe(result)) }
        }
    }

    private fun describe(result: HaTestResult): String = when (result) {
        HaTestResult.InvalidUrl -> strings.get(R.string.settings_ha_invalid_url)
        HaTestResult.EmptyToken -> strings.get(R.string.settings_ha_empty_token)
        HaTestResult.Unauthorized -> strings.get(R.string.settings_ha_test_unauthorized)
        HaTestResult.BadAnswer -> strings.get(R.string.settings_ha_test_bad_answer)
        is HaTestResult.HttpError -> strings.get(R.string.settings_ha_test_http, result.code)
        is HaTestResult.Unreachable -> strings.get(R.string.settings_ha_test_unreachable, result.reason)
        is HaTestResult.Connected -> strings.get(R.string.settings_ha_test_ok, result.sensorCount)
    }
}
