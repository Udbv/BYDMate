package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydmate.app.R
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardSurfaceElevated

/** Integrations → Home Assistant: address, token, switch, save and a connection test (#26). */
@Composable
internal fun HomeAssistantSettingsSection(viewModel: HomeAssistantSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    SectionHeader(text = stringResource(R.string.settings_ha_section_header))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingToggleRow(
                title = stringResource(R.string.settings_ha_label),
                traceId = "home_assistant",
                description = stringResource(R.string.settings_ha_description),
                checked = state.enabled,
                onCheckedChange = { viewModel.toggleEnabled(it) },
            )
            SettingsTextField(
                label = stringResource(R.string.settings_ha_url_label),
                value = state.url,
                onValueChange = { viewModel.updateUrl(it) },
                keyboardType = KeyboardType.Uri,
            )
            SettingsTextField(
                label = stringResource(R.string.settings_ha_token_label),
                value = state.token,
                onValueChange = { viewModel.updateToken(it) },
                keyboardType = KeyboardType.Password,
                secret = true,
            )
            SettingActionRow(
                title = stringResource(R.string.settings_ha_save_button),
                buttonLabel = stringResource(R.string.settings_ha_save_button),
                onClick = { viewModel.save() },
                style = SettingButtonStyle.Primary,
                secondButtonLabel = stringResource(R.string.settings_ha_test_button),
                onSecondClick = { viewModel.testConnection() },
                secondButtonEnabled = !state.testRunning,
            )
            state.status?.let {
                Text(it, color = AccentGreen, fontSize = 12.sp)
            }
        }
    }
}
