package com.example.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.ui.theme.AmberGold
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.util.Locale

private val TouchpadBgTop = Color(0xFF111B30)
private val TouchpadBgBottom = Color(0xFF080E1B)
private val SurfacePanel = Color(0xFF0E1627)
private val SurfaceElevated = Color(0xFF162238)
private val BorderSubtle = Color(0xFF243450)
private val MutedText = Color(0xFF94A3B8)
private val VioletAccent = Color(0xFFA78BFA)

/**
 * Compact collapsible Gyro Air Mouse settings drawer (only visible when expanded or continuous mode is active).
 * Keeps the main Touchpad maximally spacious by default while Air Hold Trigger lives down in the thumb bar!
 */
@Composable
fun GyroCalibrationDrawer(
    isGyroSupported: Boolean,
    isContinuous: Boolean,
    sensitivity: Float,
    onToggleContinuous: () -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    onRecalibrate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, if (isContinuous) NeonCyan.copy(alpha = 0.75f) else BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = null,
                        tint = if (isContinuous) NeonCyan else MutedText,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Air Mouse Sürekli Mod",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (!isGyroSupported) "Bu cihazda jiroskop sensörü yok"
                            else "Açıkken alttaki Air tuşu duraklatma (debriyaj) olur",
                            fontSize = 10.sp,
                            color = MutedText
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Hız ${String.format(Locale.US, "%.1fx", (sensitivity * 10f).toInt() / 10f)}",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonCyan
                    )
                    Slider(
                        value = sensitivity,
                        onValueChange = onSensitivityChanged,
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                    )
                    FilledTonalIconButton(
                        onClick = onRecalibrate,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = SurfaceElevated,
                            contentColor = NeonCyan
                        ),
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("air_mouse_recalibrate_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Jiroskopu Kalibre Et",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Sleek TV & PC Media Remote Deck that expands cleanly on demand (never wastes permanent Touchpad height!).
 */
@Composable
fun MediaRemoteDeckCard(
    onConsumer: (Int) -> Unit,
    onFullscreenToggle: () -> Unit,
    onWindowSwitch: () -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF10192D),
        border = BorderStroke(1.dp, VioletAccent.copy(alpha = 0.45f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_media_deck_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Big Playback & Volume Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaKeyPill(
                    icon = Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Sessiz",
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConsumer(BluetoothHidManager.CONSUMER_MUTE) },
                    modifier = Modifier.weight(0.9f)
                )
                MediaKeyPill(
                    icon = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = "Sesi Azalt",
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConsumer(BluetoothHidManager.CONSUMER_VOL_DOWN) },
                    modifier = Modifier.weight(0.9f)
                )
                MediaKeyPill(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "Önceki",
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConsumer(BluetoothHidManager.CONSUMER_PREV) },
                    modifier = Modifier.weight(0.9f)
                )
                // Primary Play/Pause Hero Key
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = NeonCyan.copy(alpha = 0.22f),
                    border = BorderStroke(1.2.dp, NeonCyan),
                    modifier = Modifier
                        .weight(1.45f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConsumer(BluetoothHidManager.CONSUMER_PLAY_PAUSE)
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Oynat / Duraklat",
                            tint = NeonCyan,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                MediaKeyPill(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "Sonraki",
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConsumer(BluetoothHidManager.CONSUMER_NEXT) },
                    modifier = Modifier.weight(0.9f)
                )
                MediaKeyPill(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Sesi Artır",
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onConsumer(BluetoothHidManager.CONSUMER_VOL_UP) },
                    modifier = Modifier.weight(0.9f)
                )
            }

            // Row 2: Quick Couch/TV Actions (Tam Ekran, Alt+Tab, Esc)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MediaTextActionPill(
                    icon = Icons.Default.Fullscreen,
                    label = "Tam Ekran",
                    accent = AmberGold,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFullscreenToggle() },
                    modifier = Modifier.weight(1f)
                )
                MediaTextActionPill(
                    icon = Icons.Default.Layers,
                    label = "Alt+Tab",
                    accent = VioletAccent,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onWindowSwitch() },
                    modifier = Modifier.weight(1f)
                )
                MediaTextActionPill(
                    icon = Icons.Default.Close,
                    label = "Esc",
                    accent = Color(0xFFFCA5A5),
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onEscape() },
                    modifier = Modifier.weight(0.75f)
                )
            }
        }
    }
}

@Composable
private fun MediaKeyPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceElevated,
        border = BorderStroke(0.9.dp, BorderSubtle),
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color(0xFFE2E8F0),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MediaTextActionPill(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(0.9.dp, accent.copy(alpha = 0.45f)),
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1)
        }
    }
}

/**
 * Tactile, laptop-grade Precision Touchpad + 1-finger scroll wheel strip.
 * Zero duplicate chrome: optional 1-line slim control strip + massive glass trackpad surface.
 */
@Composable
fun PrecisionTouchpadWithScroll(
    hidManager: BluetoothHidManager,
    touchpadSpeed: Float,
    isPrecisionMode: Boolean,
    naturalScroll: Boolean,
    isDragLocked: Boolean,
    onSpeedChange: (Float) -> Unit,
    onTogglePrecisionMode: () -> Unit,
    onToggleNaturalScroll: () -> Unit,
    onZoomWheel: (Int) -> Unit,
    onThreeFingerSwipe: (ThreeFingerSwipeDirection) -> Unit,
    onDisconnectedTap: () -> Unit,
    showToolbar: Boolean = true,
    isMediaOpen: Boolean = false,
    onToggleMediaOpen: (() -> Unit)? = null,
    isGyroDrawerOpen: Boolean = false,
    isAirContinuous: Boolean = false,
    onToggleGyroDrawer: (() -> Unit)? = null,
    compactHint: Boolean = false,
    onUserInteraction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    val effectiveSpeed = if (isPrecisionMode) 0.45f else touchpadSpeed
    val tapSlopPx = with(density) { 12.dp.toPx() }
    val scrollStepPx = with(density) { 14.dp.toPx() }
    val pinchStepPx = with(density) { 20.dp.toPx() }
    val threeFingerThresholdPx = with(density) { 44.dp.toPx() }
    val edgePanZonePx = with(density) { 26.dp.toPx() }

    val gestureEngine = remember { TouchpadGestureEngine() }
    LaunchedEffect(density.density, effectiveSpeed, naturalScroll, tapSlopPx, scrollStepPx, pinchStepPx, threeFingerThresholdPx) {
        gestureEngine.config = TouchpadConfig(
            density = density.density,
            speedMultiplier = effectiveSpeed,
            naturalScroll = naturalScroll,
            tapSlopPx = tapSlopPx,
            scrollStepPx = scrollStepPx,
            pinchStepPx = pinchStepPx,
            threeFingerSwipeThresholdPx = threeFingerThresholdPx
        )
    }

    var isTapDragging by remember { mutableStateOf(false) }
    // Reusable in-place touch buffers + draw-phase tick: 120Hz finger motion NEVER recomposes the touchpad!
    val activeFingerList = remember { ArrayList<Offset>(5) }
    val cursorTrailBuffer = remember { ArrayDeque<Offset>(16) }
    var activeFingerCount by remember { mutableIntStateOf(0) }
    val overlayDrawTick = remember { mutableIntStateOf(0) }
    val timerWakeChannel = remember { Channel<Unit>(Channel.CONFLATED) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var isScrollStripActive by remember { mutableStateOf(false) }

    var gestureBadgeText by remember { mutableStateOf<String?>(null) }
    var gestureBadgeSeq by remember { mutableIntStateOf(0) }
    fun flashGestureBadge(text: String) {
        gestureBadgeText = text
        gestureBadgeSeq++
    }

    LaunchedEffect(gestureBadgeSeq) {
        if (gestureBadgeSeq > 0) {
            delay(1050L)
            gestureBadgeText = null
        }
    }

    val latestOnZoomWheel by rememberUpdatedState(onZoomWheel)
    val latestOnThreeFingerSwipe by rememberUpdatedState(onThreeFingerSwipe)
    val latestOnDisconnectedTap by rememberUpdatedState(onDisconnectedTap)
    val latestIsDragLocked by rememberUpdatedState(isDragLocked)
    val latestOnUserInteraction by rememberUpdatedState(onUserInteraction)
    val latestEffectiveSpeed by rememberUpdatedState(effectiveSpeed)

    fun dispatchActions(actions: List<TouchpadAction>) {
        if (actions.isEmpty()) return
        for (action in actions) {
            when (action) {
                is TouchpadAction.Move -> {
                    if (hidManager.isHidHostConnected()) {
                        hidManager.sendMouseMove(action.dx, action.dy)
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                is TouchpadAction.Scroll -> {
                    if (hidManager.isHidHostConnected()) {
                        hidManager.sendMouseMove(0, 0, wheel = action.wheelSteps)
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                is TouchpadAction.Zoom -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        latestOnZoomWheel(action.wheelSteps)
                        flashGestureBadge(if (action.wheelSteps > 0) "🔍 Yakınlaştır" else "🔍 Uzaklaştır")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.LeftClick -> {
                    if (hidManager.isHidHostConnected()) {
                        hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_LEFT)
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.DoubleClick -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        hidManager.sendMouseDoubleClick()
                        flashGestureBadge("Çift Tık")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.RightClick -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_RIGHT)
                        flashGestureBadge("Sağ Tık")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.MiddleClick -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_MIDDLE)
                        flashGestureBadge("Orta Tık")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.TapDragStart -> {
                    isTapDragging = true
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, true)
                        flashGestureBadge("✋ Sürükleme Aktif")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.TapDragEnd -> {
                    isTapDragging = false
                    if (!latestIsDragLocked && hidManager.isHidHostConnected()) {
                        hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, false)
                    }
                }
                is TouchpadAction.ThreeFingerSwipe -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnThreeFingerSwipe(action.direction)
                        val label = when (action.direction) {
                            ThreeFingerSwipeDirection.LEFT -> "◀ Geri (Alt+←)"
                            ThreeFingerSwipeDirection.RIGHT -> "▶ İleri (Alt+→)"
                            ThreeFingerSwipeDirection.UP -> "⊞ Pencereler"
                            ThreeFingerSwipeDirection.DOWN -> "🖥 Masaüstü"
                        }
                        flashGestureBadge(label)
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
            }
        }
    }

    // Event-driven touchpad timer: sleeps with ZERO CPU wakeups whenever idle,
    // and only runs the 24ms tick while a finger is down, a single-tap window is pending, or drag-panning is active!
    LaunchedEffect(gestureEngine) {
        while (true) {
            val needsTick = activeFingerCount > 0 ||
                gestureEngine.hasPendingSingleTap() ||
                gestureEngine.isTapDragging ||
                isTapDragging
            if (!needsTick) {
                val wakeResult = timerWakeChannel.receiveCatching()
                if (wakeResult.isClosed) break
                continue
            }
            delay(24L)
            val now = System.currentTimeMillis()
            gestureEngine.flushPendingSingleTapIfExpired(now)?.let { action ->
                dispatchActions(listOf(action))
            }
            val holdActions = gestureEngine.onHoldTick(now)
            if (holdActions.isNotEmpty()) {
                dispatchActions(holdActions)
            }
            val dragging = isTapDragging || latestIsDragLocked
            if (dragging && activeFingerList.size == 1 && padSize.width > 0 && padSize.height > 0 && hidManager.isHidHostConnected()) {
                val pt = activeFingerList[0]
                val (panX, panY) = TouchpadGestureEngine.computeEdgeAutoPanDelta(
                    x = pt.x,
                    y = pt.y,
                    width = padSize.width.toFloat(),
                    height = padSize.height.toFloat(),
                    edgeZonePx = edgePanZonePx,
                    maxStepPx = (9f * latestEffectiveSpeed.coerceIn(0.6f, 2f)).toInt().coerceAtLeast(4)
                )
                if (panX != 0 || panY != 0) {
                    hidManager.sendMouseMove(panX, panY)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            timerWakeChannel.close()
            val cleanup = gestureEngine.reset()
            if (cleanup.contains(TouchpadAction.TapDragEnd) && !latestIsDragLocked) {
                hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, false)
            }
        }
    }

    val anyDragActive = isTapDragging || isDragLocked
    val padBorderColor by animateColorAsState(
        targetValue = when {
            anyDragActive -> AmberGold
            activeFingerCount >= 3 -> VioletAccent
            activeFingerCount > 0 -> NeonCyan.copy(alpha = 0.85f)
            else -> BorderSubtle
        },
        label = "touchpad_border"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showToolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val speedPresets = listOf(0.75f, 1.15f, 1.6f, 2.2f)
                val speedLabel = String.format(Locale.US, "%.1fx", touchpadSpeed)
                TouchpadToolPill(
                    icon = Icons.Default.Speed,
                    label = speedLabel,
                    isActive = !isPrecisionMode && touchpadSpeed > 1.3f,
                    onClick = {
                        val nextIdx = (speedPresets.indexOfFirst { kotlin.math.abs(it - touchpadSpeed) < 0.12f } + 1) % speedPresets.size
                        onSpeedChange(speedPresets[nextIdx])
                    },
                    modifier = Modifier.testTag("touchpad_speed_btn")
                )

                TouchpadToolPill(
                    icon = Icons.Default.GpsFixed,
                    label = "Hassas",
                    isActive = isPrecisionMode,
                    activeColor = AmberGold,
                    onClick = onTogglePrecisionMode,
                    modifier = Modifier.testTag("touchpad_precision_btn")
                )

                TouchpadToolPill(
                    icon = Icons.Default.SwapVert,
                    label = if (naturalScroll) "Doğal" else "Klasik",
                    isActive = naturalScroll,
                    onClick = onToggleNaturalScroll,
                    modifier = Modifier.testTag("touchpad_scroll_dir_btn")
                )

                if (onToggleGyroDrawer != null) {
                    TouchpadToolPill(
                        icon = Icons.Default.ScreenRotation,
                        label = if (isAirContinuous) "Air ●" else "Air Ayar",
                        isActive = isGyroDrawerOpen || isAirContinuous,
                        activeColor = NeonCyan,
                        onClick = onToggleGyroDrawer,
                        modifier = Modifier.testTag("touchpad_gyro_drawer_btn")
                    )
                }

                if (onToggleMediaOpen != null) {
                    TouchpadToolPill(
                        icon = Icons.Default.Tv,
                        label = "Medya",
                        isActive = isMediaOpen,
                        activeColor = VioletAccent,
                        onClick = onToggleMediaOpen,
                        modifier = Modifier.testTag("touchpad_media_toggle_btn")
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main Obsidian Glass Touchpad Surface
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { padSize = it }
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            if (anyDragActive) listOf(Color(0xFF231C0E), Color(0xFF100E09))
                            else listOf(TouchpadBgTop, TouchpadBgBottom)
                        )
                    )
                    .drawWithCache {
                        // Cache dot-grid geometry & glass sheen once per size change -> single GPU drawPoints batch!
                        val sheenBrush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent)
                        )
                        val sheenInset = 24.dp.toPx()
                        val sheenY = 1.dp.toPx()
                        val sheenWidth = 1.5.dp.toPx()
                        val step = 26.dp.toPx()
                        val dotStrokePx = 2.7.dp.toPx()
                        val dots = ArrayList<Offset>(256)
                        var y = step
                        while (y < size.height - step * 0.5f) {
                            var x = step
                            while (x < size.width - step * 0.5f) {
                                dots.add(Offset(x, y))
                                x += step
                            }
                            y += step
                        }
                        val dotColor = if (anyDragActive) {
                            AmberGold.copy(alpha = 0.16f)
                        } else {
                            Color(0xFF425982).copy(alpha = 0.24f)
                        }
                        onDrawBehind {
                            drawLine(
                                brush = sheenBrush,
                                start = Offset(sheenInset, sheenY),
                                end = Offset(size.width - sheenInset, sheenY),
                                strokeWidth = sheenWidth
                            )
                            if (dots.isNotEmpty()) {
                                drawPoints(
                                    points = dots,
                                    pointMode = PointMode.Points,
                                    color = dotColor,
                                    strokeWidth = dotStrokePx,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                    .border(
                        width = if (anyDragActive) 1.8.dp else 1.1.dp,
                        color = padBorderColor,
                        shape = RoundedCornerShape(22.dp)
                    )
                    .pointerInput(hidManager, gestureEngine) {
                        val reusablePoints = ArrayList<TouchpadPoint>(5)
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            latestOnUserInteraction()
                            activeFingerList.clear()
                            activeFingerList.add(firstDown.position)
                            cursorTrailBuffer.clear()
                            cursorTrailBuffer.addLast(firstDown.position)
                            activeFingerCount = 1
                            overlayDrawTick.intValue++
                            timerWakeChannel.trySend(Unit)

                            val downActions = gestureEngine.onGestureDown(
                                TouchpadPoint(firstDown.position.x, firstDown.position.y),
                                System.currentTimeMillis()
                            )
                            dispatchActions(downActions)

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    activeFingerList.clear()
                                    reusablePoints.clear()
                                    val changes = event.changes
                                    for (i in changes.indices) {
                                        val c = changes[i]
                                        if (c.pressed) {
                                            activeFingerList.add(c.position)
                                            reusablePoints.add(TouchpadPoint(c.position.x, c.position.y))
                                        }
                                    }
                                    val count = activeFingerList.size
                                    if (activeFingerCount != count) {
                                        activeFingerCount = count
                                    }
                                    if (count == 1 && event.type == PointerEventType.Move) {
                                        val nextPos = activeFingerList[0]
                                        if (cursorTrailBuffer.size >= 14) {
                                            cursorTrailBuffer.removeFirst()
                                        }
                                        cursorTrailBuffer.addLast(nextPos)
                                    }
                                    overlayDrawTick.intValue++

                                    if (count == 0) {
                                        val upActions = gestureEngine.onAllPointersUp(System.currentTimeMillis())
                                        dispatchActions(upActions)
                                        if (gestureEngine.hasPendingSingleTap()) {
                                            timerWakeChannel.trySend(Unit)
                                        }
                                        break
                                    }

                                    when (event.type) {
                                        PointerEventType.Press -> {
                                            dispatchActions(gestureEngine.onPointerAdded(reusablePoints))
                                        }
                                        PointerEventType.Release -> {
                                            gestureEngine.onPointerRemoved(reusablePoints)
                                        }
                                        PointerEventType.Move -> {
                                            val moveActions = gestureEngine.onMove(reusablePoints, System.currentTimeMillis())
                                            dispatchActions(moveActions)
                                            for (i in changes.indices) {
                                                if (changes[i].pressed) changes[i].consume()
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            } finally {
                                activeFingerList.clear()
                                cursorTrailBuffer.clear()
                                activeFingerCount = 0
                                overlayDrawTick.intValue++
                                if (gestureEngine.isTapDragging) {
                                    dispatchActions(gestureEngine.reset())
                                }
                            }
                        }
                    }
                    .testTag("remote_touchpad_surface"),
                contentAlignment = Alignment.Center
            ) {
                // Isolated draw-phase-only Canvas: moving fingers at 120Hz never recomposes PrecisionTouchpadWithScroll!
                if (activeFingerCount > 0) {
                    TouchpadLiveOverlayCanvas(
                        activeFingers = activeFingerList,
                        cursorTrail = cursorTrailBuffer,
                        anyDragActive = anyDragActive,
                        drawTick = overlayDrawTick
                    )
                }

                // Subtle idle guidance watermark
                if (!anyDragActive && activeFingerCount == 0) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        if (!compactHint) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = Color(0xFF2E4366),
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text(
                            text = if (compactHint) "Mini Touchpad · Dokun = Sol · 2 Parmak = Sağ / Kaydır"
                            else "Çift tıkla & tut = Sürükle",
                            color = Color(0xFF7B90B2),
                            fontSize = if (compactHint) 10.5.sp else 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (!compactHint) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "2 parmak: Sağ tık · Kaydır · Yakınlaştır  |  3 parmak: Orta · Gezin",
                                color = Color(0xFF4F6280),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = anyDragActive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = AmberGold.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, AmberGold)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PanTool,
                                contentDescription = null,
                                tint = AmberGold,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (isTapDragging) "Sürükleniyor · Bırakınca serbest kalır" else "Sürükle Kilidi Açık",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmberGold
                            )
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = gestureBadgeText != null && !anyDragActive,
                    enter = fadeIn(spring()),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF14243A).copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = gestureBadgeText.orEmpty(),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NeonCyan,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Dedicated 1-Finger Vertical Scroll Strip
            val scrollBorder by animateColorAsState(
                targetValue = if (isScrollStripActive) NeonCyan else BorderSubtle,
                label = "scroll_strip_border"
            )
            Box(
                modifier = Modifier
                    .width(if (compactHint) 40.dp else 46.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isScrollStripActive) Color(0xFF13263B) else Color(0xFF10192C))
                    .drawWithCache {
                        val ribColor = if (isScrollStripActive) NeonCyan.copy(alpha = 0.4f) else Color(0xFF2B3B58)
                        val startY = size.height * 0.22f
                        val endY = size.height * 0.78f
                        val step = 11.dp.toPx()
                        val ribStroke = 1.6.dp.toPx()
                        val x1 = size.width * 0.28f
                        val x2 = size.width * 0.72f
                        val yCoords = ArrayList<Float>(32)
                        var y = startY
                        while (y <= endY) {
                            yCoords.add(y)
                            y += step
                        }
                        onDrawBehind {
                            for (i in yCoords.indices) {
                                val ry = yCoords[i]
                                drawLine(
                                    color = ribColor,
                                    start = Offset(x1, ry),
                                    end = Offset(x2, ry),
                                    strokeWidth = ribStroke
                                )
                            }
                        }
                    }
                    .border(1.dp, scrollBorder, RoundedCornerShape(18.dp))
                    .pointerInput(hidManager, naturalScroll) {
                        var scrollAccum = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                latestOnUserInteraction()
                                isScrollStripActive = true
                            },
                            onDragEnd = {
                                scrollAccum = 0f
                                isScrollStripActive = false
                            },
                            onDragCancel = {
                                scrollAccum = 0f
                                isScrollStripActive = false
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            if (!hidManager.isHidHostConnected()) {
                                latestOnDisconnectedTap()
                                return@detectVerticalDragGestures
                            }
                            val sign = if (naturalScroll) 1f else -1f
                            scrollAccum += (dragAmount / scrollStepPx) * sign
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
                        .padding(vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Yukarı Kaydır",
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Aşağı Kaydır",
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchpadLiveOverlayCanvas(
    activeFingers: List<Offset>,
    cursorTrail: ArrayDeque<Offset>,
    anyDragActive: Boolean,
    drawTick: IntState
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Subscribe strictly during draw phase (zero Compose recompositions during 120Hz finger drag!)
        @Suppress("UNUSED_VARIABLE")
        val tick = drawTick.intValue
        val fingerCount = activeFingers.size
        val haloColor = when {
            anyDragActive -> AmberGold
            fingerCount >= 3 -> VioletAccent
            else -> NeonCyan
        }
        val trailSize = cursorTrail.size
        if (trailSize >= 2) {
            val total = trailSize - 1
            for (i in 0 until total) {
                val fraction = (i + 1).toFloat() / total.toFloat()
                drawLine(
                    color = haloColor.copy(alpha = fraction * 0.45f),
                    start = cursorTrail[i],
                    end = cursorTrail[i + 1],
                    strokeWidth = (1.5f + fraction * 4.5f).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        val outerGlowRadius = 38.dp.toPx()
        val innerGlowRadius = 22.dp.toPx()
        val coreRadius = 7.5.dp.toPx()
        for (i in 0 until fingerCount) {
            val pos = activeFingers[i]
            // Layered translucent circles avoid allocating a fresh Shader/RadialGradient object per finger per 120Hz frame
            drawCircle(
                color = haloColor.copy(alpha = 0.10f),
                radius = outerGlowRadius,
                center = pos
            )
            drawCircle(
                color = haloColor.copy(alpha = 0.22f),
                radius = innerGlowRadius,
                center = pos
            )
            drawCircle(
                color = haloColor.copy(alpha = 0.80f),
                radius = coreRadius,
                center = pos
            )
        }
    }
}

@Composable
private fun TouchpadToolPill(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color = NeonCyan,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) activeColor.copy(alpha = 0.18f) else Color(0xFF121C2F),
        border = BorderStroke(1.dp, if (isActive) activeColor.copy(alpha = 0.85f) else BorderSubtle),
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) activeColor else MutedText,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) activeColor else Color(0xFFE2E8F0),
                maxLines = 1
            )
        }
    }
}

/**
 * Ergonomic Bottom Thumb Action Bar:
 * Places Left Click, Hold-to-Aim Air Mouse Trigger, Drag Lock, and Right Click right in the natural
 * one-handed thumb arc immediately below the touchpad!
 */
@Composable
fun ThumbMouseActionBar(
    isDragLocked: Boolean,
    isGyroSupported: Boolean,
    isAirContinuous: Boolean,
    isAirHoldPressed: Boolean,
    onLeftDownChange: (Boolean) -> Unit,
    onAirHoldPressChange: (Boolean) -> Unit,
    onToggleDragLock: () -> Unit,
    onRightDownChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val latestOnAirHold by rememberUpdatedState(onAirHoldPressChange)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Primary Left Click
        HoldableMouseButton(
            label = "Sol Tık",
            bgColor = Color(0xFF192740),
            borderColor = Color(0xFF33496D),
            pressedColor = NeonCyan.copy(alpha = 0.34f),
            fgColor = Color.White,
            onPressChange = { down ->
                if (down) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onLeftDownChange(down)
            },
            modifier = Modifier
                .weight(1.65f)
                .fillMaxHeight()
                .testTag("mouse_left_btn")
        )

        // Ergonomic Center Thumb Air Mouse Hold Trigger (Hold & wave phone!)
        if (isGyroSupported) {
            val airActive = isAirHoldPressed || isAirContinuous
            val airScale by animateFloatAsState(
                targetValue = if (isAirHoldPressed) 0.94f else 1f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 700f),
                label = "air_btn_scale"
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = when {
                    isAirContinuous && isAirHoldPressed -> Color(0xFF28354D)
                    isAirHoldPressed -> AmberGold
                    isAirContinuous -> NeonCyan.copy(alpha = 0.22f)
                    else -> Color(0xFF122136)
                },
                border = BorderStroke(
                    width = if (airActive) 1.5.dp else 1.dp,
                    color = when {
                        isAirHoldPressed -> AmberGold
                        isAirContinuous -> NeonCyan
                        else -> NeonCyan.copy(alpha = 0.45f)
                    }
                ),
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .scale(airScale)
                    .clip(RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            latestOnAirHold(true)
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.none { it.pressed }) break
                                }
                            } finally {
                                latestOnAirHold(false)
                            }
                        }
                    }
                    .testTag("air_mouse_hold_btn")
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotation,
                        contentDescription = "Air Mouse Tut",
                        tint = if (isAirHoldPressed && !isAirContinuous) Color(0xFF042F2E) else NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = when {
                            isAirContinuous && isAirHoldPressed -> "Duraklat"
                            isAirHoldPressed -> "Oynat"
                            isAirContinuous -> "Air ●"
                            else -> "Air Tut"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isAirHoldPressed && !isAirContinuous) Color(0xFF042F2E) else Color.White,
                        maxLines = 1
                    )
                }
            }
        }

        // Drag Lock Toggle
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isDragLocked) AmberGold else Color(0xFF131D30),
            border = BorderStroke(1.dp, if (isDragLocked) AmberGold else BorderSubtle),
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleDragLock()
                }
                .testTag("mouse_drag_lock_btn")
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = "Sürükle Kilidi",
                    tint = if (isDragLocked) Color(0xFF042F2E) else AmberGold.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isDragLocked) "Kilitli" else "Sürükle",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDragLocked) Color(0xFF042F2E) else Color(0xFFCBD5E1),
                    maxLines = 1
                )
            }
        }

        // Primary Right Click
        HoldableMouseButton(
            label = "Sağ Tık",
            bgColor = Color(0xFF192740),
            borderColor = NeonCyan.copy(alpha = 0.4f),
            pressedColor = NeonCyan.copy(alpha = 0.34f),
            fgColor = NeonCyan,
            onPressChange = { down ->
                if (down) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onRightDownChange(down)
            },
            modifier = Modifier
                .weight(1.45f)
                .fillMaxHeight()
                .testTag("mouse_right_btn")
        )
    }
}

@Composable
private fun HoldableMouseButton(
    label: String,
    bgColor: Color,
    borderColor: Color,
    pressedColor: Color,
    fgColor: Color,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    val latestOnPressChange by rememberUpdatedState(onPressChange)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 700f),
        label = "mouse_btn_scale"
    )

    DisposableEffect(Unit) {
        onDispose {
            if (isPressed) {
                isPressed = false
                latestOnPressChange(false)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPressed) pressedColor else bgColor,
        border = BorderStroke(if (isPressed) 1.5.dp else 1.dp, if (isPressed) NeonCyan else borderColor),
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
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
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Interactive Gesture & Shortcut Guide dialog.
 */
@Composable
fun TouchpadGestureGuideDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E1627),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCBD5E1),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.TouchApp, contentDescription = null, tint = NeonCyan)
                Text("Hareketler & Kısayollar", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GestureGuideRow("Alt Barı Sola / Sağa Kaydır (Döndür)", "Kumanda ↔ Klavye ↔ 123 ↔ Oyun modları arasında tek parmakla anında geçiş yapar", NeonCyan)
                GestureGuideRow("Çift Tıkla & Tut + Sürükle", "Pencere, dosya veya metni basılı tutup sürükler (kenarda otomatik kayar)", AmberGold)
                GestureGuideRow("1 / 2 / 3 Parmak Dokunuş", "1 = Sol Tık · 2 = Sağ Tık · 3 = Orta Tık", NeonCyan)
                GestureGuideRow("2 Parmak Kaydır & Kıstır", "Pürüzsüz kaydırma ve Yakınlaştır / Uzaklaştır (Ctrl+Tekerlek)", NeonCyan)
                GestureGuideRow("3 Parmak Kaydırma", "Sol/Sağ = Geri/İleri · Yukarı = Pencereler · Aşağı = Masaüstü", VioletAccent)
                HorizontalDivider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 2.dp))
                GestureGuideRow("10'lu Geniş Klavye (Uzun Basım)", "g→ğ, u→ü, s→ş, o→ö, c→ç harflerini uzun basarak yazın veya 12'li TR-Q'ya geçin", NeonCyan)
                GestureGuideRow("Boşluk Tuşunda Kaydır", "PC metin imlecini hassas şekilde sola/sağa hareket ettirir", NeonCyan)
                HorizontalDivider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 2.dp))
                GestureGuideRow("Kilit Ekranı & Yan Ses Tuşları", "Kilit açmadan kullanın; telefon ses tuşları PC sesini (Oyun modunda Ateş/Nişan) yönetir", AmberGold)
                GestureGuideRow("Yüzen Joystick & 1-Tık Yatay Oyun", "Sol tarafta dokunduğun yer joystick merkezi olur; sağ taraf tam alan FPS kamera + tetikler", Color(0xFFFF4D6D))
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF00382E))
            ) {
                Text("Anladım", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun GestureGuideRow(title: String, detail: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF162033),
        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent)
            Text(text = detail, fontSize = 11.sp, color = Color(0xFFCBD5E1))
        }
    }
}
