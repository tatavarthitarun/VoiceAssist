package com.tatav.voiceassist

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.tatav.voiceassist.service.VoiceAssistService
import com.tatav.voiceassist.ui.home.HomeScreen
import com.tatav.voiceassist.ui.home.HomeViewModel
import com.tatav.voiceassist.ui.navigation.HomeRoute
import com.tatav.voiceassist.ui.navigation.SettingsRoute
import com.tatav.voiceassist.ui.navigation.SetupRoute
import com.tatav.voiceassist.ui.navigation.SplashRoute
import com.tatav.voiceassist.ui.settings.SettingsScreen
import com.tatav.voiceassist.ui.settings.SettingsViewModel
import com.tatav.voiceassist.ui.setup.SetupScreen
import com.tatav.voiceassist.ui.setup.SetupViewModel
import com.tatav.voiceassist.ui.splash.SplashScreen
import com.tatav.voiceassist.ui.theme.VoiceAssistTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val startRoute = if (isSetupComplete()) HomeRoute else SplashRoute

        setContent {
            VoiceAssistTheme {
                val backStack = rememberNavBackStack(startRoute)

                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = { key ->
                        when (key) {
                            is SplashRoute -> NavEntry(key) {
                                SplashScreen(
                                    onGetStarted = dropUnlessResumed {
                                        backStack.clear()
                                        backStack.add(SetupRoute)
                                    }
                                )
                            }

                            is SetupRoute -> NavEntry(key) {
                                val viewModel = hiltViewModel<SetupViewModel>()
                                SetupScreen(
                                    viewModel = viewModel,
                                    onFinish = dropUnlessResumed {
                                        backStack.clear()
                                        backStack.add(HomeRoute)
                                    },
                                )
                            }

                            is HomeRoute -> NavEntry(key) {
                                val viewModel = hiltViewModel<HomeViewModel>()
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToSettings = dropUnlessResumed {
                                        backStack.add(SettingsRoute)
                                    },
                                )
                            }

                            is SettingsRoute -> NavEntry(key) {
                                val viewModel = hiltViewModel<SettingsViewModel>()
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onBack = dropUnlessResumed {
                                        backStack.removeLastOrNull()
                                    },
                                    onRerunSetup = dropUnlessResumed {
                                        backStack.clear()
                                        backStack.add(SetupRoute)
                                    },
                                )
                            }

                            else -> error("Unknown route: $key")
                        }
                    }
                )
            }
        }
    }

    private fun isSetupComplete(): Boolean {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        return hasAccessibility && hasMic && hasContacts && hasPhone
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(this, VoiceAssistService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expectedComponent) return true
        }
        return false
    }
}
