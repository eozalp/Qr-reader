package com.example.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.North
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.ui.theme.AmberGold
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

enum class KeyboardDeckPage {
    ALPHA,
    SYMBOLS
}

enum class ModifierLatchState {
    OFF,
    ONE_SHOT,
    LOCKED;

    fun isActive(): Boolean = this != OFF

    fun nextTap(): ModifierLatchState = when (this) {
        OFF -> ONE_SHOT
        ONE_SHOT -> LOCKED
        LOCKED -> OFF
    }

    fun consumeIfOneShot(): ModifierLatchState = if (this == ONE_SHOT) OFF else this
}

/**
 * Compact 1-line Native Typing (Gboard/Samsung/Voice Dictation) + 1-Tap Clipboard Beam bar.
 */
@Composable
fun QuickTextAndClipboardBar(
    textValue: String,
    onTextChanged: (String) -> Unit,
    onPasteClipboardToPc: () -> Unit,
    onSendText: (appendEnter: Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1-Tap Clipboard Beam
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF132238),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
            modifier = Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onPasteClipboardToPc)
                .testTag("remote_clipboard_beam_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = "Panoyu PC'ye Yaz",
                    tint = NeonCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Pano",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
            }
        }

        OutlinedTextField(
            value = textValue,
            onValueChange = onTextChanged,
            placeholder = {
                Text(
                    text = "Gboard / Sesle yaz & gönder...",
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = Color(0xFF28354E),
                focusedContainerColor = Color(0xFF0E1627),
                unfocusedContainerColor = Color(0xFF0E1627),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
        )

        FilledTonalIconButton(
            onClick = { onSendText(false) },
            enabled = textValue.isNotEmpty(),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color(0xFF17263D),
                contentColor = NeonCyan
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("remote_send_text_btn")
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "PC'ye Yaz", modifier = Modifier.size(18.dp))
        }

        FilledIconButton(
            onClick = { onSendText(true) },
            enabled = textValue.isNotEmpty(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color(0xFF0E7490),
                contentColor = Color.White
            ),
            modifier = Modifier
                .size(46.dp)
                .testTag("remote_send_text_enter_btn")
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "PC'ye Yaz ve Enter", modifier = Modifier.size(19.dp))
        }
    }
}

/**
 * Slim high-frequency PC shortcut ribbon (zero duplicate 123 button; includes 10-Col Wide vs 12-Col TR-Q toggle!).
 */
@Composable
fun PcShortcutsRibbon(
    showFunctionRow: Boolean,
    onToggleFunctionRow: () -> Unit,
    useWideTenColLayout: Boolean,
    onToggleWideLayout: () -> Unit,
    onSpecialKey: (Byte, Byte?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ShortcutPill(
            label = if (useWideTenColLayout) "Geniş 10" else "TR-Q 12",
            isActive = useWideTenColLayout,
            accent = AmberGold,
            onClick = onToggleWideLayout
        )
        ShortcutPill("F1–F12", isActive = showFunctionRow) { onToggleFunctionRow() }
        ShortcutPill("Esc") { onSpecialKey(BluetoothHidManager.KEY_ESCAPE, 0) }
        ShortcutPill("Kopyala") { onSpecialKey(BluetoothHidManager.KEY_C, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Yapıştır") { onSpecialKey(BluetoothHidManager.KEY_V, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Kes") { onSpecialKey(BluetoothHidManager.KEY_X, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Geri Al") { onSpecialKey(BluetoothHidManager.KEY_Z, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Tümü") { onSpecialKey(BluetoothHidManager.KEY_A, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Kaydet") { onSpecialKey(BluetoothHidManager.KEY_S, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Bul") { onSpecialKey(BluetoothHidManager.KEY_F, BluetoothHidManager.MODIFIER_LEFT_CTRL) }
        ShortcutPill("Alt+Tab") { onSpecialKey(BluetoothHidManager.KEY_TAB, BluetoothHidManager.MODIFIER_LEFT_ALT) }
        ShortcutPill("Win+D") { onSpecialKey(BluetoothHidManager.KEY_D, BluetoothHidManager.MODIFIER_LEFT_GUI) }
        ShortcutPill("Kilitle") { onSpecialKey(BluetoothHidManager.KEY_L, BluetoothHidManager.MODIFIER_LEFT_GUI) }
    }
}

@Composable
private fun ShortcutPill(
    label: String,
    isActive: Boolean = false,
    accent: Color = NeonCyan,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) accent.copy(alpha = 0.2f) else Color(0xFF131D30),
        border = BorderStroke(1.dp, if (isActive) accent.copy(alpha = 0.85f) else Color(0xFF283754)),
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isActive) accent else Color(0xFFE2E8F0)
            )
        }
    }
}

/**
 * Pro Accounting & Excel Numeric Keypad (123):
 * - No awkward URL text box! Includes an OLED Live Entry Display Strip + 1-tap Clipboard Beam + Excel Shortcuts
 * - Naturally proportioned tactile calculator rows anchored comfortably in the lower thumb zone.
 */
@Composable
fun StandaloneNumpadDeck(
    recentTape: String,
    onClearTape: () -> Unit,
    onPasteClipboardToPc: () -> Unit,
    onCharKey: (Char) -> Unit,
    onStringKeys: (String) -> Unit,
    onSpecialKey: (Byte, Byte?) -> Unit,
    modifier: Modifier = Modifier
) {
    val gap = 6.dp
    val digitFontSize = 23f
    val opFontSize = 19f
    val keyRowHeight = 56.dp

    Column(
        modifier = modifier.testTag("remote_numpad_deck"),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        // OLED Live Number Entry Display Strip (replaces the misplaced URL input box!)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0C1424),
            border = BorderStroke(1.dp, AmberGold.copy(alpha = 0.38f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF152338),
                    border = BorderStroke(0.9.dp, NeonCyan.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPasteClipboardToPc)
                        .testTag("remote_clipboard_beam_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Panoyu PC'ye Yaz", tint = NeonCyan, modifier = Modifier.size(15.dp))
                        Text("Pano", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonCyan)
                    }
                }

                Text(
                    text = recentTape.takeLast(16).ifEmpty { "0" },
                    fontSize = 22.sp,
                    fontFamily = AppMonoFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (recentTape.isEmpty()) Color(0xFF475569) else Color(0xFFF8FAFC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (recentTape.isNotEmpty()) {
                    Text(
                        text = "Temizle",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AmberGold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClearTape)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Push keypad naturally toward the lower thumb zone on tall phones!
        Spacer(modifier = Modifier.weight(1f))

        // Excel & Form Quick Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            KeyCapButton(
                label = "Num",
                bgColor = Color(0xFF142238),
                fgColor = AmberGold,
                fontSizeSp = 11.5f,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_NUM_LOCK, 0) },
                modifier = Modifier.weight(0.95f).fillMaxHeight().testTag("numpad_numlock_btn")
            )
            RepeatableKeyCapButton(
                label = "⇤",
                bgColor = Color(0xFF131C2D),
                fgColor = Color(0xFFE2E8F0),
                fontSizeSp = 15f,
                onTrigger = { onSpecialKey(BluetoothHidManager.KEY_TAB, BluetoothHidManager.MODIFIER_SHIFT) },
                modifier = Modifier.weight(0.85f).fillMaxHeight()
            )
            RepeatableKeyCapButton(
                label = "⇥ Tab",
                bgColor = Color(0xFF131C2D),
                fgColor = NeonCyan,
                fontSizeSp = 12f,
                onTrigger = { onSpecialKey(BluetoothHidManager.KEY_TAB, 0) },
                modifier = Modifier.weight(1.25f).fillMaxHeight()
            )
            KeyCapButton(
                label = "=",
                bgColor = Color(0xFF162136),
                fgColor = Color(0xFFE2E8F0),
                fontSizeSp = 16f,
                onClick = { onCharKey('=') },
                modifier = Modifier.weight(0.85f).fillMaxHeight()
            )
            KeyCapButton(
                label = "(",
                bgColor = Color(0xFF162136),
                fgColor = Color(0xFFE2E8F0),
                fontSizeSp = 15f,
                onClick = { onCharKey('(') },
                modifier = Modifier.weight(0.75f).fillMaxHeight()
            )
            KeyCapButton(
                label = ")",
                bgColor = Color(0xFF162136),
                fgColor = Color(0xFFE2E8F0),
                fontSizeSp = 15f,
                onClick = { onCharKey(')') },
                modifier = Modifier.weight(0.75f).fillMaxHeight()
            )
            KeyCapButton(
                label = "Esc",
                bgColor = Color(0xFF231726),
                fgColor = Color(0xFFFCA5A5),
                fontSizeSp = 11.5f,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_ESCAPE, 0) },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
        }

        // Row 1: [7] [8] [9] [÷] [⌫]
        Row(
            modifier = Modifier.fillMaxWidth().height(keyRowHeight),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            NumpadDigitKey("7", digitFontSize, { onCharKey('7') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_7"))
            NumpadDigitKey("8", digitFontSize, { onCharKey('8') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_8"))
            NumpadDigitKey("9", digitFontSize, { onCharKey('9') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_9"))
            KeyCapButton("÷", { onCharKey('/') }, Modifier.weight(0.9f).fillMaxHeight(), Color(0xFF16243A), NeonCyan, opFontSize)
            RepeatableIconKeyCapButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Sil",
                bgColor = Color(0xFF28192A),
                fgColor = Color(0xFFFCA5A5),
                onTrigger = { onSpecialKey(BluetoothHidManager.KEY_BACKSPACE, 0) },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag("numpad_backspace_btn")
            )
        }

        // Row 2: [4] [5] [6] [×] [Del]
        Row(
            modifier = Modifier.fillMaxWidth().height(keyRowHeight),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            NumpadDigitKey("4", digitFontSize, { onCharKey('4') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_4"))
            NumpadDigitKey("5", digitFontSize, { onCharKey('5') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_5"))
            NumpadDigitKey("6", digitFontSize, { onCharKey('6') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_6"))
            KeyCapButton("×", { onCharKey('*') }, Modifier.weight(0.9f).fillMaxHeight(), Color(0xFF16243A), NeonCyan, opFontSize)
            RepeatableKeyCapButton(
                label = "Del",
                bgColor = Color(0xFF1D1E30),
                fgColor = Color(0xFFF87171),
                fontSizeSp = 13f,
                onTrigger = { onSpecialKey(BluetoothHidManager.KEY_DELETE, 0) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Row 3: [1] [2] [3] [−] [+]
        Row(
            modifier = Modifier.fillMaxWidth().height(keyRowHeight),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            NumpadDigitKey("1", digitFontSize, { onCharKey('1') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_1"))
            NumpadDigitKey("2", digitFontSize, { onCharKey('2') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_2"))
            NumpadDigitKey("3", digitFontSize, { onCharKey('3') }, Modifier.weight(1f).fillMaxHeight().testTag("numpad_key_3"))
            KeyCapButton("−", { onCharKey('-') }, Modifier.weight(0.9f).fillMaxHeight(), Color(0xFF16243A), NeonCyan, opFontSize)
            KeyCapButton("+", { onCharKey('+') }, Modifier.weight(1f).fillMaxHeight(), Color(0xFF16243A), NeonCyan, opFontSize)
        }

        // Row 4: [0] [00] [,] [.] [Enter]
        Row(
            modifier = Modifier.fillMaxWidth().height(keyRowHeight),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            NumpadDigitKey("0", digitFontSize, { onCharKey('0') }, Modifier.weight(1.15f).fillMaxHeight().testTag("numpad_key_0"))
            NumpadDigitKey("00", digitFontSize - 2f, { onStringKeys("00") }, Modifier.weight(0.95f).fillMaxHeight().testTag("numpad_key_00"))
            KeyCapButton(",", { onCharKey(',') }, Modifier.weight(0.85f).fillMaxHeight(), Color(0xFF1B2740), Color.White, opFontSize + 2f)
            KeyCapButton(".", { onCharKey('.') }, Modifier.weight(0.85f).fillMaxHeight(), Color(0xFF1B2740), Color.White, opFontSize + 2f)
            EnterActionKeyCap(
                onClick = { onSpecialKey(BluetoothHidManager.KEY_ENTER, 0) },
                modifier = Modifier.weight(1.1f).fillMaxHeight().testTag("numpad_enter_btn")
            )
        }

        // Bottom Arrow & Undo Row
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            RepeatableKeyCapButton("←", { onSpecialKey(BluetoothHidManager.KEY_LEFT, 0) }, Modifier.weight(1f).fillMaxHeight(), Color(0xFF162235), Color.White)
            RepeatableKeyCapButton("↑", { onSpecialKey(BluetoothHidManager.KEY_UP, 0) }, Modifier.weight(1f).fillMaxHeight(), Color(0xFF162235), Color.White)
            RepeatableKeyCapButton("↓", { onSpecialKey(BluetoothHidManager.KEY_DOWN, 0) }, Modifier.weight(1f).fillMaxHeight(), Color(0xFF162235), Color.White)
            RepeatableKeyCapButton("→", { onSpecialKey(BluetoothHidManager.KEY_RIGHT, 0) }, Modifier.weight(1f).fillMaxHeight(), Color(0xFF162235), Color.White)
            KeyCapButton(
                label = "Ctrl+Z",
                bgColor = Color(0xFF141E32),
                fgColor = AmberGold,
                fontSizeSp = 11.5f,
                onClick = { onSpecialKey(BluetoothHidManager.KEY_Z, BluetoothHidManager.MODIFIER_LEFT_CTRL) },
                modifier = Modifier.weight(1.25f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun NumpadDigitKey(
    label: String,
    fontSizeSp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 750f),
        label = "numpad_key_scale"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isPressed) NeonCyan.copy(alpha = 0.25f) else Color(0xFF1A263D),
        border = BorderStroke(
            width = 1.dp,
            color = if (isPressed) NeonCyan else Color(0xFF2D3F5F)
        ),
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = fontSizeSp.sp,
                fontFamily = AppMonoFontFamily,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFF8FAFC),
                maxLines = 1
            )
        }
    }
}

/**
 * Ergonomic Natural-Height PC Keyboard Deck (NO stretched skyscraper keys!):
 * - Anchored cleanly at natural ~44dp key heights so thumbs comfortably reach every row!
 * - Supports 10-Column Wide Layout (20% wider keys, long-press g→ğ, u→ü, s→ş, o→ö, c→ç!) OR classic 12-Column TR-Q.
 * - Zero duplicate 123 keys, crisp vector Shift/Backspace/Enter caps, and scrubbable Spacebar cursor!
 */
@Composable
fun FullPcKeyboardDeck(
    activePage: KeyboardDeckPage,
    useWideTenColLayout: Boolean,
    isShiftOnce: Boolean,
    isCapsLock: Boolean,
    ctrlState: ModifierLatchState,
    altState: ModifierLatchState,
    winState: ModifierLatchState,
    showFunctionRow: Boolean,
    onSelectPage: (KeyboardDeckPage) -> Unit,
    onTapShift: () -> Unit,
    onTapCtrl: () -> Unit,
    onTapAlt: () -> Unit,
    onTapWin: () -> Unit,
    onCharKey: (Char) -> Unit,
    onSpecialKey: (Byte, Byte?) -> Unit,
    modifier: Modifier = Modifier
) {
    val trLocale = remember { Locale.forLanguageTag("tr-TR") }
    val isUpper = isShiftOnce || isCapsLock
    val isSymbolsPage = activePage == KeyboardDeckPage.SYMBOLS
    val keyRowH: Dp = 44.dp
    val modRowH: Dp = 38.dp
    val bottomRowH: Dp = 44.dp
    val rowGap: Dp = 5.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(rowGap)
    ) {
        // Optional F1..F12 + Nav Row
        AnimatedVisibility(visible = showFunctionRow) {
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
                            .height(32.dp)
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

        if (!isSymbolsPage) {
            if (useWideTenColLayout) {
                // Ergonomic 10-Column Wide Layout (20% wider keys; hold g→ğ, u→ü, s→ş, o→ö, c→ç!)
                val row1 = listOf(
                    'q' to null, 'w' to null, 'e' to null, 'r' to null, 't' to null,
                    'y' to null, 'u' to 'ü', 'ı' to null, 'o' to 'ö', 'p' to null
                )
                Row(
                    modifier = Modifier.fillMaxWidth().height(keyRowH),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    row1.forEach { (ch, altCh) ->
                        WideLetterKeyCap(
                            primary = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString(),
                            altHint = altCh?.let { if (isUpper) it.toString().uppercase(trLocale) else it.toString() },
                            onTap = { onCharKey(ch) },
                            onLongPressAlt = altCh?.let { { onCharKey(it) } },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }

                val row2 = listOf(
                    'a' to null, 's' to 'ş', 'd' to null, 'f' to null, 'g' to 'ğ',
                    'h' to null, 'j' to null, 'k' to null, 'l' to null, 'i' to null
                )
                Row(
                    modifier = Modifier.fillMaxWidth().height(keyRowH),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    row2.forEach { (ch, altCh) ->
                        WideLetterKeyCap(
                            primary = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString(),
                            altHint = altCh?.let { if (isUpper) it.toString().uppercase(trLocale) else it.toString() },
                            onTap = { onCharKey(ch) },
                            onLongPressAlt = altCh?.let { { onCharKey(it) } },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }

                // Row 3: [Shift] z x c(ç) v b n m [Backspace]
                Row(
                    modifier = Modifier.fillMaxWidth().height(keyRowH),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ShiftIconKeyCap(
                        isShiftOnce = isShiftOnce,
                        isCapsLock = isCapsLock,
                        onClick = onTapShift,
                        modifier = Modifier.weight(1.45f).fillMaxHeight()
                    )
                    listOf('z' to null, 'x' to null, 'c' to 'ç', 'v' to null, 'b' to null, 'n' to null, 'm' to null).forEach { (ch, altCh) ->
                        WideLetterKeyCap(
                            primary = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString(),
                            altHint = altCh?.let { if (isUpper) it.toString().uppercase(trLocale) else it.toString() },
                            onTap = { onCharKey(ch) },
                            onLongPressAlt = altCh?.let { { onCharKey(it) } },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                    RepeatableIconKeyCapButton(
                        icon = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Sil",
                        bgColor = Color(0xFF25192A),
                        fgColor = Color(0xFFFCA5A5),
                        onTrigger = { onSpecialKey(BluetoothHidManager.KEY_BACKSPACE, 0) },
                        modifier = Modifier.weight(1.45f).fillMaxHeight().testTag("kbd_backspace_btn")
                    )
                }
            } else {
                // Classic Full 12-Column TR-Q Layout
                Row(
                    modifier = Modifier.fillMaxWidth().height(keyRowH),
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                ) {
                    listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'ı', 'o', 'p', 'ğ', 'ü').forEach { ch ->
                        val label = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString()
                        KeyCapButton(label, { onCharKey(ch) }, Modifier.weight(1f).fillMaxHeight(), fontSizeSp = 14.5f)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(keyRowH).padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                ) {
                    listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'ş', 'i').forEach { ch ->
                        val label = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString()
                        KeyCapButton(label, { onCharKey(ch) }, Modifier.weight(1f).fillMaxHeight(), fontSizeSp = 14.5f)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(keyRowH),
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                ) {
                    ShiftIconKeyCap(
                        isShiftOnce = isShiftOnce,
                        isCapsLock = isCapsLock,
                        onClick = onTapShift,
                        modifier = Modifier.weight(1.35f).fillMaxHeight()
                    )
                    listOf('z', 'x', 'c', 'v', 'b', 'n', 'm', 'ö', 'ç').forEach { ch ->
                        val label = if (isUpper) ch.toString().uppercase(trLocale) else ch.toString()
                        KeyCapButton(label, { onCharKey(ch) }, Modifier.weight(1f).fillMaxHeight(), fontSizeSp = 14.5f)
                    }
                    RepeatableIconKeyCapButton(
                        icon = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Sil",
                        bgColor = Color(0xFF25192A),
                        fgColor = Color(0xFFFCA5A5),
                        onTrigger = { onSpecialKey(BluetoothHidManager.KEY_BACKSPACE, 0) },
                        modifier = Modifier.weight(1.35f).fillMaxHeight().testTag("kbd_backspace_btn")
                    )
                }
            }
        } else {
            // Symbols & Developer Characters
            Row(modifier = Modifier.fillMaxWidth().height(keyRowH), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')').forEach { sym ->
                    KeyCapButton(sym.toString(), { onCharKey(sym) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(keyRowH), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf('-', '_', '=', '+', '[', ']', '{', '}', ':', ';').forEach { sym ->
                    KeyCapButton(sym.toString(), { onCharKey(sym) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Row(modifier = Modifier.fillMaxWidth().height(keyRowH), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf('/', '\\', '|', '<', '>', '?', '"', '\'', '~').forEach { sym ->
                    KeyCapButton(sym.toString(), { onCharKey(sym) }, Modifier.weight(1f).fillMaxHeight())
                }
                RepeatableIconKeyCapButton(
                    icon = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Sil",
                    bgColor = Color(0xFF25192A),
                    fgColor = Color(0xFFFCA5A5),
                    onTrigger = { onSpecialKey(BluetoothHidManager.KEY_BACKSPACE, 0) },
                    modifier = Modifier.weight(1.5f).fillMaxHeight()
                )
            }
        }

        // Modifier + Repeatable Arrows Row
        Row(
            modifier = Modifier.fillMaxWidth().height(modRowH),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            ModifierKeyCap("Ctrl", ctrlState, onTapCtrl, Modifier.weight(1.1f).fillMaxHeight())
            ModifierKeyCap("Alt", altState, onTapAlt, Modifier.weight(0.95f).fillMaxHeight())
            ModifierKeyCap("⊞", winState, onTapWin, Modifier.weight(0.9f).fillMaxHeight(), fontSizeSp = 13f)
            RepeatableKeyCapButton("←", { onSpecialKey(BluetoothHidManager.KEY_LEFT, null) }, Modifier.weight(0.95f).fillMaxHeight(), Color(0xFF172337), Color.White)
            RepeatableKeyCapButton("↑", { onSpecialKey(BluetoothHidManager.KEY_UP, null) }, Modifier.weight(0.95f).fillMaxHeight(), Color(0xFF172337), Color.White)
            RepeatableKeyCapButton("↓", { onSpecialKey(BluetoothHidManager.KEY_DOWN, null) }, Modifier.weight(0.95f).fillMaxHeight(), Color(0xFF172337), Color.White)
            RepeatableKeyCapButton("→", { onSpecialKey(BluetoothHidManager.KEY_RIGHT, null) }, Modifier.weight(0.95f).fillMaxHeight(), Color(0xFF172337), Color.White)
            RepeatableKeyCapButton("Del", { onSpecialKey(BluetoothHidManager.KEY_DELETE, null) }, Modifier.weight(1.0f).fillMaxHeight(), Color(0xFF161D2F), Color(0xFFF87171), 11f)
        }

        // Bottom Row (Zero duplicate 123 button!): [#+=] [⇥] [◀ Boşluk ▶] [,] [.] [Enter]
        Row(
            modifier = Modifier.fillMaxWidth().height(bottomRowH),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyCapButton(
                label = if (isSymbolsPage) "ABC" else "#+=",
                bgColor = Color(0xFF152035),
                fgColor = AmberGold,
                fontSizeSp = 12f,
                onClick = {
                    onSelectPage(if (isSymbolsPage) KeyboardDeckPage.ALPHA else KeyboardDeckPage.SYMBOLS)
                },
                modifier = Modifier.weight(1.25f).fillMaxHeight()
            )
            RepeatableKeyCapButton(
                label = "⇥",
                bgColor = Color(0xFF152035),
                fgColor = Color(0xFFCBD5E1),
                onTrigger = { onSpecialKey(BluetoothHidManager.KEY_TAB, null) },
                modifier = Modifier.weight(0.9f).fillMaxHeight()
            )
            ScrubbableSpacebarKey(
                onSpaceTap = { onCharKey(' ') },
                onCursorStep = { dir ->
                    val key = if (dir < 0) BluetoothHidManager.KEY_LEFT else BluetoothHidManager.KEY_RIGHT
                    onSpecialKey(key, null)
                },
                modifier = Modifier
                    .weight(3.3f)
                    .fillMaxHeight()
                    .testTag("kbd_spacebar_scrub")
            )
            KeyCapButton(",", { onCharKey(',') }, Modifier.weight(0.75f).fillMaxHeight())
            KeyCapButton(".", { onCharKey('.') }, Modifier.weight(0.75f).fillMaxHeight())
            EnterActionKeyCap(
                onClick = { onSpecialKey(BluetoothHidManager.KEY_ENTER, null) },
                modifier = Modifier.weight(1.5f).fillMaxHeight()
            )
        }
    }
}

/**
 * Wide tactile letter key supporting optional long-press Turkish character popup (e.g., g→ğ, u→ü, s→ş, o→ö, c→ç).
 */
@Composable
private fun WideLetterKeyCap(
    primary: String,
    altHint: String?,
    onTap: () -> Unit,
    onLongPressAlt: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnAlt by rememberUpdatedState(onLongPressAlt)

    var isPressed by remember { mutableStateOf(false) }
    var altTriggered by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            altTriggered -> AmberGold.copy(alpha = 0.28f)
            isPressed -> NeonCyan.copy(alpha = 0.24f)
            else -> Color(0xFF1A253A)
        },
        border = BorderStroke(
            width = 0.9.dp,
            color = when {
                altTriggered -> AmberGold
                isPressed -> NeonCyan
                else -> Color(0xFF283855)
            }
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(altHint) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    altTriggered = false
                    val longPressJob = if (latestOnAlt != null) {
                        coroutineScope.launch {
                            delay(250L)
                            altTriggered = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            latestOnAlt?.invoke()
                        }
                    } else null

                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) {
                                if (!altTriggered) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestOnTap()
                                }
                                break
                            }
                        }
                    } finally {
                        longPressJob?.cancel()
                        isPressed = false
                        altTriggered = false
                    }
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = if (altTriggered && altHint != null) altHint else primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (altTriggered) AmberGold else Color(0xFFF8FAFC),
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center)
            )
            if (altHint != null) {
                Text(
                    text = altHint,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmberGold.copy(alpha = 0.72f),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 3.5.dp)
                )
            }
        }
    }
}

@Composable
private fun ShiftIconKeyCap(
    isShiftOnce: Boolean,
    isCapsLock: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            isCapsLock -> AmberGold
            isShiftOnce -> Color(0xFF153B45)
            else -> Color(0xFF141E32)
        },
        border = BorderStroke(
            1.dp,
            when {
                isCapsLock -> AmberGold
                isShiftOnce -> NeonCyan
                else -> Color(0xFF283855)
            }
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isCapsLock) Icons.Default.KeyboardCapslock else Icons.Default.North,
                contentDescription = "Shift / Büyük Harf",
                tint = when {
                    isCapsLock -> Color(0xFF042F2E)
                    isShiftOnce -> NeonCyan
                    else -> Color(0xFFE2E8F0)
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EnterActionKeyCap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(
                Brush.linearGradient(
                    colors = if (isPressed) listOf(NeonCyan, Color(0xFF0284C7))
                    else listOf(Color(0xFF0E7490), Color(0xFF0F4C75))
                )
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
            contentDescription = "Enter",
            tint = Color.White,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun ModifierKeyCap(
    label: String,
    state: ModifierLatchState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 11.5f
) {
    val bg = when (state) {
        ModifierLatchState.OFF -> Color(0xFF141E32)
        ModifierLatchState.ONE_SHOT -> NeonCyan
        ModifierLatchState.LOCKED -> AmberGold
    }
    val fg = if (state == ModifierLatchState.OFF) Color(0xFFCBD5E1) else Color(0xFF042F2E)
    val display = if (state == ModifierLatchState.LOCKED) "$label●" else label
    KeyCapButton(
        label = display,
        bgColor = bg,
        fgColor = fg,
        fontSizeSp = fontSizeSp,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun ScrubbableSpacebarKey(
    onSpaceTap: () -> Unit,
    onCursorStep: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scrubStepPx = with(density) { 18.dp.toPx() }
    val scrubSlopPx = with(density) { 14.dp.toPx() }

    val latestOnSpaceTap by rememberUpdatedState(onSpaceTap)
    val latestOnCursorStep by rememberUpdatedState(onCursorStep)

    var isPressed by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            isScrubbing -> NeonCyan.copy(alpha = 0.22f)
            isPressed -> Color(0xFF2C3E5E)
            else -> Color(0xFF1D2940)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isScrubbing) NeonCyan else Color(0xFF2B3B58)
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    isScrubbing = false
                    var lastX = down.position.x
                    val startX = down.position.x
                    var accumX = 0f

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.firstOrNull { it.pressed }
                            if (active == null) {
                                if (!isScrubbing) {
                                    latestOnSpaceTap()
                                }
                                break
                            }
                            if (event.type == PointerEventType.Move) {
                                val curX = active.position.x
                                if (!isScrubbing && abs(curX - startX) >= scrubSlopPx) {
                                    isScrubbing = true
                                    lastX = curX
                                    accumX = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                if (isScrubbing) {
                                    val dx = curX - lastX
                                    lastX = curX
                                    accumX += dx
                                    while (abs(accumX) >= scrubStepPx) {
                                        val dir = if (accumX > 0f) 1 else -1
                                        accumX -= dir * scrubStepPx
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        latestOnCursorStep(dir)
                                    }
                                    active.consume()
                                }
                            }
                        }
                    } finally {
                        isPressed = false
                        isScrubbing = false
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = if (isScrubbing) "◀ İmleç ▶" else "◀ Boşluk ▶",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isScrubbing) NeonCyan else Color(0xFF94A3B8),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RepeatableIconKeyCapButton(
    icon: ImageVector,
    contentDescription: String,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    bgColor: Color = Color(0xFF1B2436),
    fgColor: Color = Color(0xFFF8FAFC),
    initialRepeatDelayMs: Long = 350L,
    repeatIntervalMs: Long = 65L
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val latestOnTrigger by rememberUpdatedState(onTrigger)
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPressed) Color(0xFF3A253D) else bgColor,
        border = BorderStroke(0.9.dp, if (isPressed) fgColor else Color(0xFF34273E)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestOnTrigger()

                    val repeatJob = coroutineScope.launch {
                        delay(initialRepeatDelayMs)
                        while (true) {
                            latestOnTrigger()
                            delay(repeatIntervalMs)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        repeatJob.cancel()
                        isPressed = false
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = fgColor, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
fun RepeatableKeyCapButton(
    label: String,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    bgColor: Color = Color(0xFF1A253A),
    fgColor: Color = Color(0xFFF8FAFC),
    fontSizeSp: Float = 14.5f,
    initialRepeatDelayMs: Long = 350L,
    repeatIntervalMs: Long = 65L
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val latestOnTrigger by rememberUpdatedState(onTrigger)
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPressed) Color(0xFF334155) else bgColor,
        border = BorderStroke(0.9.dp, if (isPressed) NeonCyan else Color(0xFF283855)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latestOnTrigger()

                    val repeatJob = coroutineScope.launch {
                        delay(initialRepeatDelayMs)
                        while (true) {
                            latestOnTrigger()
                            delay(repeatIntervalMs)
                        }
                    }
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        repeatJob.cancel()
                        isPressed = false
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = label,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun KeyCapButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bgColor: Color = Color(0xFF1A253A),
    fgColor: Color = Color(0xFFF8FAFC),
    fontSizeSp: Float = 15f
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPressed) NeonCyan.copy(alpha = 0.24f) else bgColor,
        border = BorderStroke(0.9.dp, if (isPressed) NeonCyan else Color(0xFF283855)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                text = label,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = fgColor,
                maxLines = 1
            )
        }
    }
}
