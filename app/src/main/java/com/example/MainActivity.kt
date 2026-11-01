package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.ProfileRepository
import com.example.ui.CameraScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.QrLiveCalcTheme

enum class AppScreen {
    CAMERA,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var profileRepository: ProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        profileRepository = ProfileRepository(applicationContext)

        setContent {
            QrLiveCalcTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.CAMERA) }

                Crossfade(
                    targetState = currentScreen,
                    label = "screen_navigation",
                    modifier = Modifier.fillMaxSize()
                ) { screen ->
                    when (screen) {
                        AppScreen.CAMERA -> {
                            CameraScreen(
                                profileRepository = profileRepository,
                                onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                profileRepository = profileRepository,
                                onNavigateBack = { currentScreen = AppScreen.CAMERA },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
