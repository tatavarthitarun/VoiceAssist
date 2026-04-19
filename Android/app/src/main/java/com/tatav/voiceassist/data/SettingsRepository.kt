package com.tatav.voiceassist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val doubleTapSpeedMs: Float = 400f,
    val alwaysConfirm: Boolean = true,
    val voiceVolume: Float = 1f,
    val playSoundOnActivation: Boolean = true,
    val vibrateOnActivation: Boolean = true,
    val showVisualOverlay: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val DOUBLE_TAP_SPEED = floatPreferencesKey("double_tap_speed_ms")
        val ALWAYS_CONFIRM = booleanPreferencesKey("always_confirm")
        val VOICE_VOLUME = floatPreferencesKey("voice_volume")
        val PLAY_SOUND = booleanPreferencesKey("play_sound_on_activation")
        val VIBRATE = booleanPreferencesKey("vibrate_on_activation")
        val SHOW_OVERLAY = booleanPreferencesKey("show_visual_overlay")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            doubleTapSpeedMs = prefs[Keys.DOUBLE_TAP_SPEED] ?: 400f,
            alwaysConfirm = prefs[Keys.ALWAYS_CONFIRM] ?: true,
            voiceVolume = prefs[Keys.VOICE_VOLUME] ?: 1f,
            playSoundOnActivation = prefs[Keys.PLAY_SOUND] ?: true,
            vibrateOnActivation = prefs[Keys.VIBRATE] ?: true,
            showVisualOverlay = prefs[Keys.SHOW_OVERLAY] ?: true,
        )
    }

    suspend fun setDoubleTapSpeed(ms: Float) {
        context.dataStore.edit { it[Keys.DOUBLE_TAP_SPEED] = ms }
    }

    suspend fun setAlwaysConfirm(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALWAYS_CONFIRM] = enabled }
    }

    suspend fun setVoiceVolume(volume: Float) {
        context.dataStore.edit { it[Keys.VOICE_VOLUME] = volume }
    }

    suspend fun setPlaySound(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PLAY_SOUND] = enabled }
    }

    suspend fun setVibrate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATE] = enabled }
    }

    suspend fun setShowOverlay(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_OVERLAY] = enabled }
    }
}
