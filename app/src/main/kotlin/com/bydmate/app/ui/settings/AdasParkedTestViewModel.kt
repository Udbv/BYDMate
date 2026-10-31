package com.bydmate.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.adas.AdasParkedTest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The parked ADAS test for [AdasParkedTestRow]; the test itself is the app-wide singleton. */
@HiltViewModel
class AdasParkedTestViewModel @Inject constructor(val test: AdasParkedTest) : ViewModel() {
    fun start(limitKph: Int, cameraM: Int): String? = test.start(viewModelScope, limitKph, cameraM)
}
