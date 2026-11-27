package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bluetooth.BluetoothHidManager
import com.example.data.ProfileRepository
import com.example.debug.AppDebugLogger
import com.example.ui.CameraScreen
import com.example.ui.RemoteControlScreen
import com.example.ui.components.BluetoothKeyboardSheet
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.QrLiveCalcTheme

enum class AppScreen {
    REMOTE,
    CAMERA,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var profileRepository: ProfileRepository
    private lateinit var attachmentRepository: com.example.data.AttachmentRepository

    // Active hardware volume key interceptor registered only while RemoteControlScreen is visible
    @Volatile
    private var volumeKeyHandler: ((keyCode: Int, isDown: Boolean) -> Boolean)? = null

    private val openRemoteRequestSeq = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDebugLogger.installGlobalExceptionHandler(applicationContext)
        enableEdgeToEdge()

        // Set default language to Turkish (tr-TR)
        runCatching {
            val turkishLocale = java.util.Locale.forLanguageTag("tr-TR")
            java.util.Locale.setDefault(turkishLocale)
            val config = resources.configuration
            config.setLocale(turkishLocale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }.onFailure { err ->
            AppDebugLogger.w("APP", "Türkçe yerel ayar uygulanamadı", err)
        }

        profileRepository = ProfileRepository.getInstance(applicationContext)
        attachmentRepository = com.example.data.AttachmentRepository.getInstance(applicationContext)

        setContent {
            QrLiveCalcTheme {
                // Primary entry screen is now PC Bluetooth Remote Control; QR Scanner is a 1-tap side feature.
                var currentScreen by rememberSaveable { mutableStateOf(AppScreen.REMOTE) }
                var settingsReturnScreen by rememberSaveable { mutableStateOf(AppScreen.REMOTE) }
                var showRemoteBtSheet by rememberSaveable { mutableStateOf(false) }

                val remoteSeq by openRemoteRequestSeq
                LaunchedEffect(remoteSeq) {
                    if (remoteSeq > 0) {
                        currentScreen = AppScreen.REMOTE
                    }
                }

                // Always clear lock-screen bypass, keep-screen-on, AOD dim, orientation lock & volume intercept outside Remote
                LaunchedEffect(currentScreen) {
                    if (currentScreen != AppScreen.REMOTE) {
                        applyAodDisplayMode(enabled = false, roomBrightBoost = false)
                        applyLockScreenMode(enabled = false, keepAwake = false)
                        applyLandscapeMode(enabled = false)
                        volumeKeyHandler = null
                    }
                }

                val hidManager = remember { BluetoothHidManager.getInstance(applicationContext) }
                val activeProfile by profileRepository.activeProfile.collectAsState()

                val btPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    AppDebugLogger.i("BT-PERM", "Bluetooth izin sonucu: $grants")
                    hidManager.refreshState()
                }

                val requestBluetoothPermissions: () -> Unit = {
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
                }

                Crossfade(
                    targetState = currentScreen,
                    label = "screen_navigation",
                    modifier = Modifier.fillMaxSize()
                ) { screen ->
                    when (screen) {
                        AppScreen.REMOTE -> {
                            RemoteControlScreen(
                                hidManager = hidManager,
                                keyboardConfig = activeProfile.bluetoothKeyboard,
                                onOpenBluetoothSheet = { showRemoteBtSheet = true },
                                onOpenQrScanner = { currentScreen = AppScreen.CAMERA },
                                onOpenSettings = {
                                    settingsReturnScreen = AppScreen.REMOTE
                                    currentScreen = AppScreen.SETTINGS
                                },
                                onLockScreenConfigChanged = { lockScreenEnabled, keepAwake ->
                                    applyLockScreenMode(lockScreenEnabled, keepAwake)
                                },
                                onAodModeChanged = { aodEnabled, roomBrightBoost ->
                                    applyAodDisplayMode(aodEnabled, roomBrightBoost)
                                },
                                onLandscapeModeChanged = { landscapeEnabled ->
                                    applyLandscapeMode(landscapeEnabled)
                                },
                                onRegisterVolumeKeyHandler = { handler ->
                                    volumeKeyHandler = handler
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        AppScreen.CAMERA -> {
                            CameraScreen(
                                profileRepository = profileRepository,
                                attachmentRepository = attachmentRepository,
                                onOpenSettings = {
                                    settingsReturnScreen = AppScreen.CAMERA
                                    currentScreen = AppScreen.SETTINGS
                                },
                                onOpenRemoteControl = { currentScreen = AppScreen.REMOTE },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                profileRepository = profileRepository,
                                attachmentRepository = attachmentRepository,
                                hidManager = hidManager,
                                onRequestBluetoothPermission = requestBluetoothPermissions,
                                onNavigateBack = { currentScreen = settingsReturnScreen },
                                onOpenRemoteControl = { currentScreen = AppScreen.REMOTE },
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
                            val latest = profileRepository.activeProfile.value
                            profileRepository.saveProfile(latest.copy(bluetoothKeyboard = updatedKb))
                        },
                        onRequestPermission = requestBluetoothPermissions,
                        onDismiss = { showRemoteBtSheet = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_REMOTE, false)) {
            openRemoteRequestSeq.intValue++
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val handler = volumeKeyHandler
            if (handler != null) {
                val isDown = event.action == KeyEvent.ACTION_DOWN
                val isUp = event.action == KeyEvent.ACTION_UP
                if ((isDown && event.repeatCount == 0) || isUp) {
                    if (handler(code, isDown)) return true
                } else if (isDown && event.repeatCount > 0) {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyLockScreenMode(enabled: Boolean, keepAwake: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(enabled)
                setTurnScreenOn(enabled)
            } else {
                @Suppress("DEPRECATION")
                val lockFlags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                if (enabled) window.addFlags(lockFlags) else window.clearFlags(lockFlags)
            }
            if (keepAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.onFailure { err ->
            AppDebugLogger.w("APP", "Kilit ekranı pencere bayrakları güncellenemedi", err)
        }
    }

    private fun applyAodDisplayMode(enabled: Boolean, roomBrightBoost: Boolean) {
        runCatching {
            val attrs = window.attributes
            attrs.screenBrightness = if (enabled) {
                if (roomBrightBoost) 0.08f else 0.01f
            } else {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = attrs

            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (enabled) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }.onFailure { err ->
            AppDebugLogger.w("APP", "OLED AOD ekran modu güncellenemedi", err)
        }
    }

    private fun applyLandscapeMode(enabled: Boolean) {
        runCatching {
            requestedOrientation = if (enabled) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }.onFailure { err ->
            AppDebugLogger.w("APP", "Ekran yönü güncellenemedi", err)
        }
    }

    companion object {
        const val EXTRA_OPEN_REMOTE = "extra_open_remote"
    }
}
