package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.bluetooth.BluetoothHidManager
import com.example.data.ProfileRepository
import com.example.debug.AppDebugLogger
import com.example.ui.CameraScreen
import com.example.ui.RemoteControlScreen
import com.example.ui.components.BluetoothKeyboardSheet
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.QrLiveCalcTheme

enum class AppScreen {
    CAMERA,
    SETTINGS,
    REMOTE
}

class MainActivity : ComponentActivity() {

    private lateinit var profileRepository: ProfileRepository
    private lateinit var attachmentRepository: com.example.data.AttachmentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDebugLogger.installGlobalExceptionHandler(applicationContext)
        enableEdgeToEdge()

        // Set default language to Turkish (tr-TR)
        runCatching {
            val turkishLocale = java.util.Locale("tr", "TR")
            java.util.Locale.setDefault(turkishLocale)
            val config = resources.configuration
            config.setLocale(turkishLocale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }.onFailure { err ->
            AppDebugLogger.w("APP", "Türkçe yerel ayar uygulanamadı", err)
        }

        profileRepository = ProfileRepository(applicationContext)
        attachmentRepository = com.example.data.AttachmentRepository(applicationContext)

        setContent {
            QrLiveCalcTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.CAMERA) }
                var remoteReturnScreen by remember { mutableStateOf(AppScreen.CAMERA) }
                var showRemoteBtSheet by remember { mutableStateOf(false) }

                val hidManager = remember { BluetoothHidManager.getInstance(applicationContext) }
                val activeProfile by profileRepository.activeProfile.collectAsState()

                val btPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    AppDebugLogger.i("BT-PERM", "Bluetooth izin sonucu: $grants")
                    hidManager.refreshState()
                }

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
                                onOpenRemoteControl = {
                                    remoteReturnScreen = AppScreen.CAMERA
                                    currentScreen = AppScreen.REMOTE
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                profileRepository = profileRepository,
                                attachmentRepository = attachmentRepository,
                                onNavigateBack = { currentScreen = AppScreen.CAMERA },
                                onOpenRemoteControl = {
                                    remoteReturnScreen = AppScreen.SETTINGS
                                    currentScreen = AppScreen.REMOTE
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        AppScreen.REMOTE -> {
                            RemoteControlScreen(
                                hidManager = hidManager,
                                keyboardConfig = activeProfile.bluetoothKeyboard,
                                onOpenBluetoothSheet = { showRemoteBtSheet = true },
                                onNavigateBack = { currentScreen = remoteReturnScreen },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (showRemoteBtSheet && currentScreen == AppScreen.REMOTE) {
                    BluetoothKeyboardSheet(
                        hidManager = hidManager,
                        currentConfig = activeProfile.bluetoothKeyboard,
                        availableVariables = activeProfile.parsingRules.variables,
                        onConfigChanged = { updatedKb ->
                            profileRepository.saveProfile(activeProfile.copy(bluetoothKeyboard = updatedKb))
                        },
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                btPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.BLUETOOTH_ADVERTISE
                                    )
                                )
                            } else {
                                btPermLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.BLUETOOTH,
                                        Manifest.permission.BLUETOOTH_ADMIN
                                    )
                                )
                            }
                        },
                        onDismiss = { showRemoteBtSheet = false }
                    )
                }
            }
        }
    }
}
