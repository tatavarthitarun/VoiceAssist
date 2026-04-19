package com.tatav.voiceassist.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tatav.voiceassist.data.AppSettings
import com.tatav.voiceassist.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setDoubleTapSpeed(ms: Float) {
        viewModelScope.launch { settingsRepository.setDoubleTapSpeed(ms) }
    }

    fun setAlwaysConfirm(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAlwaysConfirm(enabled) }
    }

    fun setVoiceVolume(volume: Float) {
        viewModelScope.launch { settingsRepository.setVoiceVolume(volume) }
    }

    fun setPlaySound(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPlaySound(enabled) }
    }

    fun setVibrate(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVibrate(enabled) }
    }

    fun setShowOverlay(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowOverlay(enabled) }
    }
}
