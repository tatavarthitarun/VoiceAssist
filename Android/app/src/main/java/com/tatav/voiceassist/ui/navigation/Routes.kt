package com.tatav.voiceassist.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object SplashRoute : NavKey

@Serializable
data object SetupRoute : NavKey

@Serializable
data object HomeRoute : NavKey

@Serializable
data object SettingsRoute : NavKey
