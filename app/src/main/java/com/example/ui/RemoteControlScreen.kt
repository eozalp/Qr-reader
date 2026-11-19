package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.AirMouseController
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.model.BluetoothKeyboardConfig
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardLayoutType
import com.example.ui.theme.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot

enum class RemoteControlTab(val title: String, val icon: ImageVector) {
    MOUSE_AIR("Mouse & Air", Icons.Default.Mouse),
    COMBO("Karma", Icons.Default.Devices),
    KEYBOARD("Tam Klavye", Icons.Default.Keyboard)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(
    hidManager: BluetoothHidManager,
    keyboardConfig: BluetoothKeyboardConfig,
    onOpenBluetoothSheet: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigateBack()
    }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    val connectionStatus by hidManager.connectionStatus.collectAsState()
    val connectedDeviceName by hidManager.connectedDeviceName.collectAsState()
    val hidTelemetry by hidManager.telemetry.collectAsState()

    var selectedTab by remember { mutableStateOf(RemoteControlTab.MOUSE_AIR) }
    var isAirMouseContinuous by remember { mutableStateOf(false) }
    var airSensitivity by remember { mutableFloatStateOf(1.25f) }
    var isDragLocked by remember { mutableStateOf(false) }
    var showRepairHint by remember { mutableStateOf(false) }

    // Raw zero-latency hold-to-aim / clutch state
    var isAirHoldPressed by remember { mutableStateOf(false) }

    // Full PC Keyboard States
    var isSymbolsPage by remember { mutableStateOf(false) }
    var isShiftOnce by remember { mutableStateOf(false) }
    var isCapsLock by remember { mutableStateOf(false) }
    var isCtrlSticky by remember { mutableStateOf(false) }
    var isAltSticky by remember { mutableStateOf(false) }
    var isWinSticky by remember { mutableStateOf(false) }
    var showFunctionRow by remember { mutableStateOf(true) }
    var quickSendBuffer by remember { mutableStateOf("") }

    val airMouseController = remember(context) {
        AirMouseController(context) { dx, dy ->
            if (hidManager.isHidHostConnected()) {
                hidManager.sendMouseMove(dx, dy)
            }
        }
    }

    // Keep sensitivity & running state synchronized with lifecycle
    LaunchedEffect(airSensitivity) {
        airMouseController.sensitivity = airSensitivity
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == RemoteControlTab.KEYBOARD) {
            isAirHoldPressed = false
        }
    }

    val shouldRunAirMouse = (selectedTab != RemoteControlTab.KEYBOARD) &&
        (isAirMouseContinuous || isAirHoldPressed)

    LaunchedEffect(shouldRunAirMouse, isAirMouseContinuous, isAirHoldPressed) {
        if (shouldRunAirMouse) {
            airMouseController.isClutchPaused = isAirMouseContinuous && isAirHoldPressed
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
        }
    }

    DisposableEffect(Unit) {
        hidManager.refreshState()
        onDispose {
            hidKeyQueue.close()
            airMouseController.stop()
            hidManager.releaseAllMouseButtons()
        }
    }

    var lastDisconnectToastMs by remember { mutableLongStateOf(0L) }
    fun notifyDisconnected() {
        val now = System.currentTimeMillis()
        if (now - lastDisconnectToastMs > 1800L) {
            lastDisconnectToastMs = now
            android.widget.Toast.makeText(
                context,
                "Önce PC Bluetooth bağlantısı kurun",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun currentModifierMask(): Byte {
        var mask = 0
        if (isCtrlSticky) mask = mask or BluetoothHidManager.MODIFIER_LEFT_CTRL.toInt()
        if (isAltSticky) mask = mask or BluetoothHidManager.MODIFIER_LEFT_ALT.toInt()
        if (isWinSticky) mask = mask or BluetoothHidManager.MODIFIER_LEFT_GUI.toInt()
        return mask.toByte()
    }

    fun consumeOneShotModifiers() {
        isCtrlSticky = false
        isAltSticky = false
        isWinSticky = false
        if (isShiftOnce && !isCapsLock) {
            isShiftOnce = false
        }
    }

    fun sendCharKey(baseChar: Char) {
        if (!hidManager.isHidHostConnected()) {
            notifyDisconnected()
            return
        }
        val useUpper = isShiftOnce || isCapsLock
        val effectiveChar = if (useUpper) baseChar.toString().uppercase(Locale("tr", "TR")).firstOrNull() ?: baseChar else baseChar
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

    fun sendSpecialKey(keyCode: Byte, forceMods: Byte? = null) {
        if (!hidManager.isHidHostConnected()) {
            notifyDisconnected()
            return
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF090D16),
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color(0xFF0D1422))
                    .fillMaxWidth()
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "PC Kumanda & Air Mouse",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Text(
                                text = when (connectionStatus) {
                                    HidConnectionStatus.CONNECTED -> "Bağlı: ${connectedDeviceName ?: "Bilgisayar"}"
                                    HidConnectionStatus.CONNECTING -> "Bağlanıyor..."
                                    else -> "Bağlı Değil — Dokunup Bağlanın"
                                },
                                fontSize = 11.sp,
                                color = if (connectionStatus == HidConnectionStatus.CONNECTED) NeonCyan else AmberGold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("remote_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kameraya Dön",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (connectionStatus == HidConnectionStatus.CONNECTED) {
                                NeonCyan.copy(alpha = 0.16f)
                            } else {
                                AmberGold.copy(alpha = 0.16f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (connectionStatus == HidConnectionStatus.CONNECTED) NeonCyan else AmberGold
                            ),
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenBluetoothSheet() }
                                .testTag("remote_bt_status_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = "Bluetooth Bağlantısı",
                                    tint = if (connectionStatus == HidConnectionStatus.CONNECTED) NeonCyan else AmberGold,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (connectionStatus == HidConnectionStatus.CONNECTED) "Bağlı" else "Bağlan",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (connectionStatus == HidConnectionStatus.CONNECTED) NeonCyan else AmberGold
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0D1422)
                    )
                )

                // Segmented Mode Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RemoteControlTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) NeonCyan.copy(alpha = 0.16f) else Color(0xFF151E31),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) NeonCyan else Color(0xFF25324B)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { selectedTab = tab }
                                .testTag("remote_tab_${tab.name.lowercase()}")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) NeonCyan else Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = tab.title,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (isSelected) NeonCyan else Color(0xFFCBD5E1),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (connectionStatus != HidConnectionStatus.CONNECTED) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF2B2108),
                    border = BorderStroke(1.dp, AmberGold.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenBluetoothSheet() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Bilgisayar bağlı değil. Eşleşmiş PC'ye bağlanmak için dokunun.",
                                fontSize = 11.5.sp,
                                color = Color.White
                            )
                        }
                        Text("Bağlan →", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AmberGold)
                    }
                }
            } else {
                val hasFailure = !hidTelemetry.lastFailureReason.isNullOrBlank()
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (hasFailure) Color(0xFF2B1115) else Color(0xFF0E1728),
                    border = BorderStroke(
                        1.dp,
                        if (hasFailure) Color(0xFFEF4444).copy(alpha = 0.7f) else Color(0xFF1F2D45)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${if (hidTelemetry.isBootMode) "Boot" else "Report"} · Klavye ${hidTelemetry.keyboardReportsSent} · Mouse ${hidTelemetry.mouseReportsSent}",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
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
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("remote_hid_reregister_btn")
                                )
                                Text(
                                    text = if (showRepairHint) "Gizle" else "Yardım?",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AmberGold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showRepairHint = !showRepairHint }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (hasFailure || showRepairHint) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = hidTelemetry.lastFailureReason
                                        ?: "Mouse yanıt vermiyorsa PC Bluetooth'tan cihazı 'Kaldır' deyip bir kez yeniden eşleştirin (Windows eski klavye SDP'sini önbellekte tutar).",
                                    fontSize = 10.sp,
                                    color = if (hasFailure) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "BT Ayarları",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberGold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            runCatching { context.startActivity(hidManager.openBluetoothSettingsIntent()) }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            when (selectedTab) {
                RemoteControlTab.MOUSE_AIR -> {
                    // Air Mouse Card
                    AirMouseCard(
                        isGyroSupported = airMouseController.isGyroSupported,
                        isContinuous = isAirMouseContinuous,
                        isHoldPressed = isAirHoldPressed,
                        sensitivity = airSensitivity,
                        onHoldPressChange = { isDown ->
                            if (isDown && !hidManager.isHidHostConnected()) {
                                notifyDisconnected()
                            }
                            isAirHoldPressed = isDown
                        },
                        onToggleContinuous = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isAirMouseContinuous = !isAirMouseContinuous
                        },
                        onSensitivityChanged = { airSensitivity = it }
                    )

                    // Full Touchpad + Scroll Strip
                    PrecisionTouchpadWithScroll(
                        hidManager = hidManager,
                        onDisconnectedTap = ::notifyDisconnected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    // Hardware Mouse Buttons Row (Left, Drag Lock, Middle, Right)
                    MouseButtonBar(
                        isDragLocked = isDragLocked,
                        onLeftDownChange = { isDown ->
                            if (!hidManager.isHidHostConnected()) {
                                if (isDown) notifyDisconnected()
                                return@MouseButtonBar
                            }
                            if (isDown && isDragLocked) isDragLocked = false
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDown)
                        },
                        onToggleDragLock = {
                            if (!hidManager.isHidHostConnected()) {
                                notifyDisconnected()
                                return@MouseButtonBar
                            }
                            isDragLocked = !isDragLocked
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDragLocked)
                        },
                        onMiddleDownChange = { isDown ->
                            if (!hidManager.isHidHostConnected()) {
                                if (isDown) notifyDisconnected()
                                return@MouseButtonBar
                            }
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_MIDDLE, isDown)
                        },
                        onRightDownChange = { isDown ->
                            if (!hidManager.isHidHostConnected()) {
                                if (isDown) notifyDisconnected()
                                return@MouseButtonBar
                            }
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_RIGHT, isDown)
                        }
                    )

                    // Media & Volume Bar (Consumer HID)
                    ConsumerMediaStrip(
                        onConsumer = { usage ->
                            if (!hidManager.isHidHostConnected()) {
                                notifyDisconnected()
                                return@ConsumerMediaStrip
                            }
                            coroutineScope.launch { hidManager.sendConsumerControl(usage) }
                        }
                    )
                }

                RemoteControlTab.COMBO -> {
                    // Compact Air Mouse Strip + Touchpad on Top Half
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = isAirMouseContinuous,
                            onClick = {
                                if (airMouseController.isGyroSupported) {
                                    isAirMouseContinuous = !isAirMouseContinuous
                                    if (isAirMouseContinuous && !hidManager.isHidHostConnected()) {
                                        notifyDisconnected()
                                    }
                                }
                            },
                            label = {
                                Text(
                                    text = if (!airMouseController.isGyroSupported) "Jiroskop Yok"
                                    else if (isAirMouseContinuous) "✈ Air: AÇIK"
                                    else "✈ Air Mouse",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            enabled = airMouseController.isGyroSupported,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = Color(0xFF00382E)
                            )
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAirHoldPressed) AmberGold else Color(0xFF151E31),
                            border = BorderStroke(1.dp, if (isAirHoldPressed) AmberGold else Color(0xFF2A3955)),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .pointerInput(airMouseController.isGyroSupported) {
                                    if (!airMouseController.isGyroSupported) return@pointerInput
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        if (!hidManager.isHidHostConnected()) {
                                            notifyDisconnected()
                                        }
                                        isAirHoldPressed = true
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.changes.none { it.pressed }) break
                                            }
                                        } finally {
                                            isAirHoldPressed = false
                                        }
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isAirMouseContinuous && isAirHoldPressed) "⏸ Duraklatıldı"
                                    else if (isAirMouseContinuous) "⏸ Basılı Tut = Duraklat"
                                    else if (isAirHoldPressed) "🎯 Bileği Oynatın"
                                    else "🎯 Tut & Air Mouse",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isAirHoldPressed) Color(0xFF042F2E) else Color.White
                                )
                            }
                        }
                    }

                    PrecisionTouchpadWithScroll(
                        hidManager = hidManager,
                        onDisconnectedTap = ::notifyDisconnected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.95f)
                    )

                    MouseButtonBar(
                        isDragLocked = isDragLocked,
                        onLeftDownChange = { isDown ->
                            if (!hidManager.isHidHostConnected()) {
                                if (isDown) notifyDisconnected()
                                return@MouseButtonBar
                            }
                            if (isDown && isDragLocked) isDragLocked = false
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDown)
                        },
                        onToggleDragLock = {
                            if (!hidManager.isHidHostConnected()) {
                                notifyDisconnected()
                                return@MouseButtonBar
                            }
                            isDragLocked = !isDragLocked
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, isDragLocked)
                        },
                        onMiddleDownChange = { isDown ->
                            if (!hidManager.isHidHostConnected()) {
                                if (isDown) notifyDisconnected()
                                return@MouseButtonBar
                            }
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_MIDDLE, isDown)
                        },
                        onRightDownChange = { isDown ->
                            if (!hidManager.isHidHostConnected()) {
                                if (isDown) notifyDisconnected()
                                return@MouseButtonBar
                            }
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_RIGHT, isDown)
                        }
                    )

                    // Compact PC Keyboard on Bottom Half
                    FullPcKeyboardDeck(
                        isCompact = true,
                        isSymbolsPage = isSymbolsPage,
                        isShiftOnce = isShiftOnce,
                        isCapsLock = isCapsLock,
                        isCtrlSticky = isCtrlSticky,
                        isAltSticky = isAltSticky,
                        isWinSticky = isWinSticky,
                        showFunctionRow = false,
                        onToggleSymbols = { isSymbolsPage = !isSymbolsPage },
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
                        onToggleCtrl = { isCtrlSticky = !isCtrlSticky },
                        onToggleAlt = { isAltSticky = !isAltSticky },
                        onToggleWin = { isWinSticky = !isWinSticky },
                        onCharKey = ::sendCharKey,
                        onSpecialKey = ::sendSpecialKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.15f)
                    )
                }

                RemoteControlTab.KEYBOARD -> {
                    // Quick Batch Text / URL Sender
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = quickSendBuffer,
                            onValueChange = { quickSendBuffer = it },
                            placeholder = { Text("Uzun metin veya URL yapıştırıp PC'ye gönderin", fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = Color(0xFF28354E),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val text = quickSendBuffer
                                if (text.isEmpty()) return@Button
                                if (!hidManager.isHidHostConnected()) {
                                    notifyDisconnected()
                                    return@Button
                                }
                                quickSendBuffer = ""
                                hidKeyQueue.trySend {
                                    hidManager.sendToComputer(
                                        textToSend = text,
                                        endingKey = KeyboardEndingKey.NONE,
                                        typingDelayMs = keyboardConfig.typingDelayMs,
                                        convertTurkishChars = keyboardConfig.convertTurkishChars,
                                        keyboardLayout = keyboardConfig.keyboardLayout
                                    )
                                }
                            },
                            enabled = quickSendBuffer.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF00382E)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "PC'ye Yaz", modifier = Modifier.size(18.dp))
                        }
                    }

                    // PC Shortcuts Row (Esc, F1..F12 toggle, Copy/Paste/Undo/SelectAll/Save/Alt+Tab/Win+D)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShortcutChip("F1–F12", isActive = showFunctionRow) {
                            showFunctionRow = !showFunctionRow
                        }
                        ShortcutChip("Esc") { sendSpecialKey(BluetoothHidManager.KEY_ESCAPE, 0) }
                        ShortcutChip("Ctrl+C") { sendSpecialKey(BluetoothHidManager.KEY_C, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
                        ShortcutChip("Ctrl+V") { sendSpecialKey(BluetoothHidManager.KEY_V, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
                        ShortcutChip("Ctrl+X") { sendSpecialKey(BluetoothHidManager.KEY_X, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
                        ShortcutChip("Ctrl+Z") { sendSpecialKey(BluetoothHidManager.KEY_Z, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
                        ShortcutChip("Ctrl+A") { sendSpecialKey(BluetoothHidManager.KEY_A, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
                        ShortcutChip("Ctrl+S") { sendSpecialKey(BluetoothHidManager.KEY_S, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
                        ShortcutChip("Alt+Tab") { sendSpecialKey(BluetoothHidManager.KEY_TAB, BluetoothHidManager.MODIFIER_LEFT_ALT) }
                        ShortcutChip("Win+D") { sendSpecialKey(BluetoothHidManager.KEY_D, BluetoothHidManager.MODIFIER_LEFT_GUI) }
                    }

                    FullPcKeyboardDeck(
                        isCompact = false,
                        isSymbolsPage = isSymbolsPage,
                        isShiftOnce = isShiftOnce,
                        isCapsLock = isCapsLock,
                        isCtrlSticky = isCtrlSticky,
                        isAltSticky = isAltSticky,
                        isWinSticky = isWinSticky,
                        showFunctionRow = showFunctionRow,
                        onToggleSymbols = { isSymbolsPage = !isSymbolsPage },
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
                        onToggleCtrl = { isCtrlSticky = !isCtrlSticky },
                        onToggleAlt = { isAltSticky = !isAltSticky },
                        onToggleWin = { isWinSticky = !isWinSticky },
                        onCharKey = ::sendCharKey,
                        onSpecialKey = ::sendSpecialKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AirMouseCard(
    isGyroSupported: Boolean,
    isContinuous: Boolean,
    isHoldPressed: Boolean,
    sensitivity: Float,
    onHoldPressChange: (Boolean) -> Unit,
    onToggleContinuous: () -> Unit,
    onSensitivityChanged: (Float) -> Unit
) {
    val latestOnHoldChange by rememberUpdatedState(onHoldPressChange)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF101829),
        border = BorderStroke(
            1.dp,
            if (isContinuous || isHoldPressed) NeonCyan.copy(alpha = 0.8f) else Color(0xFF23304A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isContinuous || isHoldPressed) NeonCyan.copy(alpha = 0.2f) else Color(0xFF1A253B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = null,
                            tint = if (isContinuous || isHoldPressed) NeonCyan else Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Air Mouse (Jiroskop İmleç)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (!isGyroSupported) "Bu cihazda jiroskop sensörü algılanmadı"
                            else if (isContinuous && isHoldPressed) "Duraklatıldı — Bileğinizi merkeze alın"
                            else if (isContinuous) "Sürekli Aktif — Telefonu havada oynatın"
                            else if (isHoldPressed) "Nişan Aktif — Telefonu doğrultup oynatın"
                            else "Havada imleç kontrolü için açın veya basılı tutun",
                            fontSize = 10.5.sp,
                            color = if (isContinuous || isHoldPressed) NeonCyan else Color(0xFF94A3B8)
                        )
                    }
                }
                Switch(
                    checked = isContinuous,
                    onCheckedChange = { onToggleContinuous() },
                    enabled = isGyroSupported,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NeonCyan,
                        checkedTrackColor = Color(0xFF00382E)
                    ),
                    modifier = Modifier.testTag("air_mouse_switch")
                )
            }

            if (isGyroSupported) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            isContinuous && isHoldPressed -> Color(0xFF334155)
                            isHoldPressed -> AmberGold
                            isContinuous -> Color(0xFF162238)
                            else -> NeonCyan.copy(alpha = 0.14f)
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isHoldPressed -> AmberGold
                                isContinuous -> Color(0xFF334155)
                                else -> NeonCyan.copy(alpha = 0.6f)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    latestOnHoldChange(true)
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.none { it.pressed }) break
                                        }
                                    } finally {
                                        latestOnHoldChange(false)
                                    }
                                }
                            }
                            .testTag("air_mouse_hold_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = when {
                                    isContinuous && isHoldPressed -> "⏸ İmleç Duraklatıldı"
                                    isContinuous -> "⏸ Basılı Tut = Merkezle (Clutch)"
                                    isHoldPressed -> "🎯 Oynatın — Bırakınca Durur"
                                    else -> "🎯 Basılı Tut & Havada Oynat"
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHoldPressed && !isContinuous) Color(0xFF042F2E) else Color.White
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.width(120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Hız: ${String.format(Locale.US, "%.1fx", airSensitivityText(sensitivity))}",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = sensitivity,
                            onValueChange = onSensitivityChanged,
                            valueRange = 0.5f..2.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonCyan,
                                activeTrackColor = NeonCyan
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun airSensitivityText(v: Float): Float = (v * 10f).toInt() / 10f

@Composable
private fun PrecisionTouchpadWithScroll(
    hidManager: BluetoothHidManager,
    onDisconnectedTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val tapSlopPx = with(density) { 12.dp.toPx() }
    val scrollStepPx = with(density) { 14.dp.toPx() }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Main Touchpad Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF111A2D), Color(0xFF0C1322))
                    )
                )
                .border(1.dp, Color(0xFF283754), RoundedCornerShape(18.dp))
                .pointerInput(hidManager) {
                    var accumDx = 0f
                    var accumDy = 0f
                    var accumWheel = 0f
                    var lastTapUpTime = 0L

                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val startTime = System.currentTimeMillis()
                        val startPos = firstDown.position
                        var lastSinglePos = firstDown.position
                        var lastMultiY = firstDown.position.y
                        var maxPointers = 1
                        var movedPastSlop = false
                        accumDx = 0f
                        accumDy = 0f
                        accumWheel = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val activeChanges = event.changes.filter { it.pressed }
                            maxPointers = maxOf(maxPointers, activeChanges.size)

                            if (activeChanges.isEmpty()) {
                                // Gesture lifted
                                val dt = System.currentTimeMillis() - startTime
                                if (!movedPastSlop && dt < 240L) {
                                    if (!hidManager.isHidHostConnected()) {
                                        onDisconnectedTap()
                                    } else if (maxPointers >= 2) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_RIGHT)
                                    } else {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapUpTime < 260L) {
                                            hidManager.sendMouseDoubleClick()
                                            lastTapUpTime = 0L
                                        } else {
                                            hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_LEFT)
                                            lastTapUpTime = now
                                        }
                                    }
                                }
                                break
                            }

                            if (event.type == PointerEventType.Move) {
                                if (!hidManager.isHidHostConnected()) {
                                    onDisconnectedTap()
                                }
                                if (activeChanges.size >= 2) {
                                    // Two-finger vertical scroll
                                    val avgY = activeChanges.map { it.position.y }.average().toFloat()
                                    val dy = avgY - lastMultiY
                                    lastMultiY = avgY
                                    if (kotlin.math.abs(dy) > 1.5f) movedPastSlop = true
                                    accumWheel -= dy / scrollStepPx
                                    val steps = accumWheel.toInt()
                                    if (steps != 0) {
                                        accumWheel -= steps
                                        hidManager.sendMouseMove(0, 0, wheel = steps.coerceIn(-12, 12))
                                    }
                                    activeChanges.forEach { it.consume() }
                                } else {
                                    val cur = activeChanges.first().position
                                    if (hypot(cur.x - startPos.x, cur.y - startPos.y) > tapSlopPx) {
                                        movedPastSlop = true
                                    }
                                    val dx = cur.x - lastSinglePos.x
                                    val dy = cur.y - lastSinglePos.y
                                    lastSinglePos = cur
                                    lastMultiY = cur.y

                                    val speed = hypot(dx, dy)
                                    val accel = (1.15f + (speed / 28f).coerceIn(0f, 1.5f)) / (density.density * 0.62f)
                                    accumDx += dx * accel
                                    accumDy += dy * accel
                                    val sendX = accumDx.toInt()
                                    val sendY = accumDy.toInt()
                                    if (sendX != 0 || sendY != 0) {
                                        accumDx -= sendX
                                        accumDy -= sendY
                                        hidManager.sendMouseMove(sendX, sendY)
                                    }
                                    activeChanges.first().consume()
                                }
                            } else if (event.type == PointerEventType.Press && activeChanges.size >= 2) {
                                lastMultiY = activeChanges.map { it.position.y }.average().toFloat()
                            }
                        }
                    }
                }
                .testTag("remote_touchpad_surface"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = Color(0xFF334155),
                    modifier = Modifier.size(38.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Dokunmatik Yüzey (Touchpad)",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tek tık = Sol · Çift tık = Aç · 2 parmak = Sağ / Kaydır",
                    color = Color(0xFF475569),
                    fontSize = 10.5.sp
                )
            }
        }

        // Dedicated 1-Finger Vertical Scroll Wheel Strip
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141E32))
                .border(1.dp, Color(0xFF283754), RoundedCornerShape(16.dp))
                .pointerInput(hidManager) {
                    var scrollAccum = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { scrollAccum = 0f },
                        onDragCancel = { scrollAccum = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        scrollAccum -= dragAmount / scrollStepPx
                        val steps = scrollAccum.toInt()
                        if (steps != 0) {
                            scrollAccum -= steps
                            hidManager.sendMouseMove(0, 0, wheel = steps.coerceIn(-12, 12))
                        }
                    }
                }
                .testTag("remote_scroll_strip"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 18.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Yukarı Kaydır", tint = NeonCyan, modifier = Modifier.size(20.dp))
                Text("SCROLL", fontSize = 9.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Aşağı Kaydır", tint = NeonCyan, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MouseButtonBar(
    isDragLocked: Boolean,
    onLeftDownChange: (Boolean) -> Unit,
    onToggleDragLock: () -> Unit,
    onMiddleDownChange: (Boolean) -> Unit,
    onRightDownChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HoldableActionSurface(
            label = "Sol Tık",
            bgColor = Color(0xFF172338),
            pressedColor = NeonCyan.copy(alpha = 0.3f),
            fgColor = Color.White,
            onPressChange = onLeftDownChange,
            modifier = Modifier
                .weight(1.8f)
                .fillMaxHeight()
                .testTag("mouse_left_btn")
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isDragLocked) AmberGold else Color(0xFF131C2E),
            border = BorderStroke(1.dp, if (isDragLocked) AmberGold else Color(0xFF283754)),
            modifier = Modifier
                .weight(1.05f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onToggleDragLock() }
                .testTag("mouse_drag_lock_btn")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isDragLocked) "Sürükle ●" else "Sürükle",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDragLocked) Color(0xFF042F2E) else Color(0xFFCBD5E1)
                )
            }
        }

        HoldableActionSurface(
            label = "Orta",
            bgColor = Color(0xFF131C2E),
            pressedColor = Color(0xFF334155),
            fgColor = Color(0xFFCBD5E1),
            onPressChange = onMiddleDownChange,
            modifier = Modifier
                .weight(0.85f)
                .fillMaxHeight()
                .testTag("mouse_middle_btn")
        )

        HoldableActionSurface(
            label = "Sağ Tık",
            bgColor = Color(0xFF172338),
            pressedColor = NeonCyan.copy(alpha = 0.3f),
            fgColor = NeonCyan,
            onPressChange = onRightDownChange,
            modifier = Modifier
                .weight(1.5f)
                .fillMaxHeight()
                .testTag("mouse_right_btn")
        )
    }
}

@Composable
private fun HoldableActionSurface(
    label: String,
    bgColor: Color,
    pressedColor: Color,
    fgColor: Color,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val latestOnPressChange by rememberUpdatedState(onPressChange)

    DisposableEffect(Unit) {
        onDispose {
            if (isPressed) {
                isPressed = false
                latestOnPressChange(false)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isPressed) pressedColor else bgColor,
        border = BorderStroke(1.dp, if (isPressed) NeonCyan else Color(0xFF283754)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    latestOnPressChange(true)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        if (isPressed) {
                            isPressed = false
                            latestOnPressChange(false)
                        }
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ConsumerMediaStrip(
    onConsumer: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF101829),
        border = BorderStroke(1.dp, Color(0xFF23304A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onConsumer(BluetoothHidManager.CONSUMER_MUTE) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = "Sessiz", tint = Color(0xFFCBD5E1))
            }
            IconButton(onClick = { onConsumer(BluetoothHidManager.CONSUMER_VOL_DOWN) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Sesi Azalt", tint = Color(0xFFCBD5E1))
            }
            IconButton(onClick = { onConsumer(BluetoothHidManager.CONSUMER_VOL_UP) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Sesi Artır", tint = Color(0xFFCBD5E1))
            }
            IconButton(onClick = { onConsumer(BluetoothHidManager.CONSUMER_PREV) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Önceki", tint = Color(0xFFCBD5E1))
            }
            IconButton(onClick = { onConsumer(BluetoothHidManager.CONSUMER_PLAY_PAUSE) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Oynat / Duraklat", tint = NeonCyan)
            }
            IconButton(onClick = { onConsumer(BluetoothHidManager.CONSUMER_NEXT) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Sonraki", tint = Color(0xFFCBD5E1))
            }
        }
    }
}

@Composable
private fun ShortcutChip(
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) NeonCyan.copy(alpha = 0.2f) else Color(0xFF131D30),
        border = BorderStroke(1.dp, if (isActive) NeonCyan else Color(0xFF283754)),
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) NeonCyan else Color(0xFFE2E8F0)
            )
        }
    }
}

@Composable
private fun FullPcKeyboardDeck(
    isCompact: Boolean,
    isSymbolsPage: Boolean,
    isShiftOnce: Boolean,
    isCapsLock: Boolean,
    isCtrlSticky: Boolean,
    isAltSticky: Boolean,
    isWinSticky: Boolean,
    showFunctionRow: Boolean,
    onToggleSymbols: () -> Unit,
    onTapShift: () -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleWin: () -> Unit,
    onCharKey: (Char) -> Unit,
    onSpecialKey: (Byte, Byte?) -> Unit,
    modifier: Modifier = Modifier
) {
    val trLocale = remember { Locale("tr", "TR") }
    val isUpper = isShiftOnce || isCapsLock

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 5.dp)
    ) {
        // Optional F1..F12 + Nav Row
        AnimatedVisibility(visible = showFunctionRow) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val fnKeys = listOf(
                        "F1" to BluetoothHidManager.KEY_F1,
                        "F2" to BluetoothHidManager.KEY_F2,
                        "F3" to BluetoothHidManager.KEY_F3,
                        "F4" to BluetoothHidManager.KEY_F4,
                        "F5" to BluetoothHidManager.KEY_F5,
                        "F6" to BluetoothHidManager.KEY_F6,
                        "F7" to BluetoothHidManager.KEY_F7,
                        "F8" to BluetoothHidManager.KEY_F8,
                        "F9" to BluetoothHidManager.KEY_F9,
                        "F10" to BluetoothHidManager.KEY_F10,
                        "F11" to BluetoothHidManager.KEY_F11,
                        "F12" to BluetoothHidManager.KEY_F12,
                        "PrtSc" to BluetoothHidManager.KEY_PRINT_SCREEN,
                        "Ins" to BluetoothHidManager.KEY_INSERT,
                        "Del" to BluetoothHidManager.KEY_DELETE,
                        "Home" to BluetoothHidManager.KEY_HOME,
                        "End" to BluetoothHidManager.KEY_END,
                        "PgUp" to BluetoothHidManager.KEY_PAGE_UP,
                        "PgDn" to BluetoothHidManager.KEY_PAGE_DOWN
                    )
                    fnKeys.forEach { (label, code) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF162136),
                            border = BorderStroke(0.8.dp, Color(0xFF293855)),
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSpecialKey(code, null) }
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
                            }
                        }
                    }
                }
            }
        }

        // Number Row (when full keyboard mode and not compact)
        if (!isCompact && !isSymbolsPage) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.95f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0').forEach { digit ->
                    KeyCapButton(
                        label = digit.toString(),
                        onClick = { onCharKey(digit) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }

        if (!isSymbolsPage) {
            // Row 1: q w e r t y u ı o p ğ ü
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'ı', 'o', 'p', 'ğ', 'ü').forEach { ch ->
                    val label = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString()
                    KeyCapButton(
                        label = label,
                        onClick = { onCharKey(ch) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            // Row 2: a s d f g h j k l ş i
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'ş', 'i').forEach { ch ->
                    val label = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString()
                    KeyCapButton(
                        label = label,
                        onClick = { onCharKey(ch) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            // Row 3: [⇧] z x c v b n m ö ç [⌫]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                KeyCapButton(
                    label = if (isCapsLock) "⇪" else "⇧",
                    bgColor = when {
                        isCapsLock -> NeonCyan
                        isShiftOnce -> Color(0xFF183A38)
                        else -> Color(0xFF131C2D)
                    },
                    fgColor = when {
                        isCapsLock -> Color(0xFF042F2E)
                        isShiftOnce -> NeonCyan
                        else -> Color(0xFFCBD5E1)
                    },
                    onClick = onTapShift,
                    modifier = Modifier
                        .weight(1.35f)
                        .fillMaxHeight()
                )
                listOf('z', 'x', 'c', 'v', 'b', 'n', 'm', 'ö', 'ç').forEach { ch ->
                    val label = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString()
                    KeyCapButton(
                        label = label,
                        onClick = { onCharKey(ch) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                KeyCapButton(
                    label = "⌫",
                    bgColor = Color(0xFF131C2D),
                    fgColor = Color(0xFFCBD5E1),
                    onClick = { onSpecialKey(BluetoothHidManager.KEY_BACKSPACE, 0) },
                    modifier = Modifier
                        .weight(1.35f)
                        .fillMaxHeight()
                )
            }
        } else {
            // Symbols & Punctuation Rows
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0').forEach { sym ->
                    KeyCapButton(sym.toString(), { onCharKey(sym) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf('-', '/', ':', ';', '(', ')', '&', '@', '"', '=').forEach { sym ->
                    KeyCapButton(sym.toString(), { onCharKey(sym) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf('_', '+', '*', '%', '?', '!', '\'', '\\').forEach { sym ->
                    KeyCapButton(sym.toString(), { onCharKey(sym) }, Modifier.weight(1f).fillMaxHeight())
                }
                KeyCapButton(
                    label = "⌫",
                    bgColor = Color(0xFF131C2D),
                    fgColor = Color(0xFFCBD5E1),
                    onClick = { onSpecialKey(BluetoothHidManager.KEY_BACKSPACE, 0) },
                    modifier = Modifier.weight(1.5f).fillMaxHeight()
                )
            }
        }

        // Modifier + Arrow Row: [Ctrl] [Alt] [⊞ Win] [←] [↑] [↓] [→] [Del]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.95f),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            KeyCapButton(
                label = "Ctrl",
                bgColor = if (isCtrlSticky) NeonCyan else Color(0xFF131C2D),
                fgColor = if (isCtrlSticky) Color(0xFF042F2E) else Color(0xFFCBD5E1),
                fontSizeSp = 11.5f,
                onClick = onToggleCtrl,
                modifier = Modifier.weight(1.1f).fillMaxHeight()
            )
            KeyCapButton(
                label = "Alt",
                bgColor = if (isAltSticky) NeonCyan else Color(0xFF131C2D),
                fgColor = if (isAltSticky) Color(0xFF042F2E) else Color(0xFFCBD5E1),
                fontSizeSp = 11.5f,
                onClick = onToggleAlt,
                modifier = Modifier.weight(1.0f).fillMaxHeight()
            )
            KeyCapButton(
                label = "⊞",
                bgColor = if (isWinSticky) NeonCyan else Color(0xFF131C2D),
                fgColor = if (isWinSticky) Color(0xFF042F2E) else Color(0xFFCBD5E1),
                fontSizeSp = 13f,
                onClick = onToggleWin,
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
            KeyCapButton(
                label = "←",
                bgColor = Color(0xFF172235),
                fgColor = Color.White,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_LEFT, null) },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
            KeyCapButton(
                label = "↑",
                bgColor = Color(0xFF172235),
                fgColor = Color.White,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_UP, null) },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
            KeyCapButton(
                label = "↓",
                bgColor = Color(0xFF172235),
                fgColor = Color.White,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_DOWN, null) },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
            KeyCapButton(
                label = "→",
                bgColor = Color(0xFF172235),
                fgColor = Color.White,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_RIGHT, null) },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
            KeyCapButton(
                label = "Del",
                bgColor = Color(0xFF131C2D),
                fgColor = Color(0xFFEF4444),
                fontSizeSp = 11f,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_DELETE, null) },
                modifier = Modifier.weight(1.0f).fillMaxHeight()
            )
        }

        // Bottom Row: [?123] [⇥] [,] [Boşluk] [.] [↵]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            KeyCapButton(
                label = if (isSymbolsPage) "ABC" else "?123",
                bgColor = Color(0xFF131C2D),
                fgColor = Color(0xFFCBD5E1),
                fontSizeSp = 12f,
                onClick = onToggleSymbols,
                modifier = Modifier.weight(1.35f).fillMaxHeight()
            )
            KeyCapButton(
                label = "⇥",
                bgColor = Color(0xFF131C2D),
                fgColor = Color(0xFFCBD5E1),
                onClick = { onSpecialKey(BluetoothHidManager.KEY_TAB, null) },
                modifier = Modifier.weight(1.0f).fillMaxHeight()
            )
            KeyCapButton(
                label = ",",
                onClick = { onCharKey(',') },
                modifier = Modifier.weight(0.85f).fillMaxHeight()
            )
            KeyCapButton(
                label = "Boşluk",
                bgColor = Color(0xFF1E293B),
                fgColor = Color(0xFF94A3B8),
                fontSizeSp = 12f,
                onClick = { onCharKey(' ') },
                modifier = Modifier.weight(3.2f).fillMaxHeight()
            )
            KeyCapButton(
                label = ".",
                onClick = { onCharKey('.') },
                modifier = Modifier.weight(0.85f).fillMaxHeight()
            )
            KeyCapButton(
                label = "↵",
                bgColor = NeonCyan,
                fgColor = Color(0xFF00382E),
                fontSizeSp = 16f,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_ENTER, null) },
                modifier = Modifier.weight(1.5f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun KeyCapButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bgColor: Color = Color(0xFF1B2436),
    fgColor: Color = Color(0xFFF8FAFC),
    fontSizeSp: Float = 14.5f
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isPressed) Color(0xFF334155) else bgColor,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = label,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}
