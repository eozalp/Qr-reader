package com.example.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.ui.theme.AmberGold
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure-Kotlin anti-burn-in pixel shift calculator for OLED Always-On Remote Mode.
 * Cycles through bounded sub-centimeter positions once per minute so static glyphs
 * never stress the same AMOLED subpixels while avoiding any 60/120Hz animation frames.
 */
object OledBurnInShift {
    private val pattern = arrayOf(
        0 to 0,
        6 to -4,
        12 to 2,
        8 to 7,
        -4 to 6,
        -11 to 2,
        -9 to -5,
        -2 to -7,
        5 to 4,
        -6 to -2
    )

    fun offsetDpForMinute(minuteTick: Int): Pair<Int, Int> {
        val idx = minuteTick.mod(pattern.size)
        return pattern[idx]
    }
}

private val AodPureBlack = Color(0xFF000000)
private val AodHairline = Color(0xFF1A2332)
private val AodMutedGlyph = Color(0xFF52647D)
private val AodClockText = Color(0xFF7D92B0)
private val AodSubtleCyan = Color(0xFF22A699)

/**
 * True-Black Functional OLED Always-On Remote Overlay ("OLED AOD Kumanda"):
 * - Renders >95% pure `#000000` black pixels (physically OFF on Samsung/AMOLED panels)
 * - Runs at minimum hardware brightness with zero idle animations (LTPO drops to 10Hz)
 * - Shifts static clock & control icons every 60s to prevent OLED burn-in
 * - Keeps the entire black surface active as a full-featured blind laptop Touchpad
 *   (1-finger move/tap/double-tap/tap-drag, 2-finger right-click/scroll/pinch, 3-finger swipe)
 *   plus faint hairline Left/Right click and Media/Volume controls!
 */
@Composable
fun OledAodRemoteOverlay(
    hidManager: BluetoothHidManager,
    connectionStatus: HidConnectionStatus,
    connectedDeviceName: String?,
    touchpadSpeed: Float,
    naturalScroll: Boolean,
    isRoomBrightBoost: Boolean,
    onToggleBrightnessBoost: () -> Unit,
    onConsumerControl: (Int) -> Unit,
    onZoomWheel: (Int) -> Unit,
    onThreeFingerSwipe: (ThreeFingerSwipeDirection) -> Unit,
    onFullscreenToggle: () -> Unit,
    onOpenBluetoothSheet: () -> Unit,
    onDisconnectedTap: () -> Unit,
    onExitAodMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val isConnected = connectionStatus == HidConnectionStatus.CONNECTED

    BackHandler(enabled = true) {
        onExitAodMode()
    }

    // Minute-aligned clock + anti-burn-in drift ticker (wakes ONLY once every 60 seconds!)
    var clockText by remember { mutableStateOf(formatCurrentTime()) }
    var burnInStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = formatCurrentTime()
            val nowMs = System.currentTimeMillis()
            val msUntilNextMinute = (60_000L - (nowMs % 60_000L)).coerceIn(500L, 60_000L)
            delay(msUntilNextMinute)
            burnInStep++
        }
    }

    val (shiftXDp, shiftYDp) = remember(burnInStep) { OledBurnInShift.offsetDpForMinute(burnInStep) }
    val shiftXPx = with(density) { shiftXDp.dp.toPx().roundToInt() }
    val shiftYPx = with(density) { shiftYDp.dp.toPx().roundToInt() }

    val tapSlopPx = with(density) { 12.dp.toPx() }
    val scrollStepPx = with(density) { 14.dp.toPx() }
    val pinchStepPx = with(density) { 20.dp.toPx() }
    val threeFingerThresholdPx = with(density) { 44.dp.toPx() }

    val gestureEngine = remember { TouchpadGestureEngine() }
    LaunchedEffect(density.density, touchpadSpeed, naturalScroll, tapSlopPx, scrollStepPx, pinchStepPx, threeFingerThresholdPx) {
        gestureEngine.config = TouchpadConfig(
            density = density.density,
            speedMultiplier = touchpadSpeed,
            naturalScroll = naturalScroll,
            tapSlopPx = tapSlopPx,
            scrollStepPx = scrollStepPx,
            pinchStepPx = pinchStepPx,
            threeFingerSwipeThresholdPx = threeFingerThresholdPx
        )
    }

    val activeFingerList = remember { ArrayList<Offset>(5) }
    var activeFingerCount by remember { mutableIntStateOf(0) }
    var isTapDragging by remember { mutableStateOf(false) }
    val overlayDrawTick = remember { mutableIntStateOf(0) }
    var pendingTapWakeTick by remember { mutableIntStateOf(0) }

    var faintStatusToast by remember { mutableStateOf<String?>(null) }
    var toastSeq by remember { mutableIntStateOf(0) }
    fun flashFaintBadge(label: String) {
        faintStatusToast = label
        toastSeq++
    }
    LaunchedEffect(toastSeq) {
        if (toastSeq > 0) {
            delay(950L)
            faintStatusToast = null
        }
    }

    val latestOnZoomWheel by rememberUpdatedState(onZoomWheel)
    val latestOnThreeFingerSwipe by rememberUpdatedState(onThreeFingerSwipe)
    val latestOnDisconnectedTap by rememberUpdatedState(onDisconnectedTap)

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
                        flashFaintBadge(if (action.wheelSteps > 0) "Yakınlaştır" else "Uzaklaştır")
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
                        flashFaintBadge("Çift Tık")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.RightClick -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_RIGHT)
                        flashFaintBadge("Sağ Tık")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.MiddleClick -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hidManager.sendMouseClick(BluetoothHidManager.MOUSE_BTN_MIDDLE)
                        flashFaintBadge("Orta Tık")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.TapDragStart -> {
                    isTapDragging = true
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, true)
                        flashFaintBadge("Sürükle Aktif")
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
                TouchpadAction.TapDragEnd -> {
                    isTapDragging = false
                    if (hidManager.isHidHostConnected()) {
                        hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, false)
                    }
                }
                is TouchpadAction.ThreeFingerSwipe -> {
                    if (hidManager.isHidHostConnected()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnThreeFingerSwipe(action.direction)
                        flashFaintBadge(
                            when (action.direction) {
                                ThreeFingerSwipeDirection.LEFT -> "Geri"
                                ThreeFingerSwipeDirection.RIGHT -> "İleri"
                                ThreeFingerSwipeDirection.UP -> "Pencereler"
                                ThreeFingerSwipeDirection.DOWN -> "Masaüstü"
                            }
                        )
                    } else {
                        latestOnDisconnectedTap()
                    }
                }
            }
        }
    }

    // Pure state-driven zero-CPU idle timer loop for single-tap flush & tap-drag hold confirmation
    val hasActiveTouch = activeFingerCount > 0
    LaunchedEffect(gestureEngine, hasActiveTouch, isTapDragging, pendingTapWakeTick) {
        while (
            activeFingerCount > 0 ||
            isTapDragging ||
            gestureEngine.isTapDragging ||
            gestureEngine.hasPendingSingleTap()
        ) {
            delay(24L)
            val now = System.currentTimeMillis()
            gestureEngine.flushPendingSingleTapIfExpired(now)?.let { action ->
                dispatchActions(listOf(action))
            }
            val holdActions = gestureEngine.onHoldTick(now)
            if (holdActions.isNotEmpty()) {
                dispatchActions(holdActions)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val cleanup = gestureEngine.reset()
            if (cleanup.contains(TouchpadAction.TapDragEnd)) {
                hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, false)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AodPureBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("oled_aod_remote_overlay"),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Minimalist Anti-Burn-In AOD Clock + Connection + Exit Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(shiftXPx, shiftYPx) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (!isConnected) {
                            onExitAodMode()
                            onOpenBluetoothSheet()
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = clockText,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = AppMonoFontFamily,
                    color = AodClockText,
                    letterSpacing = 1.5.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) AodSubtleCyan else AmberGold.copy(alpha = 0.6f))
                    )
                    Text(
                        text = if (isConnected) (connectedDeviceName ?: "PC Bağlı") else "PC Bağla",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isConnected) AodMutedGlyph else AmberGold.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dim / Room-Dim brightness toggle (keeps pure #000000 black!)
                AodIconCircleButton(
                    icon = if (isRoomBrightBoost) Icons.Default.BrightnessLow else Icons.Default.Brightness2,
                    contentDescription = if (isRoomBrightBoost) "Ultra Kısık AOD Parlaklık" else "Oda Parlaklığı",
                    tint = if (isRoomBrightBoost) AodSubtleCyan else AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleBrightnessBoost()
                    },
                    modifier = Modifier.testTag("oled_aod_brightness_btn")
                )

                // Wake back to full Remote Cockpit
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = AodPureBlack,
                    border = BorderStroke(1.dp, AodHairline),
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onExitAodMode()
                        }
                        .testTag("oled_aod_exit_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WbSunny,
                            contentDescription = "Tam Ekrana Dön",
                            tint = AodMutedGlyph,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Uyan",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AodClockText
                        )
                    }
                }
            }
        }

        // 2. Massive Pitch-Black Blind Touchpad + Faint Right Edge Scroll Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(hidManager, gestureEngine) {
                        val reusablePoints = ArrayList<TouchpadPoint>(5)
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            activeFingerList.clear()
                            activeFingerList.add(firstDown.position)
                            activeFingerCount = 1
                            overlayDrawTick.intValue++

                            dispatchActions(
                                gestureEngine.onGestureDown(
                                    TouchpadPoint(firstDown.position.x, firstDown.position.y),
                                    System.currentTimeMillis()
                                )
                            )

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
                                    if (activeFingerCount != count) activeFingerCount = count
                                    overlayDrawTick.intValue++

                                    if (count == 0) {
                                        dispatchActions(gestureEngine.onAllPointersUp(System.currentTimeMillis()))
                                        if (gestureEngine.hasPendingSingleTap()) {
                                            pendingTapWakeTick++
                                        }
                                        break
                                    }

                                    when (event.type) {
                                        PointerEventType.Press -> dispatchActions(gestureEngine.onPointerAdded(reusablePoints))
                                        PointerEventType.Release -> gestureEngine.onPointerRemoved(reusablePoints)
                                        PointerEventType.Move -> {
                                            dispatchActions(gestureEngine.onMove(reusablePoints, System.currentTimeMillis()))
                                            for (i in changes.indices) {
                                                if (changes[i].pressed) changes[i].consume()
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            } finally {
                                activeFingerList.clear()
                                activeFingerCount = 0
                                overlayDrawTick.intValue++
                                if (gestureEngine.isTapDragging) {
                                    dispatchActions(gestureEngine.reset())
                                }
                            }
                        }
                    }
                    .testTag("oled_aod_touchpad"),
                contentAlignment = Alignment.Center
            ) {
                // Only draw faint finger rings WHILE touching — 100% pixels OFF when untouched!
                if (activeFingerCount > 0) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        @Suppress("UNUSED_VARIABLE")
                        val tick = overlayDrawTick.intValue
                        val ringColor = if (isTapDragging) AmberGold.copy(alpha = 0.45f) else AodSubtleCyan.copy(alpha = 0.42f)
                        val r = 24.dp.toPx()
                        val sw = 1.4.dp.toPx()
                        for (i in 0 until activeFingerList.size) {
                            val pos = activeFingerList[i]
                            drawCircle(
                                color = ringColor,
                                radius = r,
                                center = pos,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
                            )
                        }
                    }
                }

                // Ultra-faint shifted hint text (only a few dim pixels)
                if (activeFingerCount == 0 && faintStatusToast == null) {
                    Text(
                        text = "Dokun & Kaydır · OLED Touchpad",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF283447),
                        modifier = Modifier.offset { IntOffset(shiftXPx, -shiftYPx) }
                    )
                }

                if (faintStatusToast != null) {
                    Text(
                        text = faintStatusToast.orEmpty(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AodSubtleCyan.copy(alpha = 0.78f),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    )
                }
            }

            // Invisible/ultra-faint right-edge 1-finger scroll strip
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .fillMaxHeight()
                    .pointerInput(hidManager, naturalScroll) {
                        var scrollAccum = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { scrollAccum = 0f },
                            onDragCancel = { scrollAccum = 0f }
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
                    .testTag("oled_aod_scroll_strip"),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .width(4.dp)
                        .height(52.dp)
                        .offset { IntOffset(0, shiftYPx) }
                ) {
                    drawLine(
                        color = Color(0xFF1D293B),
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }

        // 3. Minimalist Hairline Mouse Click + Media Control Footer (burn-in shifted)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(-shiftXPx / 2, shiftYPx / 2) },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AodClickButton(
                    label = "Sol Tık",
                    onDownChange = { down ->
                        if (down && !hidManager.isHidHostConnected()) {
                            onDisconnectedTap()
                        } else {
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_LEFT, down)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("oled_aod_left_click")
                )
                AodClickButton(
                    label = "Sağ Tık",
                    onDownChange = { down ->
                        if (down && !hidManager.isHidHostConnected()) {
                            onDisconnectedTap()
                        } else {
                            hidManager.setMouseButton(BluetoothHidManager.MOUSE_BTN_RIGHT, down)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("oled_aod_right_click")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AodIconCircleButton(
                    icon = Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Sessiz",
                    tint = AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onConsumerControl(BluetoothHidManager.CONSUMER_MUTE)
                    }
                )
                AodIconCircleButton(
                    icon = Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = "Sesi Azalt",
                    tint = AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onConsumerControl(BluetoothHidManager.CONSUMER_VOL_DOWN)
                    }
                )
                AodIconCircleButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "Önceki",
                    tint = AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onConsumerControl(BluetoothHidManager.CONSUMER_PREV)
                    }
                )
                AodIconCircleButton(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Oynat / Duraklat",
                    tint = NeonCyan.copy(alpha = 0.65f),
                    borderColor = AodSubtleCyan.copy(alpha = 0.35f),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConsumerControl(BluetoothHidManager.CONSUMER_PLAY_PAUSE)
                    },
                    modifier = Modifier.testTag("oled_aod_play_pause")
                )
                AodIconCircleButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "Sonraki",
                    tint = AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onConsumerControl(BluetoothHidManager.CONSUMER_NEXT)
                    }
                )
                AodIconCircleButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Sesi Artır",
                    tint = AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onConsumerControl(BluetoothHidManager.CONSUMER_VOL_UP)
                    }
                )
                AodIconCircleButton(
                    icon = Icons.Default.Fullscreen,
                    contentDescription = "Tam Ekran",
                    tint = AodMutedGlyph,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onFullscreenToggle()
                    }
                )
            }
        }
    }
}

@Composable
private fun AodClickButton(
    label: String,
    onDownChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val latestOnDown by rememberUpdatedState(onDownChange)

    DisposableEffect(Unit) {
        onDispose {
            if (isPressed) {
                isPressed = false
                latestOnDown(false)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AodPureBlack,
        border = BorderStroke(
            width = 1.dp,
            color = if (isPressed) AodSubtleCyan.copy(alpha = 0.7f) else AodHairline
        ),
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestOnDown(true)
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                    } finally {
                        if (isPressed) {
                            isPressed = false
                            latestOnDown(false)
                        }
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isPressed) AodSubtleCyan else AodMutedGlyph
            )
        }
    }
}

@Composable
private fun AodIconCircleButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    borderColor: Color = AodHairline,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = AodPureBlack,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatCurrentTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}
