package com.example.ui.settings

import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.OverlayCardContent
import com.example.ui.parseHexColor
import com.example.ui.theme.resolveOverlayFontFamily
import kotlin.math.roundToInt

private enum class StyleBuilderTab(val label: String, val icon: ImageVector) {
    THEMES("Temalar", Icons.Default.AutoAwesome),
    COLORS("Renkler", Icons.Default.Palette),
    TYPOGRAPHY("Font", Icons.Default.TextFields),
    FRAME("Kenar & Boyut", Icons.Default.RoundedCorner),
    LAYOUT("Yerleşim", Icons.Default.Tune)
}

private enum class PreviewBackdrop(val label: String) {
    DARK_CAMERA("Koyu"),
    LIGHT_DAYLIGHT("Gündüz"),
    GRID("Izgara")
}

private data class ActiveColorPickerTarget(
    val title: String,
    val initialHex: String,
    val allowAutoClear: Boolean,
    val onApply: (String) -> Unit
)

@Composable
fun OverlayAppearanceSection(
    overlay: OverlayAppearance,
    definedVariables: List<VariableDefinition> = emptyList(),
    onOverlayChanged: (OverlayAppearance) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(StyleBuilderTab.THEMES) }
    var previewBackdrop by remember { mutableStateOf(PreviewBackdrop.DARK_CAMERA) }
    var colorPickerTarget by remember { mutableStateOf<ActiveColorPickerTarget?>(null) }

    val sampleResult = remember(definedVariables) {
        buildLivePreviewSampleResult(definedVariables)
    }

    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = modifier.fillMaxSize()) {
        // 1. PINNED LIVE INTERACTIVE AR STAGE (always visible while editing!)
        LiveStylePreviewStage(
            overlay = overlay,
            sampleResult = sampleResult,
            definedVariables = definedVariables,
            backdrop = previewBackdrop,
            onBackdropChange = { previewBackdrop = it },
            onDisplayModeChange = { mode -> onOverlayChanged(overlay.copy(displayMode = mode)) },
            onResetStyle = {
                onOverlayChanged(
                    OverlayAppearance(
                        displayMode = overlay.displayMode,
                        tableConfig = overlay.tableConfig,
                        calloutMode = overlay.calloutMode
                    )
                )
            }
        )

        // 2. STUDIO CATEGORY TABS (5 focused panels — zero scrolling fatigue)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StyleBuilderTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    ),
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { activeTab = tab }
                        .testTag("style_tab_${tab.name.lowercase(java.util.Locale.ROOT)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // 3. SCROLLABLE STUDIO INSPECTOR BODY
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 20.dp + navBottomPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (activeTab) {
                StyleBuilderTab.THEMES -> {
                    item {
                        ThemesInspectorPanel(
                            overlay = overlay,
                            onOverlayChanged = onOverlayChanged
                        )
                    }
                }

                StyleBuilderTab.COLORS -> {
                    item {
                        ColorsInspectorPanel(
                            overlay = overlay,
                            onOverlayChanged = onOverlayChanged,
                            onOpenColorPicker = { target -> colorPickerTarget = target }
                        )
                    }
                }

                StyleBuilderTab.TYPOGRAPHY -> {
                    item {
                        TypographyInspectorPanel(
                            overlay = overlay,
                            onOverlayChanged = onOverlayChanged,
                            onOpenShadowColorPicker = {
                                colorPickerTarget = ActiveColorPickerTarget(
                                    title = "Metin Gölge Rengi",
                                    initialHex = overlay.textShadowColorHex,
                                    allowAutoClear = false,
                                    onApply = { hex -> onOverlayChanged(overlay.copy(textShadowColorHex = hex)) }
                                )
                            }
                        )
                    }
                }

                StyleBuilderTab.FRAME -> {
                    item {
                        FrameAndSizeInspectorPanel(
                            overlay = overlay,
                            onOverlayChanged = onOverlayChanged
                        )
                    }
                }

                StyleBuilderTab.LAYOUT -> {
                    item {
                        LayoutAndExtrasInspectorPanel(
                            overlay = overlay,
                            definedVariables = definedVariables,
                            onOverlayChanged = onOverlayChanged
                        )
                    }
                }
            }
        }
    }

    // Visual HSV Color Picker Dialog
    colorPickerTarget?.let { target ->
        VisualColorPickerDialog(
            title = target.title,
            initialHex = target.initialHex,
            allowAutoClear = target.allowAutoClear,
            onDismiss = { colorPickerTarget = null },
            onColorSelected = { selectedHex -> target.onApply(selectedHex) }
        )
    }
}

@Composable
private fun LiveStylePreviewStage(
    overlay: OverlayAppearance,
    sampleResult: ParsedQrResult,
    definedVariables: List<VariableDefinition>,
    backdrop: PreviewBackdrop,
    onBackdropChange: (PreviewBackdrop) -> Unit,
    onDisplayModeChange: (OverlayDisplayMode) -> Unit,
    onResetStyle: () -> Unit
) {
    val stageBg = when (backdrop) {
        PreviewBackdrop.DARK_CAMERA -> Brush.verticalGradient(listOf(Color(0xFF070B14), Color(0xFF111827)))
        PreviewBackdrop.LIGHT_DAYLIGHT -> Brush.verticalGradient(listOf(Color(0xFFE2E8F0), Color(0xFFCBD5E1)))
        PreviewBackdrop.GRID -> Brush.verticalGradient(listOf(Color(0xFF0B1120), Color(0xFF0F172A)))
    }
    val chromeTextColor = if (backdrop == PreviewBackdrop.LIGHT_DAYLIGHT) Color(0xFF1E293B) else Color(0xFF94A3B8)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(stageBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Top mini toolbar: Display Mode (Tablo / Kart / Hibrit) + Backdrop + Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    OverlayDisplayMode.EXCEL_TABLE to "Tablo",
                    OverlayDisplayMode.COMPACT_CARD to "Kart",
                    OverlayDisplayMode.HYBRID to "Hibrit"
                ).forEach { (mode, label) ->
                    val active = overlay.displayMode == mode
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.28f),
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onDisplayModeChange(mode) }
                            .testTag("preview_mode_${mode.name.lowercase(java.util.Locale.ROOT)}")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White
                            )
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cycle backdrop button
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.25f),
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val entries = PreviewBackdrop.entries
                            onBackdropChange(entries[(entries.indexOf(backdrop) + 1) % entries.size])
                        }
                        .testTag("preview_backdrop_cycle_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = when (backdrop) {
                                PreviewBackdrop.DARK_CAMERA -> Icons.Default.DarkMode
                                PreviewBackdrop.LIGHT_DAYLIGHT -> Icons.Default.LightMode
                                PreviewBackdrop.GRID -> Icons.Default.GridOn
                            },
                            contentDescription = "Arka plan ortamı",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(backdrop.label, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Reset Style
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.25f),
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onResetStyle() }
                        .testTag("style_reset_btn")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Stili Sıfırla",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Live AR Stage Canvas + Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 148.dp, max = 215.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, chromeTextColor.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Optional technical grid + mini QR callout leader line
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (backdrop == PreviewBackdrop.GRID) {
                    val step = 20.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(Color(0x1838BDF8), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Color(0x1838BDF8), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += step
                    }
                }

                if (overlay.calloutMode != CalloutLayoutMode.DIRECT_FOLLOW) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    val lineHex = overlay.leaderLineColorHex.ifBlank { overlay.borderColorHex }
                    val strokeCol = parseHexColor(lineHex).copy(alpha = 0.85f)

                    val qrSize = 36.dp.toPx()
                    val qrLeft = 14.dp.toPx()
                    val qrTop = (size.height - qrSize) / 2f
                    val qrRect = RectF(qrLeft, qrTop, qrLeft + qrSize, qrTop + qrSize)

                    if (overlay.showBarcodeDashedOutline) {
                        drawRoundRect(
                            color = strokeCol,
                            topLeft = Offset(qrRect.left, qrRect.top),
                            size = Size(qrRect.width(), qrRect.height()),
                            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                            style = Stroke(width = 1.4.dp.toPx(), pathEffect = dash)
                        )
                    }

                    val barcAnchor = Offset(qrRect.right, qrRect.centerY())
                    val cardAnchor = Offset(size.width * 0.28f, size.height / 2f)

                    if (overlay.showAnchorDot) {
                        drawCircle(color = strokeCol.copy(alpha = 0.3f), radius = 4.5.dp.toPx(), center = barcAnchor)
                        drawCircle(color = strokeCol, radius = 2.2.dp.toPx(), center = barcAnchor)
                    }

                    val lineEffect = if (overlay.leaderLineStyle == LeaderLineStyle.DASHED) dash else null
                    drawLine(
                        color = strokeCol,
                        start = barcAnchor,
                        end = cardAnchor,
                        strokeWidth = overlay.leaderLineWidthDp.dp.toPx(),
                        pathEffect = lineEffect
                    )
                }
            }

            // Cap preview scale gently so giant 2.5x scale doesn't break the settings header
            val previewConfig = remember(overlay) {
                overlay.copy(scale = overlay.scale.coerceIn(0.6f, 1.25f))
            }

            Box(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                OverlayCardContent(
                    result = sampleResult,
                    config = previewConfig,
                    variableDefs = definedVariables
                )
            }
        }
    }
}

@Composable
private fun ThemesInspectorPanel(
    overlay: OverlayAppearance,
    onOverlayChanged: (OverlayAppearance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Hazır Stüdyo Temaları",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "Tek dokunuşla komple görünüm uygulayın, sonra diğer sekmelerden her detayı ince ayarlayın.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 2-column preset cards
        STYLE_STUDIO_PRESETS.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { preset ->
                    val font = resolveOverlayFontFamily(preset.fontFamily)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = preset.previewBg,
                        border = BorderStroke(1.5.dp, preset.previewAccent.copy(alpha = 0.75f)),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOverlayChanged(preset.apply(overlay)) }
                            .testTag("style_preset_${preset.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = preset.name,
                                    color = preset.previewText,
                                    fontFamily = font,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(preset.previewAccent)
                                )
                            }
                            Text(
                                text = preset.tagline,
                                color = preset.previewText.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = preset.previewAccent.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = "₺1.450,00",
                                    color = preset.previewAccent,
                                    fontFamily = font,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Classic Excel Themes when user wants built-in grid themes
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Klasik Tablo Izgara Paletleri",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExcelGridTheme.entries.forEach { gridTheme ->
                        val selected = !overlay.useCustomTableColors && overlay.tableConfig.gridTheme == gridTheme
                        val label = when (gridTheme) {
                            ExcelGridTheme.EXCEL_CLASSIC -> "Excel Yeşil"
                            ExcelGridTheme.NEON_CYAN -> "Neon Cyan"
                            ExcelGridTheme.MODERN_SLATE -> "Buz Mavisi"
                            ExcelGridTheme.AMBER_GOLD -> "Amber Altın"
                        }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                onOverlayChanged(
                                    overlay.copy(
                                        useCustomTableColors = false,
                                        tableConfig = overlay.tableConfig.copy(gridTheme = gridTheme)
                                    )
                                )
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorsInspectorPanel(
    overlay: OverlayAppearance,
    onOverlayChanged: (OverlayAppearance) -> Unit,
    onOpenColorPicker: (ActiveColorPickerTarget) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Custom Table Palette Master Switch
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (overlay.useCustomTableColors) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Özel Renk Paletini Tabloda Kullan",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Açıkken tablo da aşağıdaki tüm özel renk, gradyan ve başlık ayarlarını birebir uygular.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = overlay.useCustomTableColors,
                    onCheckedChange = { onOverlayChanged(overlay.copy(useCustomTableColors = it)) },
                    modifier = Modifier.testTag("use_custom_table_colors_switch")
                )
            }
        }

        // Fill Mode & Opacity & Gradient Angle
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dolgu & Arka Plan Tipi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OverlayFillMode.entries.forEach { mode ->
                        val label = when (mode) {
                            OverlayFillMode.FILLED_SOLID -> "Düz Dolgu"
                            OverlayFillMode.FILLED_GRADIENT -> "Gradyan"
                            OverlayFillMode.SEMI_TRANSPARENT -> "Yarı Saydam"
                            OverlayFillMode.OUTLINE_ONLY -> "Sadece Çerçeve"
                        }
                        FilterChip(
                            selected = overlay.fillMode == mode,
                            onClick = { onOverlayChanged(overlay.copy(fillMode = mode, useCustomTableColors = true)) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                if (overlay.fillMode != OverlayFillMode.OUTLINE_ONLY) {
                    StudioSliderRow(
                        label = "Arka Plan Opaklığı",
                        valueText = "%${(overlay.backgroundOpacity * 100).roundToInt()}",
                        value = overlay.backgroundOpacity,
                        valueRange = 0.10f..1.0f,
                        onValueChange = { onOverlayChanged(overlay.copy(backgroundOpacity = it)) }
                    )
                }

                if (overlay.fillMode == OverlayFillMode.FILLED_GRADIENT) {
                    Text("Gradyan Yönü", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0f to "Yatay →", 90f to "Dikey ↓", 135f to "Çapraz ↘", 45f to "Ters ↗").forEach { (deg, lbl) ->
                            FilterChip(
                                selected = overlay.gradientAngleDeg.roundToInt() == deg.roundToInt(),
                                onClick = { onOverlayChanged(overlay.copy(gradientAngleDeg = deg, useCustomTableColors = true)) },
                                label = { Text(lbl, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // All Editable Color Slots
        Text("Renk Katmanları", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

        ColorSlotRow(
            label = "Arka Plan Rengi",
            subtitle = "Kart ve tablo gövde rengi",
            hexValue = overlay.backgroundColorHex,
            fallbackHex = "#0F172A",
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Arka Plan Rengi",
                        initialHex = overlay.backgroundColorHex,
                        allowAutoClear = false,
                        onApply = { hex -> onOverlayChanged(overlay.copy(backgroundColorHex = hex, useCustomTableColors = true)) }
                    )
                )
            },
            modifier = Modifier.testTag("color_slot_background")
        )

        if (overlay.fillMode == OverlayFillMode.FILLED_GRADIENT) {
            ColorSlotRow(
                label = "Gradyan Bitiş Rengi",
                subtitle = "İkinci geçiş rengi",
                hexValue = overlay.gradientColorEndHex,
                fallbackHex = "#1E1B4B",
                onClick = {
                    onOpenColorPicker(
                        ActiveColorPickerTarget(
                            title = "Gradyan Bitiş Rengi",
                            initialHex = overlay.gradientColorEndHex,
                            allowAutoClear = false,
                            onApply = { hex -> onOverlayChanged(overlay.copy(gradientColorEndHex = hex, useCustomTableColors = true)) }
                        )
                    )
                }
            )
        }

        ColorSlotRow(
            label = "Ana Metin Rengi",
            subtitle = "Kart sonucu ve tablo satır metni",
            hexValue = overlay.textColorHex,
            fallbackHex = "#00F5D4",
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Ana Metin Rengi",
                        initialHex = overlay.textColorHex,
                        allowAutoClear = false,
                        onApply = { hex -> onOverlayChanged(overlay.copy(textColorHex = hex, useCustomTableColors = true)) }
                    )
                )
            },
            modifier = Modifier.testTag("color_slot_text")
        )

        ColorSlotRow(
            label = "Vurgu & Değer Rengi",
            subtitle = "Tablo değerleri, yıldızlı satır ve ikonlar",
            hexValue = overlay.accentColorHex,
            fallbackHex = overlay.borderColorHex,
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Vurgu & Değer Rengi",
                        initialHex = overlay.accentColorHex.ifBlank { overlay.borderColorHex },
                        allowAutoClear = true,
                        onApply = { hex -> onOverlayChanged(overlay.copy(accentColorHex = hex, useCustomTableColors = true)) }
                    )
                )
            },
            modifier = Modifier.testTag("color_slot_accent")
        )

        ColorSlotRow(
            label = "Çerçeve & Parıltı Rengi",
            subtitle = "Dış kenar çizgisi, neon glow ve barkod çerçevesi",
            hexValue = overlay.borderColorHex,
            fallbackHex = "#00F5D4",
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Çerçeve & Parıltı Rengi",
                        initialHex = overlay.borderColorHex,
                        allowAutoClear = false,
                        onApply = { hex -> onOverlayChanged(overlay.copy(borderColorHex = hex, useCustomTableColors = true)) }
                    )
                )
            },
            modifier = Modifier.testTag("color_slot_border")
        )

        ColorSlotRow(
            label = "Etiket / Alt Başlık Rengi",
            subtitle = "Sütun başlıkları ve ikincil açıklamalar",
            hexValue = overlay.labelColorHex,
            fallbackHex = "#94A3B8",
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Etiket / Alt Başlık Rengi",
                        initialHex = overlay.labelColorHex.ifBlank { "#94A3B8" },
                        allowAutoClear = true,
                        onApply = { hex -> onOverlayChanged(overlay.copy(labelColorHex = hex, useCustomTableColors = true)) }
                    )
                )
            }
        )

        ColorSlotRow(
            label = "Tablo Başlık Şeridi Rengi",
            subtitle = "Tablo üst başlık çubuğu arka planı",
            hexValue = overlay.headerBgColorHex,
            fallbackHex = overlay.gradientColorEndHex,
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Tablo Başlık Şeridi Rengi",
                        initialHex = overlay.headerBgColorHex.ifBlank { overlay.gradientColorEndHex },
                        allowAutoClear = true,
                        onApply = { hex -> onOverlayChanged(overlay.copy(headerBgColorHex = hex, useCustomTableColors = true)) }
                    )
                )
            }
        )

        ColorSlotRow(
            label = "Rehber Çizgi Rengi",
            subtitle = "Barkod ile kutuyu bağlayan çizgi",
            hexValue = overlay.leaderLineColorHex,
            fallbackHex = overlay.borderColorHex,
            onClick = {
                onOpenColorPicker(
                    ActiveColorPickerTarget(
                        title = "Rehber Çizgi Rengi",
                        initialHex = overlay.leaderLineColorHex.ifBlank { overlay.borderColorHex },
                        allowAutoClear = true,
                        onApply = { hex -> onOverlayChanged(overlay.copy(leaderLineColorHex = hex)) }
                    )
                )
            }
        )
    }
}

@Composable
private fun TypographyInspectorPanel(
    overlay: OverlayAppearance,
    onOverlayChanged: (OverlayAppearance) -> Unit,
    onOpenShadowColorPicker: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Yazı Tipi Ailesi",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )

        // Visual Font Gallery (each card renders live sample in its real bundled font!)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayFontFamily.entries.forEach { fontOption ->
                val selected = overlay.fontFamily == fontOption
                val font = resolveOverlayFontFamily(fontOption)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOverlayChanged(overlay.copy(fontFamily = fontOption)) }
                        .testTag("font_option_${fontOption.name.lowercase(java.util.Locale.ROOT)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fontOption.displayName,
                                fontFamily = font,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = fontOption.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "₺1.450,00",
                            fontFamily = font,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Size, Spacing, Weight, Style
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioSliderRow(
                    label = "Yazı Boyutu",
                    valueText = "${overlay.fontSizeSp.roundToInt()} sp",
                    value = overlay.fontSizeSp,
                    valueRange = 10f..36f,
                    onValueChange = { onOverlayChanged(overlay.copy(fontSizeSp = it)) }
                )

                StudioSliderRow(
                    label = "Harf Aralığı",
                    valueText = "${"%.1f".format(overlay.letterSpacingSp)} sp",
                    value = overlay.letterSpacingSp,
                    valueRange = -1.0f..4.0f,
                    onValueChange = { onOverlayChanged(overlay.copy(letterSpacingSp = it)) }
                )

                Text("Yazı Kalınlığı", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        400 to "Normal",
                        500 to "Orta",
                        600 to "Yarı Kalın",
                        700 to "Kalın",
                        800 to "Ekstra"
                    ).forEach { (weight, lbl) ->
                        FilterChip(
                            selected = overlay.fontWeightLevel == weight,
                            onClick = {
                                onOverlayChanged(
                                    overlay.copy(
                                        fontWeightLevel = weight,
                                        isBold = weight >= 600
                                    )
                                )
                            },
                            label = { Text(lbl, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = overlay.isItalic,
                        onClick = { onOverlayChanged(overlay.copy(isItalic = !overlay.isItalic)) },
                        label = { Text("İtalik", fontStyle = FontStyle.Italic) }
                    )
                    FilterChip(
                        selected = overlay.textAlignCenter,
                        onClick = { onOverlayChanged(overlay.copy(textAlignCenter = !overlay.textAlignCenter)) },
                        label = { Text(if (overlay.textAlignCenter) "Ortalanmış" else "Sola Hizalı") }
                    )
                    FilterChip(
                        selected = overlay.showTextShadow,
                        onClick = { onOverlayChanged(overlay.copy(showTextShadow = !overlay.showTextShadow)) },
                        label = { Text("Metin Gölgesi") }
                    )
                }

                if (overlay.showTextShadow) {
                    ColorSlotRow(
                        label = "Metin Gölge Rengi",
                        subtitle = "Okunabilirlik kontrast gölgesi",
                        hexValue = overlay.textShadowColorHex,
                        fallbackHex = "#000000",
                        onClick = onOpenShadowColorPicker
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Değerlerde Monospace Kullan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Tablo sağ sütunundaki rakamları hizalı sabit genişlikte yazar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = overlay.useMonoForValues,
                        onCheckedChange = { onOverlayChanged(overlay.copy(useMonoForValues = it)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FrameAndSizeInspectorPanel(
    overlay: OverlayAppearance,
    onOverlayChanged: (OverlayAppearance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Corner Shape & Border Style
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Köşe & Çerçeve", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        OverlayCornerStyle.ROUNDED to "Yuvarlak Köşe",
                        OverlayCornerStyle.CUT_CORNER to "Kesik HUD Köşe"
                    ).forEach { (cStyle, lbl) ->
                        val selected = overlay.cornerStyle == cStyle
                        Surface(
                            shape = if (cStyle == OverlayCornerStyle.CUT_CORNER) CutCornerShape(10.dp) else RoundedCornerShape(10.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable { onOverlayChanged(overlay.copy(cornerStyle = cStyle)) }
                                .testTag("corner_style_${cStyle.name.lowercase(java.util.Locale.ROOT)}")
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                                Text(lbl, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                StudioSliderRow(
                    label = "Köşe Yarıçapı",
                    valueText = "${overlay.cornerRadiusDp.roundToInt()} dp",
                    value = overlay.cornerRadiusDp,
                    valueRange = 0f..32f,
                    onValueChange = { onOverlayChanged(overlay.copy(cornerRadiusDp = it)) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Çerçeve Çizgisi", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = overlay.showBorder,
                        onCheckedChange = { onOverlayChanged(overlay.copy(showBorder = it)) }
                    )
                }

                if (overlay.showBorder) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BorderStyle.entries.forEach { style ->
                            val lbl = when (style) {
                                BorderStyle.SOLID -> "Düz"
                                BorderStyle.DASHED -> "Kesikli"
                                BorderStyle.DOTTED -> "Noktalı"
                                BorderStyle.DOUBLE -> "Çift Çizgi"
                            }
                            FilterChip(
                                selected = overlay.borderStyle == style,
                                onClick = { onOverlayChanged(overlay.copy(borderStyle = style)) },
                                label = { Text(lbl, fontSize = 12.sp) }
                            )
                        }
                    }

                    StudioSliderRow(
                        label = "Çerçeve Kalınlığı",
                        valueText = "${"%.1f".format(overlay.borderWidthDp)} dp",
                        value = overlay.borderWidthDp,
                        valueRange = 0.5f..6.0f,
                        onValueChange = { onOverlayChanged(overlay.copy(borderWidthDp = it)) }
                    )
                }
            }
        }

        // Glow & Depth
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Derinlik & Parıltı", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Neon Parıltı (Glow)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.showGlow,
                        onCheckedChange = { onOverlayChanged(overlay.copy(showGlow = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Alt Gölge", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.showDropShadow,
                        onCheckedChange = { onOverlayChanged(overlay.copy(showDropShadow = it)) }
                    )
                }

                if (overlay.showDropShadow) {
                    StudioSliderRow(
                        label = "Gölge Yüksekliği",
                        valueText = "${overlay.shadowElevationDp.roundToInt()} dp",
                        value = overlay.shadowElevationDp,
                        valueRange = 0f..24f,
                        onValueChange = { onOverlayChanged(overlay.copy(shadowElevationDp = it)) }
                    )
                }
            }
        }

        // Dimensions & Table Density
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Boyut, İç Boşluk & Tablo Sıklığı", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

                StudioSliderRow(
                    label = "Genel Ölçek (AR Ekranı)",
                    valueText = "%${(overlay.scale * 100).roundToInt()}",
                    value = overlay.scale,
                    valueRange = 0.5f..3.0f,
                    onValueChange = { onOverlayChanged(overlay.copy(scale = it)) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Otomatik Genişlik", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.autoWidth,
                        onCheckedChange = { onOverlayChanged(overlay.copy(autoWidth = it)) }
                    )
                }
                if (!overlay.autoWidth) {
                    StudioSliderRow(
                        label = "Özel Genişlik",
                        valueText = "${overlay.customWidth.roundToInt()} dp",
                        value = overlay.customWidth,
                        valueRange = 140f..360f,
                        onValueChange = { onOverlayChanged(overlay.copy(customWidth = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Otomatik Yükseklik", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.autoHeight,
                        onCheckedChange = { onOverlayChanged(overlay.copy(autoHeight = it)) }
                    )
                }
                if (!overlay.autoHeight) {
                    StudioSliderRow(
                        label = "Özel Yükseklik",
                        valueText = "${overlay.customHeight.roundToInt()} dp",
                        value = overlay.customHeight,
                        valueRange = 60f..320f,
                        onValueChange = { onOverlayChanged(overlay.copy(customHeight = it)) }
                    )
                }

                StudioSliderRow(
                    label = "İç Boşluk (Padding)",
                    valueText = "${overlay.paddingDp.roundToInt()} dp",
                    value = overlay.paddingDp,
                    valueRange = 2f..24f,
                    onValueChange = { onOverlayChanged(overlay.copy(paddingDp = it)) }
                )

                StudioSliderRow(
                    label = "Tablo Satır Sıklığı",
                    valueText = "${overlay.rowPaddingDp.roundToInt()} dp",
                    value = overlay.rowPaddingDp,
                    valueRange = 2f..14f,
                    onValueChange = { onOverlayChanged(overlay.copy(rowPaddingDp = it)) }
                )

                StudioSliderRow(
                    label = "Zebra Satır Kontrastı",
                    valueText = "%${(overlay.rowStripeOpacity * 100).roundToInt()}",
                    value = overlay.rowStripeOpacity,
                    valueRange = 0f..0.35f,
                    onValueChange = { onOverlayChanged(overlay.copy(rowStripeOpacity = it)) }
                )
            }
        }
    }
}

@Composable
private fun LayoutAndExtrasInspectorPanel(
    overlay: OverlayAppearance,
    definedVariables: List<VariableDefinition>,
    onOverlayChanged: (OverlayAppearance) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Table Structure Quick Toggles
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tablo Yapısı", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                val tc = overlay.tableConfig
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Üst Başlık Çubuğu", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = tc.showHeader,
                        onCheckedChange = { onOverlayChanged(overlay.copy(tableConfig = tc.copy(showHeader = it))) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sütun Başlıkları (Alan / Değer)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = tc.showColumnHeaders,
                        onCheckedChange = { onOverlayChanged(overlay.copy(tableConfig = tc.copy(showColumnHeaders = it))) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Hücre Koordinatları (A1, A2)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = tc.showCoordinates,
                        onCheckedChange = { onOverlayChanged(overlay.copy(tableConfig = tc.copy(showCoordinates = it))) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Satır Izgara Çizgileri", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = tc.showGridLines,
                        onCheckedChange = { onOverlayChanged(overlay.copy(tableConfig = tc.copy(showGridLines = it))) }
                    )
                }
            }
        }

        // Icon Picker
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Başlık / Kart İkonu", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "calculator" to "Hesap",
                        "tag" to "Etiket",
                        "qr" to "QR",
                        "sparkle" to "Parıltı",
                        "check" to "Onay",
                        "star" to "Yıldız",
                        "none" to "Yok"
                    ).forEach { (iconKey, iconLabel) ->
                        FilterChip(
                            selected = overlay.iconName == iconKey,
                            onClick = { onOverlayChanged(overlay.copy(iconName = iconKey)) },
                            label = { Text(iconLabel, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        // Customer Display Mode (Sadece Ana Sonuç)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (overlay.customerPriceOnly) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Sadece Ana Sonuç Modu",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Açıkken ekranda sadece yıldızlı ana sonuç büyük görünür; diğer değişkenler gizlenir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = overlay.customerPriceOnly,
                    onCheckedChange = { onOverlayChanged(overlay.copy(customerPriceOnly = it)) },
                    modifier = Modifier.testTag("customer_price_only_switch")
                )
            }
        }

        // Callout Layout & Leader Lines
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("callout_layout_settings_card")
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Çakışma Önleme & Rehber Çizgiler", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CalloutLayoutMode.entries.forEach { mode ->
                        val isSelected = overlay.calloutMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { onOverlayChanged(overlay.copy(calloutMode = mode)) },
                            label = {
                                Text(
                                    when (mode) {
                                        CalloutLayoutMode.SMART_ANTI_COLLISION -> "Akıllı Dağıtım"
                                        CalloutLayoutMode.DOCKED_SIDEBAR -> "Kenar Şeridi"
                                        CalloutLayoutMode.DIRECT_FOLLOW -> "Doğrudan Takip"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }

                if (overlay.calloutMode != CalloutLayoutMode.DIRECT_FOLLOW) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LeaderLineStyle.entries.forEach { style ->
                            FilterChip(
                                selected = overlay.leaderLineStyle == style,
                                onClick = { onOverlayChanged(overlay.copy(leaderLineStyle = style)) },
                                label = {
                                    Text(
                                        when (style) {
                                            LeaderLineStyle.DASHED -> "Kesikli"
                                            LeaderLineStyle.THIN_SOLID -> "İnce Düz"
                                            LeaderLineStyle.ELBOW_JOINT -> "HUD 90° Dirsek"
                                        },
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Barkod Kesikli Çerçevesi", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = overlay.showBarcodeDashedOutline,
                            onCheckedChange = { onOverlayChanged(overlay.copy(showBarcodeDashedOutline = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Barkod Bağlantı Noktası", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = overlay.showAnchorDot,
                            onCheckedChange = { onOverlayChanged(overlay.copy(showAnchorDot = it)) }
                        )
                    }

                    StudioSliderRow(
                        label = "Çizgi Kalınlığı",
                        valueText = "${"%.1f".format(overlay.leaderLineWidthDp)} dp",
                        value = overlay.leaderLineWidthDp,
                        valueRange = 1.0f..4.0f,
                        steps = 5,
                        onValueChange = { onOverlayChanged(overlay.copy(leaderLineWidthDp = it)) }
                    )
                }
            }
        }

        // Anchor Position & X/Y Offsets
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Konum & Kaydırma", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OverlayAnchorPosition.entries.forEach { anchor ->
                        val label = when (anchor) {
                            OverlayAnchorPosition.CENTER -> "Orta"
                            OverlayAnchorPosition.ABOVE -> "Üstte"
                            OverlayAnchorPosition.BELOW -> "Altta"
                            OverlayAnchorPosition.TOP_LEFT -> "Sol Üst"
                            OverlayAnchorPosition.TOP_RIGHT -> "Sağ Üst"
                            OverlayAnchorPosition.BOTTOM_LEFT -> "Sol Alt"
                            OverlayAnchorPosition.BOTTOM_RIGHT -> "Sağ Alt"
                        }
                        FilterChip(
                            selected = overlay.anchorPosition == anchor,
                            onClick = { onOverlayChanged(overlay.copy(anchorPosition = anchor)) },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ekran Kenarlarından Otomatik Kaçın", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.autoAvoidEdges,
                        onCheckedChange = { onOverlayChanged(overlay.copy(autoAvoidEdges = it)) }
                    )
                }

                StudioSliderRow(
                    label = "Yatay Kaydırma (X)",
                    valueText = "${overlay.offsetX.roundToInt()} dp",
                    value = overlay.offsetX,
                    valueRange = -150f..150f,
                    onValueChange = { onOverlayChanged(overlay.copy(offsetX = it)) }
                )

                StudioSliderRow(
                    label = "Dikey Kaydırma (Y)",
                    valueText = "${overlay.offsetY.roundToInt()} dp",
                    value = overlay.offsetY,
                    valueRange = -150f..150f,
                    onValueChange = { onOverlayChanged(overlay.copy(offsetY = it)) }
                )
            }
        }

        // Compact Card Badges
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Özet Kart Rozetleri", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Formül Rozeti", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.showCalculationFormulaHint,
                        onCheckedChange = { onOverlayChanged(overlay.copy(showCalculationFormulaHint = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ham Barkod Rozeti", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = overlay.showRawQrPill,
                        onCheckedChange = { onOverlayChanged(overlay.copy(showRawQrPill = it)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Değişken Rozetleri", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = overlay.showVariableSummaryPill,
                        onCheckedChange = { onOverlayChanged(overlay.copy(showVariableSummaryPill = it)) }
                    )
                }

                if (overlay.showVariableSummaryPill && definedVariables.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        definedVariables.forEach { v ->
                            val isVisible = v.name !in overlay.hiddenPillVariables
                            FilterChip(
                                selected = isVisible,
                                onClick = {
                                    val updatedHidden = if (isVisible) {
                                        overlay.hiddenPillVariables + v.name
                                    } else {
                                        overlay.hiddenPillVariables.filterNot { it == v.name }
                                    }
                                    onOverlayChanged(overlay.copy(hiddenPillVariables = updatedHidden))
                                },
                                label = { Text(v.alias.ifBlank { v.name }, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }
    }
}
