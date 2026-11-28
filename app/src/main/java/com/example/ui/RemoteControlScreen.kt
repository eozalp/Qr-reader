package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bluetooth.AirMouseController
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.bluetooth.HidProfileMode
import com.example.debug.AppDebugLogger
import com.example.model.BluetoothKeyboardConfig
import com.example.model.KeyboardEndingKey
import com.example.ui.remote.FullPcKeyboardDeck
import com.example.ui.remote.GyroCalibrationDrawer
import com.example.ui.remote.KeyboardDeckPage
import com.example.ui.remote.MediaRemoteDeckCard
import com.example.ui.remote.ModifierLatchState
import com.example.ui.remote.OledAodRemoteOverlay
import com.example.ui.remote.PcShortcutsRibbon
import com.example.ui.remote.PrecisionTouchpadWithScroll
import com.example.ui.remote.QuickTextAndClipboardBar
import com.example.ui.remote.RemoteGamePadDeck
import com.example.ui.remote.StandaloneNumpadDeck
import com.example.ui.remote.ThreeFingerSwipeDirection
import com.example.ui.remote.ThumbMouseActionBar
import com.example.ui.remote.TouchpadGestureGuideDialog
import com.example.ui.theme.AmberGold
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val REMOTE_PREFS_NAME = "alvetak_remote_prefs"
private const val PREF_TOUCHPAD_SPEED = "touchpad_speed"
private const val PREF_NATURAL_SCROLL = "natural_scroll"
private const val PREF_AIR_SENSITIVITY = "air_sensitivity"
private const val PREF_LOCK_SCREEN_MODE = "lock_screen_enabled"
private const val PREF_VOLUME_KEYS_PC = "volume_keys_pc"
private const val PREF_GAME_VOLUME_TRIGGERS = "game_volume_triggers"
private const val PREF_WIDE_TEN_COL = "wide_ten_col_kbd"

private val AccentViolet = Color(0xFFA78BFA)
private val AccentCoral = Color(0xFFFF4D6D)
private val ObsidianDeep = Color(0xFF070B14)
private val ObsidianSurface = Color(0xFF0D1526)
private val ObsidianBorder = Color(0xFF22314D)

/**
 * 4 ultra-clean, zero-overlap cockpit modes rotatable via Swipe-on-Dock or 1-Tap "Döndür" button:
 * Kumanda (Touchpad + Thumb Air + Media) ↔ Klavye (Mini Pad + Natural-Height Keyboard) ↔ 123 (Pro Numpad) ↔ Oyun (6KRO Gamepad)
 */
enum class RemoteControlTab(
    val title: String,
    val icon: ImageVector,
    val accent: Color
) {
    KUMANDA("Kumanda", Icons.Default.Mouse, NeonCyan),
    KEYBOARD("Klavye", Icons.Default.Keyboard, AccentViolet),
    NUMPAD("123", Icons.Default.Dialpad, AmberGold),
    GAMEPAD("Oyun", Icons.Default.SportsEsports, AccentCoral);

    fun rotated(step: Int): RemoteControlTab {
        val list = entries
        val idx = (ordinal + step).mod(list.size)
        return list[idx]
    }
}

@Composable
fun RemoteControlScreen(
    hidManager: BluetoothHidManager,
    keyboardConfig: BluetoothKeyboardConfig,
    onOpenBluetoothSheet: () -> Unit,
    onOpenQrScanner: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onLockScreenConfigChanged: (lockScreenEnabled: Boolean, keepAwake: Boolean) -> Unit = { _, _ -> },
    onAodModeChanged: (aodEnabled: Boolean, roomBrightBoost: Boolean) -> Unit = { _, _ -> },
    onLandscapeModeChanged: (landscapeEnabled: Boolean) -> Unit = {},
    onRegisterVolumeKeyHandler: (((keyCode: Int, isDown: Boolean) -> Boolean)?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val remotePrefs = remember(context) {
        context.applicationContext.getSharedPreferences(REMOTE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    val connectionStatus by hidManager.connectionStatus.collectAsState()
    val connectedDeviceName by hidManager.connectedDeviceName.collectAsState()
    val hasTelemetryFailure by hidManager.hasTelemetryFailure.collectAsState()
    val hidProfileMode by hidManager.hidProfileMode.collectAsState()
    val lastCrashReport by AppDebugLogger.lastCrashReport.collectAsState()

    // Lifecycle awareness: pause gyroscope/gravity sensors immediately on ON_STOP (screen off / pocket / app switch)
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
                Lifecycle.Event.ON_STOP -> isLifecycleResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Smart battery-saving keep-awake: holds FLAG_KEEP_SCREEN_ON only while actively interacting (90s idle timeout)
    // while keeping showWhenLocked active so waking the phone opens straight into the remote!
    var interactionPulse by remember { mutableIntStateOf(1) }
    var isKeepAwakeHot by remember { mutableStateOf(true) }
    fun markActiveInteraction() {
        isKeepAwakeHot = true
        interactionPulse++
    }
    LaunchedEffect(interactionPulse, connectionStatus) {
        if (connectionStatus == HidConnectionStatus.CONNECTED) {
            isKeepAwakeHot = true
            delay(90_000L)
            isKeepAwakeHot = false
        } else {
            isKeepAwakeHot = false
        }
    }

    var selectedTabName by rememberSaveable { mutableStateOf(RemoteControlTab.KUMANDA.name) }
    var lastSlideDirection by remember { mutableIntStateOf(1) }
    val selectedTab = remember(selectedTabName) {
        RemoteControlTab.entries.firstOrNull { it.name == selectedTabName } ?: RemoteControlTab.KUMANDA
    }

    fun selectTab(next: RemoteControlTab, directionHint: Int? = null) {
        markActiveInteraction()
        if (next == selectedTab) return
        lastSlideDirection = directionHint ?: if (next.ordinal > selectedTab.ordinal) 1 else -1
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        selectedTabName = next.name
    }

    // Persisted power-user preferences
    var touchpadSpeed by remember {
        mutableFloatStateOf(remotePrefs.getFloat(PREF_TOUCHPAD_SPEED, 1.15f).coerceIn(0.6f, 2.5f))
    }
    var isPrecisionMode by remember { mutableStateOf(false) }
    var naturalScroll by remember {
        mutableStateOf(remotePrefs.getBoolean(PREF_NATURAL_SCROLL, false))
    }
    var isAirMouseContinuous by remember { mutableStateOf(false) }
    var isGyroDrawerOpen by remember { mutableStateOf(false) }
    var isMediaDeckOpen by remember { mutableStateOf(false) }
    var airSensitivity by remember {
        mutableFloatStateOf(remotePrefs.getFloat(PREF_AIR_SENSITIVITY, 1.25f).coerceIn(0.5f, 2.5f))
    }
    var isAirHoldPressed by remember { mutableStateOf(false) }
    var isDragLocked by remember { mutableStateOf(false) }
    var showDiagnosticsBar by remember { mutableStateOf(false) }
    var showGestureGuide by remember { mutableStateOf(false) }

    // Lock-Screen, Landscape Gaming & Hardware Volume Key states
    var isLockScreenEnabled by remember {
        mutableStateOf(remotePrefs.getBoolean(PREF_LOCK_SCREEN_MODE, true))
    }
    var isVolumeKeysPcEnabled by remember {
        mutableStateOf(remotePrefs.getBoolean(PREF_VOLUME_KEYS_PC, true))
    }
    var isGameVolumeTriggersEnabled by remember {
        mutableStateOf(remotePrefs.getBoolean(PREF_GAME_VOLUME_TRIGGERS, true))
    }
    var isGameGyroAimEnabled by remember { mutableStateOf(false) }
    var isGameLandscapeLocked by remember { mutableStateOf(false) }
    var isOledAodMode by rememberSaveable { mutableStateOf(false) }
    var isAodRoomBright by rememberSaveable { mutableStateOf(false) }

    // Keyboard & Numpad states
    var keyboardPage by remember { mutableStateOf(KeyboardDeckPage.ALPHA) }
    var useWideTenColLayout by remember {
        mutableStateOf(remotePrefs.getBoolean(PREF_WIDE_TEN_COL, true))
    }
    var isShiftOnce by remember { mutableStateOf(false) }
    var isCapsLock by remember { mutableStateOf(false) }
    var ctrlState by remember { mutableStateOf(ModifierLatchState.OFF) }
    var altState by remember { mutableStateOf(ModifierLatchState.OFF) }
    var winState by remember { mutableStateOf(ModifierLatchState.OFF) }
    var showFunctionRow by remember { mutableStateOf(false) }
    var quickSendBuffer by remember { mutableStateOf("") }
    var numpadTape by remember { mutableStateOf("") }

    // Intercept Back only when an overlay/drawer or landscape lock is active; otherwise let system Back behave naturally (this is now Home!)
    BackHandler(enabled = showGestureGuide || isMediaDeckOpen || isGyroDrawerOpen || isGameLandscapeLocked) {
        when {
            showGestureGuide -> showGestureGuide = false
            isMediaDeckOpen -> isMediaDeckOpen = false
            isGyroDrawerOpen -> isGyroDrawerOpen = false
            isGameLandscapeLocked -> isGameLandscapeLocked = false
        }
    }

    val airMouseController = remember(context) {
        AirMouseController(context) { dx, dy ->
            if (hidManager.isHidHostConnected()) {
                hidManager.sendMouseMove(dx, dy)
            }
        }
    }

    LaunchedEffect(airSensitivity) {
        airMouseController.sensitivity = airSensitivity
    }

    val shouldRunAirMouse = isLifecycleResumed && !isOledAodMode && when (selectedTab) {
        RemoteControlTab.KUMANDA -> isAirMouseContinuous || isAirHoldPressed
        RemoteControlTab.GAMEPAD -> isGameGyroAimEnabled
        else -> false
    }

    val effectiveKeepAwake = isOledAodMode || (
        isLockScreenEnabled &&
            connectionStatus == HidConnectionStatus.CONNECTED &&
            (isKeepAwakeHot || shouldRunAirMouse || selectedTab == RemoteControlTab.GAMEPAD)
        )

    LaunchedEffect(isLockScreenEnabled, isOledAodMode, effectiveKeepAwake) {
        onLockScreenConfigChanged(isLockScreenEnabled || isOledAodMode, effectiveKeepAwake)
    }

    LaunchedEffect(isOledAodMode, isAodRoomBright) {
        onAodModeChanged(isOledAodMode, isAodRoomBright)
    }

    LaunchedEffect(isGameLandscapeLocked, selectedTab, isOledAodMode) {
        onLandscapeModeChanged(!isOledAodMode && selectedTab == RemoteControlTab.GAMEPAD && isGameLandscapeLocked)
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab != RemoteControlTab.KUMANDA) {
            isAirHoldPressed = false
        }
        if (selectedTab != RemoteControlTab.GAMEPAD) {
            isGameLandscapeLocked = false
            hidManager.releaseAllLiveKeys()
            if (!isDragLocked) {
                hidManager.releaseAllMouseButtons()
            }
        }
    }

    LaunchedEffect(shouldRunAirMouse, isAirMouseContinuous, isAirHoldPressed, selectedTab) {
        if (shouldRunAirMouse) {
            airMouseController.isClutchPaused =
                (selectedTab == RemoteControlTab.KUMANDA) && isAirMouseContinuous && isAirHoldPressed
            if (!airMouseController.isRunning) {
                airMouseController.start()
            }
        } else {
            airMouseController.isClutchPaused = false
            airMouseController.stop()
        }
    }

    val hidKeyQueue = remember { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    LaunchedEffect(Unit) {
        for (task in hidKeyQueue) {
            runCatching { task() }
                .onFailure { AppDebugLogger.e("BT-HID", "Kumanda tuş görevi hatası", it) }
        }
    }

    // Route physical Volume keys -> PC Volume (always in OLED AOD!) OR Game Mode L1/R1 Fire & Aim triggers!
    DisposableEffect(selectedTab, isVolumeKeysPcEnabled, isGameVolumeTriggersEnabled, isOledAodMode) {
        val handler: (Int, Boolean) -> Boolean = handler@{ keyCode, isDown ->
            if (!hidManager.isHidHostConnected()) return@handler false

            if (!isOledAodMode && selectedTab == RemoteControlTab.GAMEPAD && isGameVolumeTriggersEnabled) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDown)
                        return@handler true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_RIGHT, isDown)
                        return@handler true
                    }
                }
            } else if (isOledAodMode || isVolumeKeysPcEnabled) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (isDown) {
                            coroutineScope.launch {
                                hidManager.sendConsumerControl(BluetoothHidManager.CONSUMER_VOL_UP)
                            }
                        }
                        return@handler true
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (isDown) {
                            coroutineScope.launch {
                                hidManager.sendConsumerControl(BluetoothHidManager.CONSUMER_VOL_DOWN)
                            }
                        }
                        return@handler true
                    }
                }
            }
            false
        }
        onRegisterVolumeKeyHandler(handler)
        onDispose {
            onRegisterVolumeKeyHandler(null)
        }
    }

    DisposableEffect(Unit) {
        hidManager.refreshState()
        onDispose {
            onRegisterVolumeKeyHandler(null)
            onAodModeChanged(false, false)
            onLockScreenConfigChanged(false, false)
            onLandscapeModeChanged(false)
            hidKeyQueue.close()
            airMouseController.stop()
            hidManager.releaseAllLiveKeys()
            hidManager.releaseAllMouseButtons()
        }
    }

    var lastDisconnectToastMs by remember { mutableLongStateOf(0L) }
    fun notifyDisconnected() {
        val now = System.currentTimeMillis()
        if (now - lastDisconnectToastMs > 1800L) {
            lastDisconnectToastMs = now
            Toast.makeText(context, "Önce üstten PC Bluetooth bağlantısı kurun", Toast.LENGTH_SHORT).show()
        }
    }

    fun currentModifierMask(): Byte {
        var mask = 0
        if (ctrlState.isActive()) mask = mask or BluetoothHidManager.MODIFIER_LEFT_CTRL.toInt()
        if (altState.isActive()) mask = mask or BluetoothHidManager.MODIFIER_LEFT_ALT.toInt()
        if (winState.isActive()) mask = mask or BluetoothHidManager.MODIFIER_LEFT_GUI.toInt()
        return mask.toByte()
    }

    fun consumeOneShotModifiers() {
        ctrlState = ctrlState.consumeIfOneShot()
        altState = altState.consumeIfOneShot()
        winState = winState.consumeIfOneShot()
        if (isShiftOnce && !isCapsLock) {
            isShiftOnce = false
        }
    }

    val trLocale = remember { Locale.forLanguageTag("tr-TR") }

    fun sendCharKey(baseChar: Char, trackInNumpadTape: Boolean = false) {
        markActiveInteraction()
        if (!hidManager.isHidHostConnected()) {
            notifyDisconnected()
            return
        }
        val useUpper = isShiftOnce || isCapsLock
        val effectiveChar = if (useUpper) {
            baseChar.toString().uppercase(trLocale).firstOrNull() ?: baseChar
        } else {
            baseChar
        }
        if (trackInNumpadTape) {
            numpadTape = (numpadTape + effectiveChar).takeLast(24)
        }
        val extraMods = currentModifierMask()
        hidKeyQueue.trySend {
            hidManager.sendSingleCharacter(
                ch = effectiveChar,
                layout = keyboardConfig.keyboardLayout,
                typingDelayMs = keyboardConfig.typingDelayMs,
                extraModifiers = extraMods
            )
        }
        consumeOneShotModifiers()
    }

    fun sendTextString(text: String, endingKey: KeyboardEndingKey = KeyboardEndingKey.NONE) {
        if (text.isEmpty()) return
        if (!hidManager.isHidHostConnected()) {
            notifyDisconnected()
            return
        }
        hidKeyQueue.trySend {
            hidManager.sendToComputer(
                textToSend = text,
                endingKey = endingKey,
                typingDelayMs = keyboardConfig.typingDelayMs,
                convertTurkishChars = keyboardConfig.convertTurkishChars,
                keyboardLayout = keyboardConfig.keyboardLayout
            )
        }
    }

    fun pasteClipboardToPc() {
        if (!hidManager.isHidHostConnected()) {
            notifyDisconnected()
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (clipText.isBlank()) {
            Toast.makeText(context, "Telefon panosu boş", Toast.LENGTH_SHORT).show()
            return
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        sendTextString(clipText, KeyboardEndingKey.NONE)
        Toast.makeText(context, "Panodan ${clipText.length} karakter PC'ye yazılıyor", Toast.LENGTH_SHORT).show()
    }

    fun sendSpecialKey(keyCode: Byte, forceMods: Byte? = null, trackNumpadTape: Boolean = false) {
        markActiveInteraction()
        if (!hidManager.isHidHostConnected()) {
            notifyDisconnected()
            return
        }
        if (trackNumpadTape) {
            when (keyCode) {
                BluetoothHidManager.KEY_BACKSPACE -> numpadTape = numpadTape.dropLast(1)
                BluetoothHidManager.KEY_ENTER, BluetoothHidManager.KEY_ESCAPE -> numpadTape = ""
            }
        }
        val mods = forceMods ?: run {
            var m = currentModifierMask().toInt()
            if (isShiftOnce || isCapsLock) m = m or BluetoothHidManager.MODIFIER_SHIFT.toInt()
            m.toByte()
        }
        hidKeyQueue.trySend {
            hidManager.sendHidKeyCombo(
                keyCode = keyCode,
                modifiers = mods,
                typingDelayMs = keyboardConfig.typingDelayMs
            )
        }
        if (forceMods == null) {
            consumeOneShotModifiers()
        }
    }

    fun handleThreeFingerSwipe(direction: ThreeFingerSwipeDirection) {
        when (direction) {
            ThreeFingerSwipeDirection.LEFT -> sendSpecialKey(BluetoothHidManager.KEY_LEFT, BluetoothHidManager.MODIFIER_LEFT_ALT)
            ThreeFingerSwipeDirection.RIGHT -> sendSpecialKey(BluetoothHidManager.KEY_RIGHT, BluetoothHidManager.MODIFIER_LEFT_ALT)
            ThreeFingerSwipeDirection.UP -> sendSpecialKey(BluetoothHidManager.KEY_TAB, BluetoothHidManager.MODIFIER_LEFT_GUI)
            ThreeFingerSwipeDirection.DOWN -> sendSpecialKey(BluetoothHidManager.KEY_D, BluetoothHidManager.MODIFIER_LEFT_GUI)
        }
    }

    fun handleZoomWheel(steps: Int) {
        hidKeyQueue.trySend {
            hidManager.sendZoomWheel(steps)
        }
    }

    val modeAccent by animateColorAsState(
        targetValue = selectedTab.accent,
        animationSpec = tween(300),
        label = "cockpit_ambient_accent"
    )

    val isConnected = connectionStatus == HidConnectionStatus.CONNECTED
    val hasFailure = hasTelemetryFailure

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianDeep)
            .drawWithCache {
                if (size.width <= 1f || size.height <= 1f) {
                    return@drawWithCache onDrawBehind {}
                }
                val glowBrush = Brush.radialGradient(
                    colors = listOf(modeAccent.copy(alpha = 0.14f), ObsidianDeep),
                    center = Offset(size.width * 0.5f, 0f),
                    radius = (size.maxDimension * 0.62f).coerceAtLeast(1f)
                )
                onDrawBehind {
                    drawRect(brush = glowBrush)
                }
            },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RemoteSlimHudTopBar(
                connectionStatus = connectionStatus,
                connectedDeviceName = connectedDeviceName,
                modeAccent = modeAccent,
                isLockScreenEnabled = isLockScreenEnabled,
                isVolumeControlActive = if (selectedTab == RemoteControlTab.GAMEPAD) {
                    isGameVolumeTriggersEnabled
                } else {
                    isVolumeKeysPcEnabled
                },
                isGameMode = selectedTab == RemoteControlTab.GAMEPAD,
                showDiagnostics = showDiagnosticsBar || hasFailure,
                onOpenQrScanner = onOpenQrScanner,
                onOpenSettings = onOpenSettings,
                onOpenBluetoothSheet = onOpenBluetoothSheet,
                onToggleLockScreen = {
                    markActiveInteraction()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isLockScreenEnabled = !isLockScreenEnabled
                    remotePrefs.edit().putBoolean(PREF_LOCK_SCREEN_MODE, isLockScreenEnabled).apply()
                    Toast.makeText(
                        context,
                        if (isLockScreenEnabled) "Kilit ekranında aktif + akıllı ekran koruması"
                        else "Kilit ekranı modu kapatıldı",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onToggleVolumeKeys = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (selectedTab == RemoteControlTab.GAMEPAD) {
                        isGameVolumeTriggersEnabled = !isGameVolumeTriggersEnabled
                        remotePrefs.edit().putBoolean(PREF_GAME_VOLUME_TRIGGERS, isGameVolumeTriggersEnabled).apply()
                        Toast.makeText(
                            context,
                            if (isGameVolumeTriggersEnabled) "Yan ses tuşları: Ateş / Nişan tetiği aktif"
                            else "Yan ses tuşları: Oyun tetiği kapatıldı",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isVolumeKeysPcEnabled = !isVolumeKeysPcEnabled
                        remotePrefs.edit().putBoolean(PREF_VOLUME_KEYS_PC, isVolumeKeysPcEnabled).apply()
                        Toast.makeText(
                            context,
                            if (isVolumeKeysPcEnabled) "Yan ses tuşları artık PC sesini kontrol eder"
                            else "Yan ses tuşları normale döndü",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onToggleDiagnostics = { showDiagnosticsBar = !showDiagnosticsBar },
                onEnterOledAodMode = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isOledAodMode = true
                },
                onOpenGestureGuide = { showGestureGuide = true }
            )
        },
        bottomBar = {
            SwipeableRotateModeDock(
                selectedTab = selectedTab,
                onSelectTab = { selectTab(it) },
                onRotateStep = { step ->
                    selectTab(selectedTab.rotated(step), directionHint = step)
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Instant 1-tap Crash Recovery Banner (only visible if previous session had an uncaught crash)
            val crashText = lastCrashReport
            AnimatedVisibility(
                visible = !crashText.isNullOrBlank(),
                enter = expandVertically(spring()) + fadeIn(),
                exit = shrinkVertically(spring()) + fadeOut()
            ) {
                val summaryLine = remember(crashText) {
                    crashText
                        .orEmpty()
                        .lineSequence()
                        .firstOrNull { line ->
                            line.contains("Exception") || line.contains("Error")
                        }
                        ?.trim()
                        ?: "Önceki oturumda beklenmeyen hata yakalandı"
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF2B1115),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.75f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_crash_recovery_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Son Çökme Raporu",
                            tint = Color(0xFFFCA5A5),
                            modifier = Modifier.size(16.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Son Çökme Raporu",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFCA5A5),
                                maxLines = 1
                            )
                            Text(
                                text = summaryLine,
                                fontSize = 10.sp,
                                fontFamily = AppMonoFontFamily,
                                color = Color(0xFFFECACA),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEF4444),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("Alvetak Crash Report", crashText.orEmpty()))
                                    Toast.makeText(context, "Çökme raporu panoya kopyalandı", Toast.LENGTH_SHORT).show()
                                }
                                .testTag("remote_crash_copy_btn")
                        ) {
                            Text(
                                text = "Kopyala",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable { AppDebugLogger.clearCrashBanner() }
                                .testTag("remote_crash_dismiss_btn"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Çökme Raporunu Kapat",
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Optional HID Diagnostics & Recovery Drawer (isolated telemetry collector -> 0 root recompositions!)
            AnimatedVisibility(
                visible = isConnected && (hasFailure || showDiagnosticsBar),
                enter = expandVertically(spring()) + fadeIn(),
                exit = shrinkVertically(spring()) + fadeOut()
            ) {
                HidDiagnosticsCard(
                    hidManager = hidManager,
                    hasFailure = hasFailure,
                    onOpenBluetoothSettings = {
                        runCatching { context.startActivity(hidManager.openBluetoothSettingsIntent()) }
                            .onFailure { AppDebugLogger.e("UI", "BT ayarları açılamadı", it) }
                    }
                )
            }

            // Alert banner when user is on Mouse/Game tab while HID profile is in Keyboard-only (SIMPLE_KEYBOARD) mode
            val isSimpleKeyboardBlockingMouse =
                hidProfileMode == HidProfileMode.SIMPLE_KEYBOARD &&
                    (selectedTab == RemoteControlTab.KUMANDA || selectedTab == RemoteControlTab.GAMEPAD)
            AnimatedVisibility(
                visible = isSimpleKeyboardBlockingMouse,
                enter = expandVertically(spring()) + fadeIn(),
                exit = shrinkVertically(spring()) + fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AmberGold.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, AmberGold.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_simple_kb_mouse_warning")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = AmberGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Saf Klavye (id=0) aktif — Mouse & Medya kapalı",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AmberGold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = NeonCyan,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    hidManager.setHidProfileMode(HidProfileMode.COMBO)
                                    Toast.makeText(
                                        context,
                                        "Kombo (Klavye+Fare) moduna geçildi — gerekirse PC'den tekrar bağlanın",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .testTag("remote_switch_to_combo_btn")
                        ) {
                            Text(
                                text = "Kombo Aç",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF00382E),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Smooth Directional Slide+Fade Mode Switcher ("Döndür" transitions!)
            AnimatedContent(
                targetState = selectedTab,
                contentKey = { it.name },
                transitionSpec = {
                    val dir = if (lastSlideDirection >= 0) 1 else -1
                    (slideInHorizontally(animationSpec = tween(220)) { width -> dir * (width / 5) } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(200)) { width -> -dir * (width / 5) } + fadeOut(tween(140))
                        )
                },
                label = "mode_carousel_content",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { tab ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    when (tab) {
                        RemoteControlTab.KUMANDA -> {
                            // Collapsible Gyro Calibration Drawer (hidden by default for maximum Touchpad!)
                            AnimatedVisibility(
                                visible = isGyroDrawerOpen || isAirMouseContinuous,
                                enter = expandVertically(spring()) + fadeIn(),
                                exit = shrinkVertically(spring()) + fadeOut()
                            ) {
                                GyroCalibrationDrawer(
                                    isGyroSupported = airMouseController.isGyroSupported,
                                    isContinuous = isAirMouseContinuous,
                                    sensitivity = airSensitivity,
                                    onToggleContinuous = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        isAirMouseContinuous = !isAirMouseContinuous
                                    },
                                    onSensitivityChanged = { next ->
                                        airSensitivity = next
                                        remotePrefs.edit().putFloat(PREF_AIR_SENSITIVITY, next).apply()
                                    },
                                    onRecalibrate = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        airMouseController.recalibrate()
                                        Toast.makeText(context, "Jiroskop merkezlendi", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            // Collapsible Media & TV Remote Deck (1-tap from Touchpad toolbar!)
                            AnimatedVisibility(
                                visible = isMediaDeckOpen,
                                enter = expandVertically(spring()) + fadeIn(),
                                exit = shrinkVertically(spring()) + fadeOut()
                            ) {
                                MediaRemoteDeckCard(
                                    onConsumer = { usage ->
                                        if (!hidManager.isHidHostConnected()) {
                                            notifyDisconnected()
                                            return@MediaRemoteDeckCard
                                        }
                                        coroutineScope.launch { hidManager.sendConsumerControl(usage) }
                                    },
                                    onFullscreenToggle = { sendSpecialKey(BluetoothHidManager.KEY_F, 0) },
                                    onWindowSwitch = { sendSpecialKey(BluetoothHidManager.KEY_TAB, BluetoothHidManager.MODIFIER_LEFT_ALT) },
                                    onEscape = { sendSpecialKey(BluetoothHidManager.KEY_ESCAPE, 0) }
                                )
                            }

                            // Massive Glass Touchpad (~70% screen!)
                            PrecisionTouchpadWithScroll(
                                hidManager = hidManager,
                                touchpadSpeed = touchpadSpeed,
                                isPrecisionMode = isPrecisionMode,
                                naturalScroll = naturalScroll,
                                isDragLocked = isDragLocked,
                                onSpeedChange = { next ->
                                    touchpadSpeed = next
                                    isPrecisionMode = false
                                    remotePrefs.edit().putFloat(PREF_TOUCHPAD_SPEED, next).apply()
                                },
                                onTogglePrecisionMode = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isPrecisionMode = !isPrecisionMode
                                },
                                onToggleNaturalScroll = {
                                    naturalScroll = !naturalScroll
                                    remotePrefs.edit().putBoolean(PREF_NATURAL_SCROLL, naturalScroll).apply()
                                },
                                onZoomWheel = ::handleZoomWheel,
                                onThreeFingerSwipe = ::handleThreeFingerSwipe,
                                onDisconnectedTap = ::notifyDisconnected,
                                showToolbar = true,
                                isMediaOpen = isMediaDeckOpen,
                                onToggleMediaOpen = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isMediaDeckOpen = !isMediaDeckOpen
                                },
                                isGyroDrawerOpen = isGyroDrawerOpen,
                                isAirContinuous = isAirMouseContinuous,
                                onToggleGyroDrawer = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isGyroDrawerOpen = !isGyroDrawerOpen
                                },
                                onUserInteraction = ::markActiveInteraction,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )

                            // Ergonomic Bottom Thumb Action Bar: [Sol Tık] [🎯 Air Tut] [Sürükle] [Sağ Tık]
                            ThumbMouseActionBar(
                                isDragLocked = isDragLocked,
                                isGyroSupported = airMouseController.isGyroSupported,
                                isAirContinuous = isAirMouseContinuous,
                                isAirHoldPressed = isAirHoldPressed,
                                onLeftDownChange = { isDown ->
                                    if (!hidManager.isHidHostConnected()) {
                                        if (isDown) notifyDisconnected()
                                        return@ThumbMouseActionBar
                                    }
                                    if (isDown && isDragLocked) isDragLocked = false
                                    hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDown)
                                },
                                onAirHoldPressChange = { isDown ->
                                    if (isDown && !hidManager.isHidHostConnected()) notifyDisconnected()
                                    isAirHoldPressed = isDown
                                },
                                onToggleDragLock = {
                                    if (!hidManager.isHidHostConnected()) {
                                        notifyDisconnected()
                                        return@ThumbMouseActionBar
                                    }
                                    isDragLocked = !isDragLocked
                                    hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDragLocked)
                                },
                                onRightDownChange = { isDown ->
                                    if (!hidManager.isHidHostConnected()) {
                                        if (isDown) notifyDisconnected()
                                        return@ThumbMouseActionBar
                                    }
                                    hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_RIGHT, isDown)
                                }
                            )
                        }

                        RemoteControlTab.KEYBOARD -> {
                            // Upper Flexible Space: Live Mini-Touchpad + Native Typing + Shortcuts!
                            // Lower Space: Fixed Natural-Height Ergonomic Keyboard (NO skyscraper keys!)
                            PrecisionTouchpadWithScroll(
                                hidManager = hidManager,
                                touchpadSpeed = touchpadSpeed,
                                isPrecisionMode = isPrecisionMode,
                                naturalScroll = naturalScroll,
                                isDragLocked = isDragLocked,
                                onSpeedChange = { next ->
                                    touchpadSpeed = next
                                    isPrecisionMode = false
                                    remotePrefs.edit().putFloat(PREF_TOUCHPAD_SPEED, next).apply()
                                },
                                onTogglePrecisionMode = { isPrecisionMode = !isPrecisionMode },
                                onToggleNaturalScroll = {
                                    naturalScroll = !naturalScroll
                                    remotePrefs.edit().putBoolean(PREF_NATURAL_SCROLL, naturalScroll).apply()
                                },
                                onZoomWheel = ::handleZoomWheel,
                                onThreeFingerSwipe = ::handleThreeFingerSwipe,
                                onDisconnectedTap = ::notifyDisconnected,
                                showToolbar = false,
                                compactHint = true,
                                onUserInteraction = ::markActiveInteraction,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .heightIn(min = 84.dp)
                            )

                            QuickTextAndClipboardBar(
                                textValue = quickSendBuffer,
                                onTextChanged = { quickSendBuffer = it },
                                onPasteClipboardToPc = ::pasteClipboardToPc,
                                onSendText = { appendEnter ->
                                    val text = quickSendBuffer
                                    if (text.isEmpty()) return@QuickTextAndClipboardBar
                                    if (!hidManager.isHidHostConnected()) {
                                        notifyDisconnected()
                                        return@QuickTextAndClipboardBar
                                    }
                                    quickSendBuffer = ""
                                    sendTextString(
                                        text = text,
                                        endingKey = if (appendEnter) KeyboardEndingKey.ENTER else KeyboardEndingKey.NONE
                                    )
                                }
                            )

                            PcShortcutsRibbon(
                                showFunctionRow = showFunctionRow,
                                onToggleFunctionRow = { showFunctionRow = !showFunctionRow },
                                useWideTenColLayout = useWideTenColLayout,
                                onToggleWideLayout = {
                                    useWideTenColLayout = !useWideTenColLayout
                                    remotePrefs.edit().putBoolean(PREF_WIDE_TEN_COL, useWideTenColLayout).apply()
                                },
                                onSpecialKey = { code, mods -> sendSpecialKey(code, mods) }
                            )

                            FullPcKeyboardDeck(
                                activePage = keyboardPage,
                                useWideTenColLayout = useWideTenColLayout,
                                isShiftOnce = isShiftOnce,
                                isCapsLock = isCapsLock,
                                ctrlState = ctrlState,
                                altState = altState,
                                winState = winState,
                                showFunctionRow = showFunctionRow,
                                onSelectPage = { keyboardPage = it },
                                onTapShift = {
                                    if (isCapsLock) {
                                        isCapsLock = false
                                        isShiftOnce = false
                                    } else if (isShiftOnce) {
                                        isCapsLock = true
                                    } else {
                                        isShiftOnce = true
                                    }
                                },
                                onTapCtrl = { ctrlState = ctrlState.nextTap() },
                                onTapAlt = { altState = altState.nextTap() },
                                onTapWin = { winState = winState.nextTap() },
                                onCharKey = { sendCharKey(it) },
                                onSpecialKey = { code, mods -> sendSpecialKey(code, mods) }
                            )
                        }

                        RemoteControlTab.NUMPAD -> {
                            StandaloneNumpadDeck(
                                recentTape = numpadTape,
                                onClearTape = { numpadTape = "" },
                                onPasteClipboardToPc = ::pasteClipboardToPc,
                                onCharKey = { sendCharKey(it, trackInNumpadTape = true) },
                                onStringKeys = { str ->
                                    numpadTape = (numpadTape + str).takeLast(24)
                                    sendTextString(str)
                                },
                                onSpecialKey = { code, mods -> sendSpecialKey(code, mods, trackNumpadTape = true) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }

                        RemoteControlTab.GAMEPAD -> {
                            RemoteGamePadDeck(
                                hidManager = hidManager,
                                isGyroAimEnabled = isGameGyroAimEnabled,
                                isLandscapeLocked = isGameLandscapeLocked,
                                onToggleGyroAim = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (!airMouseController.isGyroSupported) {
                                        Toast.makeText(context, "Bu cihazda jiroskop sensörü yok", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isGameGyroAimEnabled = !isGameGyroAimEnabled
                                        if (isGameGyroAimEnabled) {
                                            airMouseController.recalibrate()
                                        }
                                    }
                                },
                                onToggleLandscape = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    isGameLandscapeLocked = !isGameLandscapeLocked
                                },
                                onDisconnectedTap = ::notifyDisconnected,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGestureGuide && !isOledAodMode) {
        TouchpadGestureGuideDialog(onDismiss = { showGestureGuide = false })
    }

    AnimatedVisibility(
        visible = isOledAodMode,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180))
    ) {
        OledAodRemoteOverlay(
            hidManager = hidManager,
            connectionStatus = connectionStatus,
            connectedDeviceName = connectedDeviceName,
            touchpadSpeed = if (isPrecisionMode) 0.45f else touchpadSpeed,
            naturalScroll = naturalScroll,
            isRoomBrightBoost = isAodRoomBright,
            onToggleBrightnessBoost = { isAodRoomBright = !isAodRoomBright },
            onConsumerControl = { usage ->
                if (!hidManager.isHidHostConnected()) {
                    notifyDisconnected()
                    return@OledAodRemoteOverlay
                }
                coroutineScope.launch { hidManager.sendConsumerControl(usage) }
            },
            onZoomWheel = ::handleZoomWheel,
            onThreeFingerSwipe = ::handleThreeFingerSwipe,
            onFullscreenToggle = { sendSpecialKey(BluetoothHidManager.KEY_F, 0) },
            onOpenBluetoothSheet = onOpenBluetoothSheet,
            onDisconnectedTap = ::notifyDisconnected,
            onExitAodMode = {
                markActiveInteraction()
                isOledAodMode = false
            }
        )
    }
    }
}

@Composable
private fun HidDiagnosticsCard(
    hidManager: BluetoothHidManager,
    hasFailure: Boolean,
    onOpenBluetoothSettings: () -> Unit
) {
    val hidTelemetry by hidManager.telemetry.collectAsState()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (hasFailure) Color(0xFF2B1115) else Color(0xFF0E1728),
        border = BorderStroke(
            1.dp,
            if (hasFailure) Color(0xFFEF4444).copy(alpha = 0.7f) else Color(0xFF223350)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${if (hidTelemetry.isBootMode) "Boot" else "Report"} · Klavye ${hidTelemetry.keyboardReportsSent} · Mouse ${hidTelemetry.mouseReportsSent}",
                    fontSize = 10.sp,
                    fontFamily = AppMonoFontFamily,
                    color = if (hasFailure) Color(0xFFFCA5A5) else NeonCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HID Yenile",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { hidManager.reregisterHidProfile() }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("remote_hid_reregister_btn")
                    )
                    Text(
                        text = "BT Ayarları",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmberGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenBluetoothSettings)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            if (hasFailure) {
                Text(
                    text = hidTelemetry.lastFailureReason.orEmpty(),
                    fontSize = 10.sp,
                    color = Color(0xFFFCA5A5)
                )
            }
        }
    }
}

/**
 * Battery-friendly status orb: ONLY runs an infinite pulse animation while actively CONNECTING,
 * using draw-phase [graphicsLayer] alpha (zero recompositions). When idle (CONNECTED/DISCONNECTED),
 * renders statically so LTPO 120Hz OLED displays immediately step down to 10–24Hz!
 */
@Composable
private fun ConnectionStatusOrb(
    isConnecting: Boolean,
    color: Color
) {
    if (isConnecting) {
        val pulseTransition = rememberInfiniteTransition(label = "bt_orb_pulse")
        val orbAlpha by pulseTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(850),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bt_orb_alpha"
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = orbAlpha }
                .clip(CircleShape)
                .background(color)
        )
    } else {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * Single-row, zero-duplication Cockpit HUD Top Bar:
 * [QR] [PC Bağla / Connected PC Orb] [🔒] [🔊/🎮] [⚙] [?]
 * PC Bluetooth is the centerpiece; QR Scanner is 1 tap away as a clean side feature.
 */
@Composable
private fun RemoteSlimHudTopBar(
    connectionStatus: HidConnectionStatus,
    connectedDeviceName: String?,
    modeAccent: Color,
    isLockScreenEnabled: Boolean,
    isVolumeControlActive: Boolean,
    isGameMode: Boolean,
    showDiagnostics: Boolean,
    onOpenQrScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBluetoothSheet: () -> Unit,
    onToggleLockScreen: () -> Unit,
    onToggleVolumeKeys: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onEnterOledAodMode: () -> Unit,
    onOpenGestureGuide: () -> Unit
) {
    val isConnected = connectionStatus == HidConnectionStatus.CONNECTED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Side Feature: 1-Tap QR Scanner Launch
        HudQuickToggleButton(
            icon = Icons.Default.QrCodeScanner,
            contentDescription = "QR Okuyucu",
            isActive = false,
            activeColor = NeonCyan,
            onClick = onOpenQrScanner,
            modifier = Modifier.testTag("remote_qr_scanner_btn")
        )

        // Glowing Bluetooth PC Connection Pill (Zero duplicate second banner!)
        val pillAccent = if (isConnected) NeonCyan else AmberGold
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = if (isConnected) Color(0xFF0E1C2E) else Color(0xFF251C08),
            border = BorderStroke(
                width = if (isConnected) 1.dp else 1.2.dp,
                color = pillAccent.copy(alpha = if (isConnected) 0.6f else 0.85f)
            ),
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onOpenBluetoothSheet)
                .testTag("remote_bt_status_btn")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ConnectionStatusOrb(
                    isConnecting = connectionStatus == HidConnectionStatus.CONNECTING,
                    color = pillAccent
                )
                Text(
                    text = when (connectionStatus) {
                        HidConnectionStatus.CONNECTED -> connectedDeviceName ?: "Bilgisayar"
                        HidConnectionStatus.CONNECTING -> "Bağlanıyor..."
                        else -> "PC Bağla"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isConnected) Color.White else AmberGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.Bluetooth,
                    contentDescription = "Bluetooth Bağlantısı",
                    tint = pillAccent,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Lock-Screen + Keep-Awake Toggle
        HudQuickToggleButton(
            icon = if (isLockScreenEnabled) Icons.Default.LockOpen else Icons.Default.Lock,
            contentDescription = if (isLockScreenEnabled) "Kilit Ekranında Aktif" else "Kilit Ekranı Modu Kapalı",
            isActive = isLockScreenEnabled,
            activeColor = NeonCyan,
            onClick = onToggleLockScreen,
            modifier = Modifier.testTag("remote_lockscreen_toggle_btn")
        )

        // Hardware Volume Keys -> PC Volume / Game Trigger Toggle
        HudQuickToggleButton(
            icon = if (isGameMode && isVolumeControlActive) Icons.Default.SportsEsports else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (isGameMode) "Ses Tuşları Oyun Tetiği" else "Ses Tuşları PC Sesi",
            isActive = isVolumeControlActive,
            activeColor = if (isGameMode) AccentCoral else AmberGold,
            onClick = onToggleVolumeKeys,
            modifier = Modifier.testTag("remote_volume_keys_toggle_btn")
        )

        // OLED Always-On Remote Mode (True-Black Dimmed Blind Touchpad + Media)
        HudQuickToggleButton(
            icon = Icons.Default.Bedtime,
            contentDescription = "OLED AOD Kumanda Modu",
            isActive = false,
            activeColor = NeonCyan,
            onClick = onEnterOledAodMode,
            modifier = Modifier.testTag("remote_oled_aod_btn")
        )

        // Connected Diagnostics OR Settings
        if (isConnected) {
            HudQuickToggleButton(
                icon = Icons.Default.Tune,
                contentDescription = "HID Durum ve Yenileme",
                isActive = showDiagnostics,
                activeColor = modeAccent,
                onClick = onToggleDiagnostics,
                modifier = Modifier.testTag("remote_diagnostics_toggle_btn")
            )
        } else {
            HudQuickToggleButton(
                icon = Icons.Default.Settings,
                contentDescription = "Ayarlar",
                isActive = false,
                activeColor = modeAccent,
                onClick = onOpenSettings,
                modifier = Modifier.testTag("remote_settings_btn")
            )
        }

        // Single Gesture & Shortcuts Guide icon
        HudQuickToggleButton(
            icon = Icons.Default.HelpOutline,
            contentDescription = "Hareket Rehberi",
            isActive = false,
            activeColor = modeAccent,
            onClick = onOpenGestureGuide,
            modifier = Modifier.testTag("remote_help_guide_btn")
        )
    }
}

@Composable
private fun HudQuickToggleButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.2f) else Color(0xFF121C2F),
        label = "hud_toggle_bg"
    )
    val border by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.85f) else ObsidianBorder,
        label = "hud_toggle_border"
    )
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) activeColor else Color(0xFF94A3B8),
            modifier = Modifier.size(17.dp)
        )
    }
}

/**
 * Swipe-to-Rotate ("Döndür") Floating Glassmorphic Bottom 4-Mode Dock:
 * - Swipe left/right anywhere along the dock pill to smoothly rotate Kumanda ↔ Klavye ↔ 123 ↔ Oyun!
 * - Or tap any mode directly, OR tap the circular ⟲ Döndür button on the right to instant-cycle with 1 thumb!
 */
@Composable
private fun SwipeableRotateModeDock(
    selectedTab: RemoteControlTab,
    onSelectTab: (RemoteControlTab) -> Unit,
    onRotateStep: (Int) -> Unit
) {
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 42.dp.toPx() }
    val latestOnRotate by rememberUpdatedState(onRotateStep)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        ObsidianDeep.copy(alpha = 0.94f),
                        ObsidianDeep
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ObsidianSurface.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, ObsidianBorder),
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(swipeThresholdPx) {
                    var dragAccum = 0f
                    var triggered = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccum = 0f
                            triggered = false
                        },
                        onDragEnd = {
                            dragAccum = 0f
                            triggered = false
                        },
                        onDragCancel = {
                            dragAccum = 0f
                            triggered = false
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        if (triggered) return@detectHorizontalDragGestures
                        dragAccum += dragAmount
                        if (dragAccum >= swipeThresholdPx) {
                            triggered = true
                            latestOnRotate(1)
                        } else if (dragAccum <= -swipeThresholdPx) {
                            triggered = true
                            latestOnRotate(-1)
                        }
                    }
                }
                .testTag("remote_swipe_mode_dock")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RemoteControlTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    val itemBg by animateColorAsState(
                        targetValue = if (isSelected) tab.accent.copy(alpha = 0.18f) else Color.Transparent,
                        animationSpec = spring(),
                        label = "dock_bg_${tab.name}"
                    )
                    val itemBorder by animateColorAsState(
                        targetValue = if (isSelected) tab.accent.copy(alpha = 0.78f) else Color.Transparent,
                        animationSpec = spring(),
                        label = "dock_border_${tab.name}"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.12f else 1f,
                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                        label = "dock_scale_${tab.name}"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(itemBg)
                            .border(1.dp, itemBorder, RoundedCornerShape(18.dp))
                            .clickable { onSelectTab(tab) }
                            .testTag("remote_tab_${tab.name.lowercase(Locale.ROOT)}")
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.title,
                            tint = if (isSelected) tab.accent else Color(0xFF8396B2),
                            modifier = Modifier
                                .size(19.dp)
                                .scale(iconScale)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = tab.title,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isSelected) tab.accent else Color(0xFF94A3B8),
                            maxLines = 1
                        )
                    }
                }

                // 1-Tap Tactile "Döndür" (Mode Cycle) Thumb Button
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(selectedTab.accent.copy(alpha = 0.16f))
                        .border(1.dp, selectedTab.accent.copy(alpha = 0.65f), CircleShape)
                        .clickable { onRotateStep(1) }
                        .testTag("remote_rotate_mode_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = "Modu Döndür",
                        tint = selectedTab.accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
