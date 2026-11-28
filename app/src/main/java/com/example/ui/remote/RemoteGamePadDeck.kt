package com.example.ui.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class GamepadPreset(val label: String) {
    FPS_WASD("FPS / TPS"),
    RACING_ARROWS("Yarış")
}

data class StickKeyOutput(
    val keys: Set<Byte>,
    val sprintShift: Boolean,
    val directionLabel: String
)

/**
 * Pure-Kotlin analog stick to 8-direction HID key mapper with outer-ring Auto-Sprint.
 */
object AnalogWasdMapper {
    fun mapStick(
        nx: Float,
        ny: Float,
        preset: GamepadPreset,
        deadzone: Float = 0.22f,
        autoSprintEnabled: Boolean = true,
        sprintThreshold: Float = 0.84f
    ): StickKeyOutput {
        val mag = hypot(nx, ny)
        if (mag < deadzone) {
            return StickKeyOutput(emptySet(), false, "MERKEZ")
        }

        val upKey = if (preset == GamepadPreset.FPS_WASD) BluetoothHidManager.KEY_W else BluetoothHidManager.KEY_UP
        val downKey = if (preset == GamepadPreset.FPS_WASD) BluetoothHidManager.KEY_S else BluetoothHidManager.KEY_DOWN
        val leftKey = if (preset == GamepadPreset.FPS_WASD) BluetoothHidManager.KEY_A else BluetoothHidManager.KEY_LEFT
        val rightKey = if (preset == GamepadPreset.FPS_WASD) BluetoothHidManager.KEY_D else BluetoothHidManager.KEY_RIGHT

        // Screen Y points down, so negative ny = Forward/Up
        val angleDeg = Math.toDegrees(atan2(-ny.toDouble(), nx.toDouble()))
        val normAngle = if (angleDeg < 0.0) angleDeg + 360.0 else angleDeg

        val keys = LinkedHashSet<Byte>(2)
        val dirLabel: String
        when {
            normAngle >= 337.5 || normAngle < 22.5 -> {
                keys.add(rightKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "D" else "→"
            }
            normAngle < 67.5 -> {
                keys.add(upKey); keys.add(rightKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "W+D" else "↑→"
            }
            normAngle < 112.5 -> {
                keys.add(upKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "W" else "↑"
            }
            normAngle < 157.5 -> {
                keys.add(upKey); keys.add(leftKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "W+A" else "↑←"
            }
            normAngle < 202.5 -> {
                keys.add(leftKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "A" else "←"
            }
            normAngle < 247.5 -> {
                keys.add(downKey); keys.add(leftKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "S+A" else "↓←"
            }
            normAngle < 292.5 -> {
                keys.add(downKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "S" else "↓"
            }
            else -> {
                keys.add(downKey); keys.add(rightKey); dirLabel = if (preset == GamepadPreset.FPS_WASD) "S+D" else "↓→"
            }
        }

        val isForward = upKey in keys
        val sprint = autoSprintEnabled && preset == GamepadPreset.FPS_WASD && isForward && mag >= sprintThreshold
        return StickKeyOutput(keys = keys, sprintShift = sprint, directionLabel = if (sprint) "$dirLabel ⚡" else dirLabel)
    }
}

private val GameAccentCoral = Color(0xFFFF4D6D)
private val GameAccentEmerald = Color(0xFF10B981)
private val GameAccentViolet = Color(0xFFA78BFA)

/**
 * Dual-Thumb PUBG/Steam-Link Standard 6KRO Gaming Cockpit:
 * - Left Zone: Floating Dynamic Analog WASD/Arrow Stick (wherever your thumb lands becomes origin!)
 * - Right Zone: Full-Area 1:1 Raw FPS Camera Aim Surface WITH translucent floating thumb-arc triggers overlaid
 *   so 2 thumbs effortlessly Move + Aim + Shoot simultaneously in BOTH Portrait & 1-Tap Landscape!
 * - Zero duplicate chrome, non-clipped header strip, and 1-tap Screen Orientation rotation ("Yatay").
 */
@Composable
fun RemoteGamePadDeck(
    hidManager: BluetoothHidManager,
    isGyroAimEnabled: Boolean,
    isLandscapeLocked: Boolean,
    onToggleGyroAim: () -> Unit,
    onToggleLandscape: () -> Unit,
    onDisconnectedTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var preset by remember { mutableStateOf(GamepadPreset.FPS_WASD) }
    var autoSprint by remember { mutableStateOf(true) }
    var aimSensitivity by remember { mutableFloatStateOf(1.35f) }

    val stickKeys = remember { mutableStateOf<Set<Byte>>(emptySet()) }
    var stickSprint by remember { mutableStateOf(false) }
    val buttonKeys = remember { mutableStateMapOf<Byte, Boolean>() }
    val buttonModifiers = remember { mutableStateMapOf<Byte, Boolean>() }

    fun syncLiveHidKeys() {
        val combinedKeys = LinkedHashSet<Byte>()
        combinedKeys.addAll(stickKeys.value)
        buttonKeys.forEach { (k, down) -> if (down) combinedKeys.add(k) }

        var modMask = 0
        if (stickSprint) modMask = modMask or BluetoothHidManager.MODIFIER_SHIFT.toInt()
        buttonModifiers.forEach { (m, down) -> if (down) modMask = modMask or m.toInt() }

        hidManager.updateLiveKeyboardState(combinedKeys, modMask.toByte())
    }

    fun setActionKey(keyCode: Byte, isDown: Boolean) {
        if (isDown && !hidManager.isHidHostConnected()) {
            onDisconnectedTap()
            return
        }
        buttonKeys[keyCode] = isDown
        syncLiveHidKeys()
    }

    fun setActionModifier(modBit: Byte, isDown: Boolean) {
        if (isDown && !hidManager.isHidHostConnected()) {
            onDisconnectedTap()
            return
        }
        buttonModifiers[modBit] = isDown
        syncLiveHidKeys()
    }

    fun setMouseTrigger(buttonMask: Int, isDown: Boolean) {
        if (isDown && !hidManager.isHidHostConnected()) {
            onDisconnectedTap()
            return
        }
        hidManager.setMouseButton(buttonMask, isDown)
    }

    DisposableEffect(Unit) {
        onDispose {
            hidManager.releaseAllLiveKeys()
            hidManager.releaseAllMouseButtons()
        }
    }

    Column(
        modifier = modifier.testTag("remote_gamepad_deck"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Slim, non-clipped Gaming Control Ribbon (Presets + 1-Tap Landscape Rotation + Hotbar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GamepadPreset.entries.forEach { option ->
                val selected = preset == option
                GameChipPill(
                    label = option.label,
                    isActive = selected,
                    activeColor = NeonCyan,
                    onClick = {
                        preset = option
                        stickKeys.value = emptySet()
                        stickSprint = false
                        syncLiveHidKeys()
                    },
                    modifier = Modifier.testTag("game_preset_${option.name.lowercase(java.util.Locale.ROOT)}")
                )
            }

            // 1-Tap Landscape Orientation Lock ("Yatay Döndür") for true console ergonomics!
            GameChipPill(
                label = if (isLandscapeLocked) "⟲ Dikey" else "⟲ Yatay",
                isActive = isLandscapeLocked,
                activeColor = GameAccentCoral,
                onClick = onToggleLandscape,
                modifier = Modifier.testTag("game_landscape_toggle_btn")
            )

            if (preset == GamepadPreset.FPS_WASD) {
                GameChipPill(
                    label = "⚡ Koş",
                    isActive = autoSprint,
                    activeColor = AmberGold,
                    onClick = { autoSprint = !autoSprint }
                )
            }

            GameChipPill(
                label = "🎯 Gyro",
                isActive = isGyroAimEnabled,
                activeColor = GameAccentViolet,
                onClick = onToggleGyroAim
            )

            val hotbarKeys = remember {
                listOf(
                    "Esc" to BluetoothHidManager.KEY_ESCAPE,
                    "Tab" to BluetoothHidManager.KEY_TAB,
                    "1" to BluetoothHidManager.KEY_1,
                    "2" to BluetoothHidManager.KEY_2,
                    "3" to BluetoothHidManager.KEY_3,
                    "4" to BluetoothHidManager.KEY_4,
                    "M" to BluetoothHidManager.KEY_M
                )
            }
            hotbarKeys.forEach { (lbl, code) ->
                LiveHoldKeyPill(
                    label = lbl,
                    onDownChange = { setActionKey(code, it) }
                )
            }
        }

        // Ergonomic Dual-Thumb Cockpit Body:
        // Left Side = Floating Dynamic Analog Stick
        // Right Side = Full-Area 1:1 FPS Aim Canvas with Floating Translucent Thumb-Arc Triggers overlaid!
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isLandscape = maxWidth > maxHeight * 1.05f

            if (preset == GamepadPreset.FPS_WASD) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FloatingAnalogStickZone(
                            preset = preset,
                            autoSprint = autoSprint,
                            onStickOutput = { out ->
                                if (out.keys.isNotEmpty() && !hidManager.isHidHostConnected()) {
                                    onDisconnectedTap()
                                    return@FloatingAnalogStickZone
                                }
                                stickKeys.value = out.keys
                                stickSprint = out.sprintShift
                                syncLiveHidKeys()
                            },
                            modifier = Modifier
                                .weight(0.95f)
                                .fillMaxHeight()
                        )

                        FpsAimWithFloatingTriggers(
                            hidManager = hidManager,
                            sensitivity = aimSensitivity,
                            onSensitivityCycle = {
                                aimSensitivity = when {
                                    aimSensitivity < 1.0f -> 1.35f
                                    aimSensitivity < 1.7f -> 2.0f
                                    else -> 0.85f
                                }
                            },
                            onFireChange = { setMouseTrigger(BluetoothHidManager.MOUSE_BTN_LEFT, it) },
                            onAimChange = { setMouseTrigger(BluetoothHidManager.MOUSE_BTN_RIGHT, it) },
                            onJumpChange = { setActionKey(BluetoothHidManager.KEY_SPACE, it) },
                            onCrouchChange = { setActionModifier(BluetoothHidManager.MODIFIER_LEFT_CTRL, it) },
                            onInteractChange = { setActionKey(BluetoothHidManager.KEY_E, it) },
                            onReloadChange = { setActionKey(BluetoothHidManager.KEY_R, it) },
                            onDisconnectedTap = onDisconnectedTap,
                            modifier = Modifier
                                .weight(1.35f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait Dual-Thumb Layout: Full-Area Aim + Floating Triggers on Right & Top, Floating Stick Bottom-Left!
                    // Solves the "3-hand" problem completely — right thumb can aim anywhere AND tap Fire/Aim/Jump cleanly!
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FpsAimWithFloatingTriggers(
                            hidManager = hidManager,
                            sensitivity = aimSensitivity,
                            onSensitivityCycle = {
                                aimSensitivity = when {
                                    aimSensitivity < 1.0f -> 1.35f
                                    aimSensitivity < 1.7f -> 2.0f
                                    else -> 0.85f
                                }
                            },
                            onFireChange = { setMouseTrigger(BluetoothHidManager.MOUSE_BTN_LEFT, it) },
                            onAimChange = { setMouseTrigger(BluetoothHidManager.MOUSE_BTN_RIGHT, it) },
                            onJumpChange = { setActionKey(BluetoothHidManager.KEY_SPACE, it) },
                            onCrouchChange = { setActionModifier(BluetoothHidManager.MODIFIER_LEFT_CTRL, it) },
                            onInteractChange = { setActionKey(BluetoothHidManager.KEY_E, it) },
                            onReloadChange = { setActionKey(BluetoothHidManager.KEY_R, it) },
                            onDisconnectedTap = onDisconnectedTap,
                            compactButtons = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1.1f)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FloatingAnalogStickZone(
                                preset = preset,
                                autoSprint = autoSprint,
                                onStickOutput = { out ->
                                    if (out.keys.isNotEmpty() && !hidManager.isHidHostConnected()) {
                                        onDisconnectedTap()
                                        return@FloatingAnalogStickZone
                                    }
                                    stickKeys.value = out.keys
                                    stickSprint = out.sprintShift
                                    syncLiveHidKeys()
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .fillMaxHeight()
                            )

                            // Right-thumb instant primary combat arc (Fire + Aim + Jump) right beside stick!
                            Column(
                                modifier = Modifier
                                    .weight(0.95f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TactileGameTrigger(
                                    label = "ATEŞ",
                                    subLabel = "Sol Tık",
                                    accent = GameAccentCoral,
                                    onDownChange = { setMouseTrigger(BluetoothHidManager.MOUSE_BTN_LEFT, it) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1.25f)
                                        .testTag("game_fire_btn")
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(0.9f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    TactileGameTrigger(
                                        label = "NİŞAN",
                                        subLabel = "Sağ",
                                        accent = NeonCyan,
                                        onDownChange = { setMouseTrigger(BluetoothHidManager.MOUSE_BTN_RIGHT, it) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .testTag("game_aim_btn")
                                    )
                                    TactileGameTrigger(
                                        label = "ZIPLA",
                                        subLabel = "Space",
                                        accent = GameAccentEmerald,
                                        onDownChange = { setActionKey(BluetoothHidManager.KEY_SPACE, it) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Racing / Arcade Mode (Side-by-side in both Portrait & Landscape!)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FloatingAnalogStickZone(
                        preset = preset,
                        autoSprint = false,
                        onStickOutput = { out ->
                            if (out.keys.isNotEmpty() && !hidManager.isHidHostConnected()) {
                                onDisconnectedTap()
                                return@FloatingAnalogStickZone
                            }
                            stickKeys.value = out.keys
                            stickSprint = false
                            syncLiveHidKeys()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    RacingPedalsCluster(
                        onGasChange = { setActionKey(BluetoothHidManager.KEY_UP, it) },
                        onBrakeChange = { setActionKey(BluetoothHidManager.KEY_DOWN, it) },
                        onNitroChange = { setActionModifier(BluetoothHidManager.MODIFIER_SHIFT, it) },
                        onHandbrakeChange = { setActionKey(BluetoothHidManager.KEY_SPACE, it) },
                        onActionA = { setActionKey(BluetoothHidManager.KEY_Z, it) },
                        onActionB = { setActionKey(BluetoothHidManager.KEY_X, it) },
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * Floating Dynamic Analog Stick:
 * Wherever your thumb touches down becomes the stick origin immediately — zero missed centers!
 */
@Composable
private fun FloatingAnalogStickZone(
    preset: GamepadPreset,
    autoSprint: Boolean,
    onStickOutput: (StickKeyOutput) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestOnStickOutput by rememberUpdatedState(onStickOutput)
    val latestPreset by rememberUpdatedState(preset)
    val latestAutoSprint by rememberUpdatedState(autoSprint)

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var originPos by remember { mutableStateOf<Offset?>(null) }
    var normX by remember { mutableFloatStateOf(0f) }
    var normY by remember { mutableFloatStateOf(0f) }
    var isEngaged by remember { mutableStateOf(false) }
    var activeLabel by remember { mutableStateOf(if (preset == GamepadPreset.FPS_WASD) "WASD" else "YÖN") }
    var isSprinting by remember { mutableStateOf(false) }

    val maxStickRadiusPx = with(density) { 64.dp.toPx() }

    val borderColor by animateColorAsState(
        targetValue = when {
            isSprinting -> AmberGold
            isEngaged -> NeonCyan
            else -> Color(0xFF25344E)
        },
        label = "floating_stick_border"
    )

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF132038), Color(0xFF090F1D))
                )
            )
            .border(1.3.dp, borderColor, RoundedCornerShape(22.dp))
            .pointerInput(maxStickRadiusPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val margin = maxStickRadiusPx * 0.65f
                    val clampedOrigin = Offset(
                        x = down.position.x.coerceIn(margin, (boxSize.width - margin).coerceAtLeast(margin)),
                        y = down.position.y.coerceIn(margin, (boxSize.height - margin).coerceAtLeast(margin))
                    )
                    originPos = clampedOrigin
                    isEngaged = true
                    var lastDir = ""

                    fun updateFromPos(pos: Offset) {
                        val origin = originPos ?: return
                        val maxR = minOf(maxStickRadiusPx, minOf(boxSize.width, boxSize.height) * 0.4f).coerceAtLeast(36f)
                        val rawDx = pos.x - origin.x
                        val rawDy = pos.y - origin.y
                        val dist = hypot(rawDx, rawDy)
                        val clampedDist = dist.coerceAtMost(maxR)
                        val angle = atan2(rawDy, rawDx)
                        val nx = if (dist > 0.001f) (cos(angle) * clampedDist) / maxR else 0f
                        val ny = if (dist > 0.001f) (sin(angle) * clampedDist) / maxR else 0f
                        normX = nx
                        normY = ny

                        val mapped = AnalogWasdMapper.mapStick(
                            nx = nx,
                            ny = ny,
                            preset = latestPreset,
                            autoSprintEnabled = latestAutoSprint
                        )
                        isSprinting = mapped.sprintShift
                        activeLabel = if (mapped.keys.isEmpty()) {
                            if (latestPreset == GamepadPreset.FPS_WASD) "WASD" else "YÖN"
                        } else mapped.directionLabel
                        if (mapped.directionLabel != lastDir && mapped.keys.isNotEmpty()) {
                            lastDir = mapped.directionLabel
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        latestOnStickOutput(mapped)
                    }

                    updateFromPos(down.position)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.firstOrNull { it.pressed } ?: break
                            if (event.type == PointerEventType.Move) {
                                updateFromPos(active.position)
                                active.consume()
                            }
                        }
                    } finally {
                        isEngaged = false
                        isSprinting = false
                        originPos = null
                        normX = 0f
                        normY = 0f
                        activeLabel = if (latestPreset == GamepadPreset.FPS_WASD) "WASD" else "YÖN"
                        latestOnStickOutput(StickKeyOutput(emptySet(), false, "MERKEZ"))
                    }
                }
            }
            .testTag("game_analog_stick"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val restCenter = Offset(size.width / 2f, size.height / 2f)
            val center = originPos ?: restCenter
            val outerR = minOf(maxStickRadiusPx, minOf(size.width, size.height) * 0.4f).coerceAtLeast(36f)
            val deadR = outerR * 0.24f
            val activeAccent = if (isSprinting) AmberGold else NeonCyan

            // Outer ring
            drawCircle(
                color = if (isSprinting) AmberGold.copy(alpha = 0.65f) else if (isEngaged) NeonCyan.copy(alpha = 0.45f) else Color(0xFF283A5A),
                radius = outerR,
                center = center,
                style = Stroke(width = if (isSprinting) 2.5.dp.toPx() else 1.5.dp.toPx())
            )
            // Mid ring
            drawCircle(
                color = Color(0xFF1B2942),
                radius = outerR * 0.62f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            // Deadzone ring
            drawCircle(
                color = Color(0xFF223250),
                radius = deadR,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            val thumbPos = Offset(center.x + normX * outerR, center.y + normY * outerR)

            if (isEngaged) {
                drawLine(
                    color = activeAccent.copy(alpha = 0.65f),
                    start = center,
                    end = thumbPos,
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Layered translucent circles avoid allocating a fresh RadialGradient shader on every 120Hz frame
            drawCircle(
                color = activeAccent.copy(alpha = if (isEngaged) 0.16f else 0.07f),
                radius = 34.dp.toPx(),
                center = thumbPos
            )
            drawCircle(
                color = activeAccent.copy(alpha = if (isEngaged) 0.30f else 0.12f),
                radius = 26.dp.toPx(),
                center = thumbPos
            )
            drawCircle(
                color = if (isEngaged) activeAccent else Color(0xFF1F324D),
                radius = 21.dp.toPx(),
                center = thumbPos
            )
            drawCircle(
                color = Color(0xFF0A101E).copy(alpha = 0.55f),
                radius = 11.dp.toPx(),
                center = thumbPos
            )
        }

        // Live badge + floating hint
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isSprinting) AmberGold.copy(alpha = 0.22f) else Color(0xFF0D1526).copy(alpha = 0.85f),
            border = BorderStroke(0.8.dp, if (isSprinting) AmberGold else Color(0xFF283A5A)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Text(
                text = activeLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isSprinting) AmberGold else NeonCyan,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }

        if (!isEngaged) {
            Text(
                text = "Yüzen Joystick",
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF53698B),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

/**
 * Full-Area 1:1 Raw FPS Aim Pad with floating semi-transparent thumb-arc buttons overlaid directly
 * on top (PUBG/COD Mobile standard — aim anywhere AND press/hold triggers seamlessly!).
 */
@Composable
private fun FpsAimWithFloatingTriggers(
    hidManager: BluetoothHidManager,
    sensitivity: Float,
    onSensitivityCycle: () -> Unit,
    onFireChange: (Boolean) -> Unit,
    onAimChange: (Boolean) -> Unit,
    onJumpChange: (Boolean) -> Unit,
    onCrouchChange: (Boolean) -> Unit,
    onInteractChange: (Boolean) -> Unit,
    onReloadChange: (Boolean) -> Unit,
    onDisconnectedTap: () -> Unit,
    compactButtons: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val latestSens by rememberUpdatedState(sensitivity)
    val latestDisconnected by rememberUpdatedState(onDisconnectedTap)
    // Separate boolean border flag from 120Hz touch Offset so moving aim NEVER recomposes floating triggers!
    var isAiming by remember { mutableStateOf(false) }
    val activeTouchState = remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0xFF10192D), Color(0xFF080E1B)))
            )
            .drawWithCache {
                if (size.width <= 1f || size.height <= 1f) {
                    return@drawWithCache onDrawBehind {}
                }
                val c = Offset(size.width * 0.36f, size.height * 0.5f)
                val ringR = 24.dp.toPx()
                val outerGap = 34.dp.toPx()
                val innerGap = 9.dp.toPx()
                val strokePx = 1.dp.toPx()
                val ringStroke = Stroke(strokePx)
                val crosshairColor = Color(0xFF1E2E4A)
                onDrawBehind {
                    drawCircle(color = crosshairColor, radius = ringR, center = c, style = ringStroke)
                    drawLine(color = crosshairColor, start = Offset(c.x - outerGap, c.y), end = Offset(c.x - innerGap, c.y), strokeWidth = strokePx)
                    drawLine(color = crosshairColor, start = Offset(c.x + innerGap, c.y), end = Offset(c.x + outerGap, c.y), strokeWidth = strokePx)
                    drawLine(color = crosshairColor, start = Offset(c.x, c.y - outerGap), end = Offset(c.x, c.y - innerGap), strokeWidth = strokePx)
                    drawLine(color = crosshairColor, start = Offset(c.x, c.y + innerGap), end = Offset(c.x, c.y + outerGap), strokeWidth = strokePx)
                }
            }
            .border(
                width = 1.2.dp,
                color = if (isAiming) NeonCyan else Color(0xFF25344E),
                shape = RoundedCornerShape(22.dp)
            )
            .pointerInput(hidManager) {
                var accumX = 0f
                var accumY = 0f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPos = down.position
                    isAiming = true
                    activeTouchState.value = down.position
                    accumX = 0f
                    accumY = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.firstOrNull { it.pressed } ?: break
                            if (event.type == PointerEventType.Move) {
                                activeTouchState.value = active.position
                                val dx = active.position.x - lastPos.x
                                val dy = active.position.y - lastPos.y
                                lastPos = active.position
                                if (!hidManager.isHidHostConnected()) {
                                    latestDisconnected()
                                } else {
                                    val scale = (latestSens * 1.45f) / (density.density * 0.6f)
                                    accumX += dx * scale
                                    accumY += dy * scale
                                    val sx = accumX.toInt()
                                    val sy = accumY.toInt()
                                    if (sx != 0 || sy != 0) {
                                        accumX -= sx
                                        accumY -= sy
                                        hidManager.sendMouseMove(sx, sy)
                                    }
                                }
                                active.consume()
                            }
                        }
                    } finally {
                        isAiming = false
                        activeTouchState.value = null
                    }
                }
            }
            .testTag("game_fps_aim_pad")
    ) {
        // Draw-phase-only live aim halo (0 recompositions during 120Hz camera sweeps)
        if (isAiming) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                activeTouchState.value?.let { pos ->
                    drawCircle(
                        color = NeonCyan.copy(alpha = 0.14f),
                        radius = 34.dp.toPx(),
                        center = pos
                    )
                    drawCircle(
                        color = NeonCyan.copy(alpha = 0.28f),
                        radius = 18.dp.toPx(),
                        center = pos
                    )
                }
            }
        }

        // Top-Left Label + DPI Speed Toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF132036).copy(alpha = 0.88f),
                border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSensitivityCycle)
            ) {
                Text(
                    text = "DPI ${String.format(java.util.Locale.US, "%.1fx", sensitivity)}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                text = "1:1 FPS Kamera",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF53698B)
            )
        }

        // Floating Translucent Thumb-Arc Utility / Combat Overlay on the Right Edge!
        if (compactButtons) {
            // In Portrait upper Aim Pad, float compact secondary utility pills (E, R, Eğil) on the right edge
            // so the big open area stays pure Aim while primary Fire/Aim/Jump sit beside the stick below!
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp, top = 28.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                FloatingRoundTrigger("E", AmberGold, onInteractChange)
                FloatingRoundTrigger("R", GameAccentViolet, onReloadChange)
                FloatingRoundTrigger("Eğil", Color(0xFF94A3B8), onCrouchChange)
            }
        } else {
            // In Landscape full-screen gaming mode, put the full PUBG/COD ergonomic right-thumb arc directly over the right side!
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FloatingRoundTrigger("E", AmberGold, onInteractChange)
                    FloatingRoundTrigger("R", GameAccentViolet, onReloadChange)
                    FloatingRoundTrigger("NİŞAN", NeonCyan, onAimChange, sizeDp = 56, tag = "game_aim_btn")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    FloatingRoundTrigger("EĞİL", Color(0xFF94A3B8), onCrouchChange)
                    FloatingRoundTrigger("ZIPLA", GameAccentEmerald, onJumpChange, sizeDp = 54)
                    FloatingRoundTrigger("ATEŞ", GameAccentCoral, onFireChange, sizeDp = 68, tag = "game_fire_btn")
                }
            }
        }
    }
}

@Composable
private fun FloatingRoundTrigger(
    label: String,
    accent: Color,
    onDownChange: (Boolean) -> Unit,
    sizeDp: Int = 48,
    tag: String? = null
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val latestOnDown by rememberUpdatedState(onDownChange)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 700f),
        label = "float_trigger_scale"
    )

    DisposableEffect(Unit) {
        onDispose {
            if (isPressed) {
                isPressed = false
                latestOnDown(false)
            }
        }
    }

    val baseModifier = Modifier
        .size(sizeDp.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(CircleShape)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                isPressed = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                latestOnDown(true)
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
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

    Surface(
        shape = CircleShape,
        color = if (isPressed) accent.copy(alpha = 0.45f) else Color(0xFF101B2E).copy(alpha = 0.78f),
        border = BorderStroke(if (isPressed) 2.dp else 1.2.dp, if (isPressed) accent else accent.copy(alpha = 0.65f)),
        modifier = if (tag != null) baseModifier.testTag(tag) else baseModifier
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = if (sizeDp >= 64) 12.5.sp else 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isPressed) Color.White else accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RacingPedalsCluster(
    onGasChange: (Boolean) -> Unit,
    onBrakeChange: (Boolean) -> Unit,
    onNitroChange: (Boolean) -> Unit,
    onHandbrakeChange: (Boolean) -> Unit,
    onActionA: (Boolean) -> Unit,
    onActionB: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.35f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TactileGameTrigger(
                label = "FREN",
                subLabel = "Geri ↓",
                accent = GameAccentCoral,
                onDownChange = onBrakeChange,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            TactileGameTrigger(
                label = "GAZ",
                subLabel = "İleri ↑",
                accent = GameAccentEmerald,
                onDownChange = onGasChange,
                modifier = Modifier.weight(1.3f).fillMaxHeight()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.85f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TactileGameTrigger(
                label = "NİTRO",
                subLabel = "Shift",
                accent = AmberGold,
                onDownChange = onNitroChange,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            TactileGameTrigger(
                label = "EL FRENİ",
                subLabel = "Space",
                accent = NeonCyan,
                onDownChange = onHandbrakeChange,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.75f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TactileGameTrigger(
                label = "Z",
                subLabel = "Aksiyon 1",
                accent = GameAccentViolet,
                onDownChange = onActionA,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            TactileGameTrigger(
                label = "X",
                subLabel = "Aksiyon 2",
                accent = Color(0xFF60A5FA),
                onDownChange = onActionB,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun TactileGameTrigger(
    label: String,
    subLabel: String,
    accent: Color,
    onDownChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }
    val latestOnDown by rememberUpdatedState(onDownChange)
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 700f),
        label = "trigger_scale"
    )

    DisposableEffect(Unit) {
        onDispose {
            if (isPressed) {
                isPressed = false
                latestOnDown(false)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isPressed) accent.copy(alpha = 0.34f) else Color(0xFF131D30),
        border = BorderStroke(
            width = if (isPressed) 1.8.dp else 1.dp,
            color = if (isPressed) accent else accent.copy(alpha = 0.45f)
        ),
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
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
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isPressed) Color.White else accent,
                maxLines = 1
            )
            Text(
                text = subLabel,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF94A3B8),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LiveHoldKeyPill(
    label: String,
    onDownChange: (Boolean) -> Unit
) {
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
        shape = RoundedCornerShape(10.dp),
        color = if (isPressed) NeonCyan.copy(alpha = 0.25f) else Color(0xFF141E32),
        border = BorderStroke(1.dp, if (isPressed) NeonCyan else Color(0xFF283754)),
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPressed) NeonCyan else Color(0xFFE2E8F0)
            )
        }
    }
}

@Composable
private fun GameChipPill(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) activeColor.copy(alpha = 0.2f) else Color(0xFF141E32),
        border = BorderStroke(1.dp, if (isActive) activeColor else Color(0xFF283754)),
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isActive) activeColor else Color(0xFFCBD5E1),
                maxLines = 1
            )
        }
    }
}
