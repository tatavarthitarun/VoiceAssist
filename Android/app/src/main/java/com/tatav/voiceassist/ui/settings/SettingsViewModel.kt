package com.tatav.voiceassist.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsState(
    val doubleTapSpeedMs: Float = 400f,
    val alwaysConfirm: Boolean = true,
    val voiceVolume: Float = 1f,
    val playSoundOnActivation: Boolean = true,
    val vibrateOnActivation: Boolean = true,
    val showVisualOverlay: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun setDoubleTapSpeed(ms: Float) {
        _state.update { it.copy(doubleTapSpeedMs = ms) }
    }

    fun setAlwaysConfirm(enabled: Boolean) {
        _state.update { it.copy(alwaysConfirm = enabled) }
    }

    fun setVoiceVolume(volume: Float) {
        _state.update { it.copy(voiceVolume = volume) }
    }

    fun setPlaySound(enabled: Boolean) {
        _state.update { it.copy(playSoundOnActivation = enabled) }
    }

    fun setVibrate(enabled: Boolean) {
        _state.update { it.copy(vibrateOnActivation = enabled) }
    }

    fun setShowOverlay(enabled: Boolean) {
        _state.update { it.copy(showVisualOverlay = enabled) }
    }
}
