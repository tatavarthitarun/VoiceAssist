package com.tatav.voiceassist.ui.setup

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.tatav.voiceassist.service.VoiceAssistService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SetupState(
    val step: Int = 1,
    val accessibilityEnabled: Boolean = false,
    val micGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val phoneGranted: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun refreshPermissions() {
        _state.update {
            it.copy(
                accessibilityEnabled = isAccessibilityServiceEnabled(),
                micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                contactsGranted = hasPermission(Manifest.permission.READ_CONTACTS),
                phoneGranted = hasPermission(Manifest.permission.CALL_PHONE)
                        && hasPermission(Manifest.permission.READ_PHONE_STATE)
                        && hasPermission(Manifest.permission.ANSWER_PHONE_CALLS),
            )
        }
    }

    fun nextStep() {
        _state.update { it.copy(step = (it.step + 1).coerceAtMost(3)) }
    }

    fun previousStep() {
        _state.update { it.copy(step = (it.step - 1).coerceAtLeast(1)) }
    }

    val allPermissionsGranted: Boolean
        get() = _state.value.run {
            accessibilityEnabled && micGranted && contactsGranted && phoneGranted
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(context, VoiceAssistService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = ComponentName.unflattenFromString(colonSplitter.next())
            if (componentName == expectedComponent) return true
        }
        return false
    }
}
