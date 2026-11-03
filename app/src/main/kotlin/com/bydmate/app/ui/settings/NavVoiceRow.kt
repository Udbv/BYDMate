package com.bydmate.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.diagnostics.audio.AudioChannel
import com.bydmate.app.navvoice.NavVoiceSettings
import com.bydmate.app.ui.theme.TextSecondary

/** BYDMate's own voice for Waze guidance: the switch, the audio channel and the ducking (#6). */
@Composable
internal fun NavVoiceRow() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NavVoiceSettings.isEnabled(context)) }
    var channel by remember { mutableStateOf(NavVoiceSettings.channel(context)) }
    var duck by remember { mutableStateOf(NavVoiceSettings.duck(context)) }
    SettingToggleRow(
        title = stringResource(R.string.settings_nav_voice_title),
        description = stringResource(R.string.settings_nav_voice_desc),
        checked = on,
        onCheckedChange = {
            on = it
            NavVoiceSettings.setEnabled(context, it)
        },
    )
    if (!on) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_nav_voice_channel), color = TextSecondary, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioChannel.entries.forEach { c ->
                FilterChip(
                    selected = channel == c,
                    onClick = {
                        channel = c
                        NavVoiceSettings.setChannel(context, c)
                    },
                    label = { Text(stringResource(c.label)) },
                )
            }
        }
    }
    SettingToggleRow(
        title = stringResource(R.string.settings_nav_voice_duck_title),
        description = stringResource(R.string.settings_nav_voice_duck_desc),
        checked = duck,
        onCheckedChange = {
            duck = it
            NavVoiceSettings.setDuck(context, it)
        },
    )
}
