package com.bydmate.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydmate.app.R
import com.bydmate.app.diagnostics.audio.AudioChannelTest
import com.bydmate.app.diagnostics.audio.AudioChannelTestEntryPoint
import dagger.hilt.android.EntryPointAccessors

/** «Тест аудиоканалов» (issue #6): starts or stops the run and shows where it is. The owner
 *  listens for which variants dip the music; the per-variant result lines are shown here too. */
@Composable
internal fun AudioChannelTestRow() {
    val context = LocalContext.current
    val test = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, AudioChannelTestEntryPoint::class.java)
            .audioChannelTest()
    }
    val state by test.state.collectAsStateWithLifecycle()
    val running = state is AudioChannelTest.State.Running
    SettingActionRow(
        title = stringResource(R.string.settings_audio_test_title),
        description = stringResource(R.string.settings_audio_test_desc),
        buttonLabel = stringResource(
            if (running) R.string.settings_audio_test_stop else R.string.settings_audio_test_start,
        ),
        onClick = { if (running) test.stop() else test.start() },
    )
    when (val s = state) {
        AudioChannelTest.State.Idle -> Unit
        is AudioChannelTest.State.Running -> SettingHint(
            text = stringResource(
                R.string.settings_audio_test_running,
                s.step,
                s.total,
                stringResource(s.variant.channel.label) + ", " + stringResource(s.variant.modeLabel),
            ),
        )
        is AudioChannelTest.State.Done -> SettingHint(
            text = stringResource(R.string.settings_audio_test_done) + "\n" + s.lines.joinToString("\n"),
        )
    }
}
