package com.example.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.*
import com.example.ui.parseHexColor
import com.example.ui.theme.AppMonoFontFamily
import kotlin.math.roundToInt

/**
 * Curated 1-tap studio preset that styles colors, typography, shape, border, fill and depth together
 * while preserving the user's table items, callout geometry, and badges.
 */
data class StyleStudioPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val previewBg: Color,
    val previewAccent: Color,
    val previewText: Color,
    val fontFamily: OverlayFontFamily,
    val apply: (OverlayAppearance) -> OverlayAppearance
)

val STYLE_STUDIO_PRESETS: List<StyleStudioPreset> = listOf(
    StyleStudioPreset(
        id = "cyber_hud",
        name = "Cyber HUD",
        tagline = "Kesik köşe • Neon Cyan",
        previewBg = Color(0xFF061523),
        previewAccent = Color(0xFF00F5D4),
        previewText = Color(0xFFE2FBF8),
        fontFamily = OverlayFontFamily.GEOMETRIC,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.GEOMETRIC,
                fontWeightLevel = 700,
                isBold = true,
                isItalic = false,
                useMonoForValues = true,
                fillMode = OverlayFillMode.FILLED_GRADIENT,
                backgroundColorHex = "#061523",
                gradientColorEndHex = "#0D2B45",
                gradientAngleDeg = 135f,
                backgroundOpacity = 0.92f,
                textColorHex = "#E2FBF8",
                labelColorHex = "#67E8F9",
                accentColorHex = "#00F5D4",
                headerBgColorHex = "#0A2A3C",
                borderColorHex = "#00F5D4",
                useCustomTableColors = true,
                showBorder = true,
                borderStyle = BorderStyle.SOLID,
                borderWidthDp = 1.8f,
                cornerStyle = OverlayCornerStyle.CUT_CORNER,
                cornerRadiusDp = 12f,
                showGlow = true,
                showDropShadow = true,
                shadowElevationDp = 14f,
                showTextShadow = true,
                rowStripeOpacity = 0.10f
            )
        }
    ),
    StyleStudioPreset(
        id = "paper_daylight",
        name = "Beyaz Fiş",
        tagline = "Gündüz yüksek kontrast",
        previewBg = Color(0xFFF8FAFC),
        previewAccent = Color(0xFF0F172A),
        previewText = Color(0xFF0F172A),
        fontFamily = OverlayFontFamily.MODERN,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.MODERN,
                fontWeightLevel = 700,
                isBold = true,
                isItalic = false,
                useMonoForValues = true,
                fillMode = OverlayFillMode.FILLED_SOLID,
                backgroundColorHex = "#F8FAFC",
                gradientColorEndHex = "#E2E8F0",
                backgroundOpacity = 0.97f,
                textColorHex = "#0F172A",
                labelColorHex = "#475569",
                accentColorHex = "#0284C7",
                headerBgColorHex = "#E2E8F0",
                borderColorHex = "#0F172A",
                useCustomTableColors = true,
                showBorder = true,
                borderStyle = BorderStyle.SOLID,
                borderWidthDp = 1.5f,
                cornerStyle = OverlayCornerStyle.ROUNDED,
                cornerRadiusDp = 12f,
                showGlow = false,
                showDropShadow = true,
                shadowElevationDp = 10f,
                showTextShadow = false,
                rowStripeOpacity = 0.08f
            )
        }
    ),
    StyleStudioPreset(
        id = "industrial_warehouse",
        name = "Depo Sarısı",
        tagline = "Dar etiket • Yüksek görünürlük",
        previewBg = Color(0xFF18181B),
        previewAccent = Color(0xFFFACC15),
        previewText = Color(0xFFFEF9C3),
        fontFamily = OverlayFontFamily.CONDENSED,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.CONDENSED,
                fontWeightLevel = 700,
                isBold = true,
                isItalic = false,
                useMonoForValues = false,
                fillMode = OverlayFillMode.FILLED_SOLID,
                backgroundColorHex = "#18181B",
                gradientColorEndHex = "#27272A",
                backgroundOpacity = 0.95f,
                textColorHex = "#FEF9C3",
                labelColorHex = "#A1A1AA",
                accentColorHex = "#FACC15",
                headerBgColorHex = "#27272A",
                borderColorHex = "#FACC15",
                useCustomTableColors = true,
                showBorder = true,
                borderStyle = BorderStyle.DASHED,
                borderWidthDp = 2.2f,
                cornerStyle = OverlayCornerStyle.CUT_CORNER,
                cornerRadiusDp = 10f,
                showGlow = false,
                showDropShadow = true,
                shadowElevationDp = 12f,
                showTextShadow = false,
                rowStripeOpacity = 0.12f
            )
        }
    ),
    StyleStudioPreset(
        id = "luxury_boutique",
        name = "Lüks Butik",
        tagline = "Serif • Altın çift çerçeve",
        previewBg = Color(0xFF120F16),
        previewAccent = Color(0xFFD4AF37),
        previewText = Color(0xFFFAF5E8),
        fontFamily = OverlayFontFamily.SERIF,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.SERIF,
                fontWeightLevel = 600,
                isBold = true,
                isItalic = false,
                useMonoForValues = false,
                fillMode = OverlayFillMode.FILLED_GRADIENT,
                backgroundColorHex = "#120F16",
                gradientColorEndHex = "#241B14",
                gradientAngleDeg = 90f,
                backgroundOpacity = 0.95f,
                textColorHex = "#FAF5E8",
                labelColorHex = "#D6C5A3",
                accentColorHex = "#D4AF37",
                headerBgColorHex = "#1F1912",
                borderColorHex = "#D4AF37",
                useCustomTableColors = true,
                showBorder = true,
                borderStyle = BorderStyle.DOUBLE,
                borderWidthDp = 3f,
                cornerStyle = OverlayCornerStyle.ROUNDED,
                cornerRadiusDp = 14f,
                showGlow = false,
                showDropShadow = true,
                shadowElevationDp = 16f,
                showTextShadow = true,
                rowStripeOpacity = 0.08f
            )
        }
    ),
    StyleStudioPreset(
        id = "excel_emerald",
        name = "Klasik Excel",
        tagline = "Zümrüt yeşil • Monospace",
        previewBg = Color(0xFF071A11),
        previewAccent = Color(0xFF22C55E),
        previewText = Color(0xFFF0FDF4),
        fontFamily = OverlayFontFamily.MONO,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.MONO,
                fontWeightLevel = 700,
                isBold = true,
                isItalic = false,
                useMonoForValues = true,
                fillMode = OverlayFillMode.FILLED_SOLID,
                backgroundColorHex = "#071A11",
                gradientColorEndHex = "#0F3B25",
                backgroundOpacity = 0.95f,
                textColorHex = "#F0FDF4",
                labelColorHex = "#86EFAC",
                accentColorHex = "#22C55E",
                headerBgColorHex = "#0F3B25",
                borderColorHex = "#22C55E",
                useCustomTableColors = false,
                tableConfig = base.tableConfig.copy(gridTheme = ExcelGridTheme.EXCEL_CLASSIC),
                showBorder = true,
                borderStyle = BorderStyle.SOLID,
                borderWidthDp = 1.5f,
                cornerStyle = OverlayCornerStyle.ROUNDED,
                cornerRadiusDp = 8f,
                showGlow = false,
                showDropShadow = true,
                shadowElevationDp = 10f,
                showTextShadow = false,
                rowStripeOpacity = 0.12f
            )
        }
    ),
    StyleStudioPreset(
        id = "frosted_glass",
        name = "Buzlu Cam",
        tagline = "Yarı saydam • Yumuşak köşe",
        previewBg = Color(0xFF1E293B),
        previewAccent = Color(0xFF38BDF8),
        previewText = Color(0xFFFFFFFF),
        fontFamily = OverlayFontFamily.ROUNDED,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.ROUNDED,
                fontWeightLevel = 600,
                isBold = true,
                isItalic = false,
                useMonoForValues = false,
                fillMode = OverlayFillMode.SEMI_TRANSPARENT,
                backgroundColorHex = "#0F172A",
                gradientColorEndHex = "#1E293B",
                backgroundOpacity = 0.72f,
                textColorHex = "#FFFFFF",
                labelColorHex = "#BAE6FD",
                accentColorHex = "#38BDF8",
                headerBgColorHex = "#1E293B",
                borderColorHex = "#7DD3FC",
                useCustomTableColors = true,
                showBorder = true,
                borderStyle = BorderStyle.SOLID,
                borderWidthDp = 1.2f,
                cornerStyle = OverlayCornerStyle.ROUNDED,
                cornerRadiusDp = 22f,
                showGlow = true,
                showDropShadow = true,
                shadowElevationDp = 14f,
                showTextShadow = true,
                rowStripeOpacity = 0.08f
            )
        }
    ),
    StyleStudioPreset(
        id = "amber_pump",
        name = "Amber Enerji",
        tagline = "Sıcak gradyan • Güçlü rakam",
        previewBg = Color(0xFF2B1204),
        previewAccent = Color(0xFFF59E0B),
        previewText = Color(0xFFFEF3C7),
        fontFamily = OverlayFontFamily.GEOMETRIC,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.GEOMETRIC,
                fontWeightLevel = 800,
                isBold = true,
                isItalic = false,
                useMonoForValues = true,
                fillMode = OverlayFillMode.FILLED_GRADIENT,
                backgroundColorHex = "#2B1204",
                gradientColorEndHex = "#1E1B4B",
                gradientAngleDeg = 45f,
                backgroundOpacity = 0.94f,
                textColorHex = "#FEF3C7",
                labelColorHex = "#FCD34D",
                accentColorHex = "#F59E0B",
                headerBgColorHex = "#451A03",
                borderColorHex = "#F59E0B",
                useCustomTableColors = true,
                showBorder = true,
                borderStyle = BorderStyle.SOLID,
                borderWidthDp = 2f,
                cornerStyle = OverlayCornerStyle.ROUNDED,
                cornerRadiusDp = 16f,
                showGlow = true,
                showDropShadow = true,
                shadowElevationDp = 16f,
                showTextShadow = true,
                rowStripeOpacity = 0.14f
            )
        }
    ),
    StyleStudioPreset(
        id = "pure_minimal",
        name = "Saf Minimal",
        tagline = "Çerçevesiz • Sade odak",
        previewBg = Color(0xFF111827),
        previewAccent = Color(0xFFF43F5E),
        previewText = Color(0xFFF9FAFB),
        fontFamily = OverlayFontFamily.ROUNDED,
        apply = { base ->
            base.copy(
                fontFamily = OverlayFontFamily.ROUNDED,
                fontWeightLevel = 600,
                isBold = false,
                isItalic = false,
                useMonoForValues = false,
                fillMode = OverlayFillMode.FILLED_SOLID,
                backgroundColorHex = "#111827",
                gradientColorEndHex = "#111827",
                backgroundOpacity = 0.88f,
                textColorHex = "#F9FAFB",
                labelColorHex = "#9CA3AF",
                accentColorHex = "#F43F5E",
                headerBgColorHex = "#1F2937",
                borderColorHex = "#374151",
                useCustomTableColors = true,
                showBorder = false,
                borderStyle = BorderStyle.SOLID,
                borderWidthDp = 1f,
                cornerStyle = OverlayCornerStyle.ROUNDED,
                cornerRadiusDp = 18f,
                showGlow = false,
                showDropShadow = true,
                shadowElevationDp = 18f,
                showTextShadow = false,
                rowStripeOpacity = 0.05f
            )
        }
    )
)

/**
 * Generates a realistic live preview ParsedQrResult using the active profile's real variables.
 */
fun buildLivePreviewSampleResult(definedVariables: List<VariableDefinition>): ParsedQrResult {
    if (definedVariables.isEmpty()) {
        return ParsedQrResult(
            rawText = "KOLI01-A12-4",
            formatName = "QR_CODE",
            extractedVariables = mapOf("adet" to 4.0, "tutar" to 1450.0),
            extractedStrings = mapOf("sku" to "KOLI01", "raf" to "A12", "adet" to "4", "tutar" to "₺1.450,00"),
            status = CalculationStatus.Success(1450.0, "₺1.450,00"),
            formulaExpression = ""
        )
    }

    val numMap = mutableMapOf<String, Double>()
    val strMap = mutableMapOf<String, String>()
    var headlineText: String? = null
    var headlineNum = 0.0

    definedVariables.forEachIndexed { idx, v ->
        val sampleStr = when {
            v.defaultValue.isNotBlank() -> v.defaultValue
            v.isComputed && v.dataType == FieldDataType.STRING -> "ONAYLANDI"
            v.isComputed && v.dataType == FieldDataType.DATE -> "15.11.2026"
            v.isComputed -> "${(idx + 1) * 125}"
            v.dataType == FieldDataType.DATE -> "01.11.2026"
            v.dataType == FieldDataType.BOOLEAN -> "EVET"
            v.dataType == FieldDataType.STRING -> "KOD-${idx + 1}0${idx + 4}"
            v.dataType == FieldDataType.INTEGER -> "${(idx + 1) * 4}"
            else -> "${(idx + 1) * 24.5}"
        }

        val parsedNum = sampleStr.replace(",", ".").toDoubleOrNull()
        if (parsedNum != null && v.dataType != FieldDataType.STRING && v.dataType != FieldDataType.DATE) {
            numMap[v.name] = parsedNum
        }
        strMap[v.name] = sampleStr

        if (v.isPrimaryOutput) {
            headlineText = "${v.prefix}$sampleStr${v.postfix}"
            headlineNum = parsedNum ?: 0.0
        }
    }

    val fallbackVar = definedVariables.lastOrNull { it.isComputed && it.showPill }
        ?: definedVariables.firstOrNull { it.showPill }
        ?: definedVariables.first()
    val finalHeadline = headlineText ?: run {
        val raw = strMap[fallbackVar.name] ?: "1.450,00"
        "${fallbackVar.prefix}$raw${fallbackVar.postfix}"
    }

    return ParsedQrResult(
        rawText = definedVariables.filter { !it.isComputed && !it.isManual }
            .joinToString("-") { strMap[it.name] ?: "0" }
            .ifBlank { "ORNEK-100" },
        formatName = "QR_CODE",
        extractedVariables = numMap,
        extractedStrings = strMap,
        status = CalculationStatus.Success(headlineNum, finalHeadline),
        formulaExpression = ""
    )
}

/**
 * Interactive HSV Color Picker Dialog with 2D Saturation/Value pad, Hue rainbow slider,
 * designer quick swatches, and editable Hex input.
 */
@Composable
fun VisualColorPickerDialog(
    title: String,
    initialHex: String,
    allowAutoClear: Boolean = false,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    val startColor = remember(initialHex) {
        if (initialHex.isBlank()) Color(0xFF00F5D4) else parseHexColor(initialHex)
    }
    val initialHsv = remember(startColor) {
        val arr = FloatArray(3)
        AndroidColor.colorToHSV(startColor.toArgb(), arr)
        arr
    }

    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var hexInput by remember { mutableStateOf(colorToHexString(startColor)) }

    val currentColor = remember(hue, saturation, value) {
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
    }

    fun updateFromHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 360f)
        saturation = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        val c = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
        hexInput = colorToHexString(c)
    }

    fun updateFromColor(c: Color) {
        val arr = FloatArray(3)
        AndroidColor.colorToHSV(c.toArgb(), arr)
        hue = arr[0]
        saturation = arr[1]
        value = arr[2]
        hexInput = colorToHexString(c)
    }

    val designerSwatches = remember {
        listOf(
            "#00F5D4", "#22C55E", "#38BDF8", "#3B82F6", "#8B5CF6", "#EC4899",
            "#F43F5E", "#F59E0B", "#FACC15", "#D4AF37", "#FFFFFF", "#F8FAFC",
            "#94A3B8", "#334155", "#18181B", "#0F172A", "#071A11", "#000000"
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("visual_color_picker_dialog")
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Renk alanından sürükleyin veya paletten seçin",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Kapat")
                    }
                }

                // 2D Saturation / Value Pad
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(165.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val s = (offset.x / size.width).coerceIn(0f, 1f)
                                val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                                updateFromHsv(hue, s, v)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val s = (change.position.x / size.width).coerceIn(0f, 1f)
                                val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                updateFromHsv(hue, s, v)
                            }
                        }
                ) {
                    val pureHueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.horizontalGradient(listOf(Color.White, pureHueColor))
                        )
                        drawRect(
                            brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                        )
                        val thumbX = saturation * size.width
                        val thumbY = (1f - value) * size.height
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.55f),
                            radius = 11.dp.toPx(),
                            center = Offset(thumbX, thumbY)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 9.dp.toPx(),
                            center = Offset(thumbX, thumbY)
                        )
                        drawCircle(
                            color = currentColor,
                            radius = 6.5.dp.toPx(),
                            center = Offset(thumbX, thumbY)
                        )
                    }
                }

                // Rainbow Hue Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(15.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val h = ((offset.x / size.width).coerceIn(0f, 1f)) * 360f
                                updateFromHsv(h, saturation, value)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val h = ((change.position.x / size.width).coerceIn(0f, 1f)) * 360f
                                updateFromHsv(h, saturation, value)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val rainbow = listOf(
                            Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                            Color.Blue, Color.Magenta, Color.Red
                        )
                        drawRect(brush = Brush.horizontalGradient(rainbow))
                        val thumbX = (hue / 360f).coerceIn(0f, 1f) * size.width
                        drawCircle(
                            color = Color.White,
                            radius = 12.dp.toPx(),
                            center = Offset(thumbX, size.height / 2f)
                        )
                        drawCircle(
                            color = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))),
                            radius = 8.5.dp.toPx(),
                            center = Offset(thumbX, size.height / 2f)
                        )
                    }
                }

                // Quick Swatch Grid (3 rows of 6)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    designerSwatches.chunked(6).forEach { rowHexes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            rowHexes.forEach { swatchHex ->
                                val swatchColor = parseHexColor(swatchHex)
                                val isSel = hexInput.equals(swatchHex, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(
                                            width = if (isSel) 2.5.dp else 1.dp,
                                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            shape = CircleShape
                                        )
                                        .clickable { updateFromColor(swatchColor) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSel) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = if (swatchColor.luminance() > 0.55f) Color.Black else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Preview + Hex Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(currentColor)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    )
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { typed ->
                            val cleaned = typed.uppercase().take(9)
                            hexInput = cleaned
                            val candidate = if (cleaned.startsWith("#")) cleaned else "#$cleaned"
                            if (candidate.length == 7 || candidate.length == 9) {
                                runCatching { Color(AndroidColor.parseColor(candidate)) }
                                    .onSuccess { parsed ->
                                        val arr = FloatArray(3)
                                        AndroidColor.colorToHSV(parsed.toArgb(), arr)
                                        hue = arr[0]
                                        saturation = arr[1]
                                        value = arr[2]
                                    }
                            }
                        },
                        label = { Text("Hex Kodu") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = AppMonoFontFamily),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (allowAutoClear) {
                        TextButton(
                            onClick = {
                                onColorSelected("")
                                onDismiss()
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Otomatik")
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("İptal")
                    }
                    Button(
                        onClick = {
                            onColorSelected(colorToHexString(currentColor))
                            onDismiss()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("color_picker_apply_btn")
                    ) {
                        Text("Uygula", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

fun colorToHexString(color: Color): String {
    val rgb = color.toArgb() and 0x00FFFFFF
    return String.format("#%06X", rgb)
}

@Composable
fun ColorSlotRow(
    label: String,
    subtitle: String,
    hexValue: String,
    fallbackHex: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveHex = hexValue.ifBlank { fallbackHex }
    val previewColor = parseHexColor(effectiveHex)
    val isAuto = hexValue.isBlank()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(previewColor)
                    .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Colorize,
                    contentDescription = null,
                    tint = if (previewColor.luminance() > 0.55f) Color(0xFF0F172A) else Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Text(
                    text = if (isAuto) "OTOMATİK" else effectiveHex.uppercase(),
                    fontFamily = AppMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun StudioSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
            ) {
                Text(
                    text = valueText,
                    fontFamily = AppMonoFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}
