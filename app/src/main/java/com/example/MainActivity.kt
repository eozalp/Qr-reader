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
    private lateinit var attachmentRepository: com.example.data.AttachmentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set default language to Turkish (tr-TR)
        val turkishLocale = java.util.Locale("tr", "TR")
        java.util.Locale.setDefault(turkishLocale)
        val config = resources.configuration
        config.setLocale(turkishLocale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)

        profileRepository = ProfileRepository(applicationContext)
        attachmentRepository = com.example.data.AttachmentRepository(applicationContext)

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
                                attachmentRepository = attachmentRepository,
                                onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                profileRepository = profileRepository,
                                attachmentRepository = attachmentRepository,
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
