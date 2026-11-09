package com.example.ui

import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.*
import java.io.File
import kotlin.math.roundToInt

@Composable
fun QrOverlaysCanvas(
    trackedItems: List<TrackedQr>,
    overlayConfig: OverlayAppearance,
    screenWidthPx: Float,
    screenHeightPx: Float,
    showRawQrData: Boolean = false,
    pinnedQr: TrackedQr? = null,
    onPinClick: ((TrackedQr) -> Unit)? = null,
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    val solvedPositions = remember(trackedItems, overlayConfig, screenWidthPx, screenHeightPx, density) {
        CalloutLayoutSolver.solve(
            items = trackedItems,
            config = overlayConfig,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx,
            density = density
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Unified AR background canvas: Leader lines, dashed barcode reticles & anchor pins (Hidden in raw data mode or when pinned)
        if (!showRawQrData && pinnedQr == null) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                CalloutLayoutSolver.drawCalloutAtmosphere(
                    scope = this,
                    solvedItems = solvedPositions,
                    config = overlayConfig
                )
            }
        }

        // Render each data box at its position (Hidden when pinned since pinned card is displayed full width)
        if (pinnedQr == null) {
            for (solved in solvedPositions) {
                val targetX = if (showRawQrData) solved.tracked.currentBox.centerX() else solved.cardCenterX
                val targetY = if (showRawQrData) solved.tracked.currentBox.centerY() else solved.cardCenterY

                val animatedX by animateFloatAsState(
                    targetValue = targetX,
                    animationSpec = tween(durationMillis = 180),
                    label = "callout_x_${solved.tracked.id}"
                )
                val animatedY by animateFloatAsState(
                    targetValue = targetY,
                    animationSpec = tween(durationMillis = 180),
                    label = "callout_y_${solved.tracked.id}"
                )
                val animatedAlpha by animateFloatAsState(
                    targetValue = solved.tracked.alpha,
                    animationSpec = tween(durationMillis = 150),
                    label = "callout_alpha_${solved.tracked.id}"
                )

                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                val marginPx = with(density) { 14.dp.toPx() }
                                val topSafePx = with(density) { 70.dp.toPx() }
                                val bottomSafePx = with(density) { 84.dp.toPx() }

                                val minX = marginPx.roundToInt()
                                val maxX = (constraints.maxWidth - placeable.width - marginPx).roundToInt().coerceAtLeast(minX)
                                val minY = topSafePx.roundToInt()
                                val maxY = (constraints.maxHeight - placeable.height - bottomSafePx).roundToInt().coerceAtLeast(minY)

                                val rawPosX = (animatedX - placeable.width / 2f).roundToInt()
                                val rawPosY = (animatedY - placeable.height / 2f).roundToInt()

                                val clampedX = rawPosX.coerceIn(minX, maxX)
                                val clampedY = rawPosY.coerceIn(minY, maxY)

                                placeable.placeRelative(clampedX, clampedY)
                            }
                        }
                        .alpha(if (showRawQrData) 1f else animatedAlpha)
                ) {
                    if (showRawQrData) {
                        RawQrOverlayCardContent(
                            tracked = solved.tracked,
                            config = overlayConfig
                        )
                    } else {
                        Box {
                            OverlayCardContent(
                                result = solved.tracked.result,
                                config = overlayConfig,
                                attachment = solved.tracked.attachment,
                                onAttachmentClick = onAttachmentClick
                            )

                            // Small Pin Icon on the overlay when it appears
                            IconButton(
                                onClick = { onPinClick?.invoke(solved.tracked) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(30.dp)
                                    .testTag("pin_overlay_${solved.tracked.id}")
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4).copy(alpha = 0.6f)),
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Icon(
                                            imageVector = Icons.Outlined.PushPin,
                                            contentDescription = "Pin overlay full width",
                                            tint = Color(0xFF00F5D4),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pinned Full-Width Overlay Card displayed when the user pins an overlay.
 * Scales to full width, stays on screen, and provides an unpin button to resume scanning.
 */
@Composable
fun PinnedFullWidthOverlayCard(
    tracked: TrackedQr,
    config: OverlayAppearance,
    onUnpin: () -> Unit,
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val themeColors = getExcelThemeColors(config.tableConfig.gridTheme)
    val shape = RoundedCornerShape(16.dp)

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color(0xF5070D18)),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00F5D4)),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Top Bar: Pinned Badge + Scanning Paused Badge + Pin Icon (Unpin action)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF00F5D4).copy(alpha = 0.18f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = null,
                                tint = Color(0xFF00F5D4),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "PINNED",
                                color = Color(0xFF00F5D4),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = "SCANNING PAUSED",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Small Pin Icon Button (Click to unpin and resume scanning)
                FilledTonalIconButton(
                    onClick = onUnpin,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xFF00F5D4),
                        contentColor = Color(0xFF00382E)
                    ),
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("unpin_overlay_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Unpin and resume scan",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Full-width card content according to current display mode
            when (config.displayMode) {
                OverlayDisplayMode.EXCEL_TABLE -> {
                    ExcelTableOverlayContent(
                        result = tracked.result,
                        config = config.copy(scale = 1.0f),
                        attachment = tracked.attachment,
                        onAttachmentClick = onAttachmentClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OverlayDisplayMode.COMPACT_CARD -> {
                    CompactCardOverlayContent(
                        result = tracked.result,
                        config = config.copy(scale = 1.0f, autoWidth = false, customWidth = 600f),
                        attachment = tracked.attachment,
                        onAttachmentClick = onAttachmentClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OverlayDisplayMode.HYBRID -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompactCardOverlayContent(
                            result = tracked.result,
                            config = config.copy(scale = 1.0f, autoWidth = false, customWidth = 600f),
                            attachment = tracked.attachment,
                            onAttachmentClick = onAttachmentClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ExcelTableOverlayContent(
                            result = tracked.result,
                            config = config.copy(scale = 1.0f),
                            attachment = tracked.attachment,
                            onAttachmentClick = onAttachmentClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Unpin Button to resume scanning
            OutlinedButton(
                onClick = onUnpin,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4).copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF00F5D4)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unpin_resume_scan_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Unpin & Resume Scanning",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Raw barcode/QR data display shown strictly when the user presses and holds the Raw QR button.
 * Displays ONLY the raw QR data text, nothing else.
 */
@Composable
fun RawQrOverlayCardContent(
    tracked: TrackedQr,
    config: OverlayAppearance,
    modifier: Modifier = Modifier
) {
    val scale = config.scale.coerceIn(0.7f, 2.5f)
    Surface(
        shape = RoundedCornerShape((10 * scale).dp),
        color = Color(0xF5050811),
        border = androidx.compose.foundation.BorderStroke((1.5f * scale).dp, Color(0xFF00F5D4)),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Text(
            text = tracked.rawContent,
            color = Color.White,
            fontSize = (13 * scale).sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = (12 * scale).dp, vertical = (8 * scale).dp)
        )
    }
}

@Composable
fun QrTrackingHudElement(
    tracked: TrackedQr,
    overlayConfig: OverlayAppearance,
    screenWidthPx: Float,
    screenHeightPx: Float,
    onAttachmentClick: ((QrAttachment) -> Unit)? = null
) {
    val density = LocalDensity.current
    val animatedAlpha by animateFloatAsState(
        targetValue = tracked.alpha,
        animationSpec = tween(durationMillis = 150),
        label = "overlay_alpha"
    )

    val box = tracked.currentBox

    // AR HUD Bounding Reticle around the QR itself
    val reticleColor = parseHexColor(overlayConfig.borderColorHex).copy(alpha = 0.6f * animatedAlpha)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawReticleCorners(box, reticleColor)
            }
    )

    // Compute anchor center position in screen coordinates
    val anchorX: Float
    val anchorY: Float

    when (overlayConfig.anchorPosition) {
        OverlayAnchorPosition.CENTER -> {
            anchorX = box.centerX()
            anchorY = box.centerY()
        }
        OverlayAnchorPosition.ABOVE -> {
            anchorX = box.centerX()
            anchorY = box.top - 60f
        }
        OverlayAnchorPosition.BELOW -> {
            anchorX = box.centerX()
            anchorY = box.bottom + 60f
        }
        OverlayAnchorPosition.TOP_LEFT -> {
            anchorX = box.left
            anchorY = box.top
        }
        OverlayAnchorPosition.TOP_RIGHT -> {
            anchorX = box.right
            anchorY = box.top
        }
        OverlayAnchorPosition.BOTTOM_LEFT -> {
            anchorX = box.left
            anchorY = box.bottom
        }
        OverlayAnchorPosition.BOTTOM_RIGHT -> {
            anchorX = box.right
            anchorY = box.bottom
        }
    }

    val offsetXPx = with(density) { overlayConfig.offsetX.dp.toPx() }
    val offsetYPx = with(density) { overlayConfig.offsetY.dp.toPx() }

    var finalTargetX = anchorX + offsetXPx
    var finalTargetY = anchorY + offsetYPx

    // Screen boundary avoidance
    if (overlayConfig.autoAvoidEdges && screenWidthPx > 0 && screenHeightPx > 0) {
        val margin = with(density) { 24.dp.toPx() }
        val estHalfW = with(density) { (overlayConfig.customWidth / 2f).dp.toPx() }
        val estHalfH = with(density) { (overlayConfig.customHeight / 2f).dp.toPx() }

        finalTargetX = finalTargetX.coerceIn(margin + estHalfW, screenWidthPx - margin - estHalfW)
        finalTargetY = finalTargetY.coerceIn(margin + estHalfH, screenHeightPx - margin - estHalfH)
    }

    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val marginPx = with(density) { 14.dp.toPx() }
                    val topSafePx = with(density) { 70.dp.toPx() }
                    val bottomSafePx = with(density) { 84.dp.toPx() }

                    val minX = marginPx.roundToInt()
                    val maxX = (constraints.maxWidth - placeable.width - marginPx).roundToInt().coerceAtLeast(minX)
                    val minY = topSafePx.roundToInt()
                    val maxY = (constraints.maxHeight - placeable.height - bottomSafePx).roundToInt().coerceAtLeast(minY)

                    val rawPosX = (finalTargetX - placeable.width / 2f).roundToInt()
                    val rawPosY = (finalTargetY - placeable.height / 2f).roundToInt()

                    val clampedX = rawPosX.coerceIn(minX, maxX)
                    val clampedY = rawPosY.coerceIn(minY, maxY)

                    placeable.placeRelative(clampedX, clampedY)
                }
            }
            .alpha(animatedAlpha)
    ) {
        OverlayCardContent(
            result = tracked.result,
            config = overlayConfig,
            attachment = tracked.attachment,
            onAttachmentClick = onAttachmentClick
        )
    }
}

/**
 * Main overlay router: dispatches between Mini Excel Table, Compact Card, or Hybrid mode.
 */
@Composable
fun OverlayCardContent(
    result: ParsedQrResult,
    config: OverlayAppearance,
    attachment: QrAttachment? = null,
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    when (config.displayMode) {
        OverlayDisplayMode.EXCEL_TABLE -> {
            ExcelTableOverlayContent(
                result = result,
                config = config,
                attachment = attachment,
                onAttachmentClick = onAttachmentClick,
                modifier = modifier
            )
        }
        OverlayDisplayMode.COMPACT_CARD -> {
            CompactCardOverlayContent(
                result = result,
                config = config,
                attachment = attachment,
                onAttachmentClick = onAttachmentClick,
                modifier = modifier
            )
        }
        OverlayDisplayMode.HYBRID -> {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactCardOverlayContent(
                    result = result,
                    config = config,
                    attachment = attachment,
                    onAttachmentClick = onAttachmentClick
                )
                ExcelTableOverlayContent(
                    result = result,
                    config = config,
                    attachment = attachment,
                    onAttachmentClick = onAttachmentClick
                )
            }
        }
    }
}

/**
 * Palette colors for the Mini Excel Table themes.
 */
data class ExcelThemeColors(
    val accent: Color,
    val headerBgStart: Color,
    val headerBgEnd: Color,
    val containerBg: Color,
    val gridBorder: Color,
    val rowBgEven: Color,
    val rowBgOdd: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val coordinateBg: Color,
    val coordinateText: Color,
    val emphasizedBg: Brush,
    val emphasizedBorder: Color,
    val emphasizedText: Color
)

fun getExcelThemeColors(theme: ExcelGridTheme): ExcelThemeColors {
    return when (theme) {
        ExcelGridTheme.EXCEL_CLASSIC -> ExcelThemeColors(
            accent = Color(0xFF107C41),
            headerBgStart = Color(0xFF0F3B25),
            headerBgEnd = Color(0xFF0A2216),
            containerBg = Color(0xF5071A11),
            gridBorder = Color(0x4422C55E),
            rowBgEven = Color(0x00000000),
            rowBgOdd = Color(0x1822C55E),
            textPrimary = Color(0xFFF1F5F9),
            textSecondary = Color(0xFF86EFAC),
            coordinateBg = Color(0xFF0A2E1C),
            coordinateText = Color(0xFF4ADE80),
            emphasizedBg = Brush.horizontalGradient(listOf(Color(0xFF065F46), Color(0xFF047857))),
            emphasizedBorder = Color(0xFF34D399),
            emphasizedText = Color(0xFFFFFFFF)
        )
        ExcelGridTheme.NEON_CYAN -> ExcelThemeColors(
            accent = Color(0xFF00F5D4),
            headerBgStart = Color(0xFF083344),
            headerBgEnd = Color(0xFF041C24),
            containerBg = Color(0xF5031B22),
            gridBorder = Color(0x4400F5D4),
            rowBgEven = Color(0x00000000),
            rowBgOdd = Color(0x1800F5D4),
            textPrimary = Color(0xFFF0FDFA),
            textSecondary = Color(0xFF5EEAD4),
            coordinateBg = Color(0xFF072935),
            coordinateText = Color(0xFF2DD4BF),
            emphasizedBg = Brush.horizontalGradient(listOf(Color(0xFF0E7490), Color(0xFF0891B2))),
            emphasizedBorder = Color(0xFF22D3EE),
            emphasizedText = Color(0xFFFFFFFF)
        )
        ExcelGridTheme.MODERN_SLATE -> ExcelThemeColors(
            accent = Color(0xFF38BDF8),
            headerBgStart = Color(0xFF1E293B),
            headerBgEnd = Color(0xFF0F172A),
            containerBg = Color(0xF50B1120),
            gridBorder = Color(0x4438BDF8),
            rowBgEven = Color(0x00000000),
            rowBgOdd = Color(0x1838BDF8),
            textPrimary = Color(0xFFF8FAFC),
            textSecondary = Color(0xFF94A3B8),
            coordinateBg = Color(0xFF1E293B),
            coordinateText = Color(0xFF7DD3FC),
            emphasizedBg = Brush.horizontalGradient(listOf(Color(0xFF0369A1), Color(0xFF0284C7))),
            emphasizedBorder = Color(0xFF38BDF8),
            emphasizedText = Color(0xFFFFFFFF)
        )
        ExcelGridTheme.AMBER_GOLD -> ExcelThemeColors(
            accent = Color(0xFFF59E0B),
            headerBgStart = Color(0xFF451A03),
            headerBgEnd = Color(0xFF260E02),
            containerBg = Color(0xF51C0D03),
            gridBorder = Color(0x44F59E0B),
            rowBgEven = Color(0x00000000),
            rowBgOdd = Color(0x18F59E0B),
            textPrimary = Color(0xFFFEF3C7),
            textSecondary = Color(0xFFFCD34D),
            coordinateBg = Color(0xFF351502),
            coordinateText = Color(0xFFFBBF24),
            emphasizedBg = Brush.horizontalGradient(listOf(Color(0xFF92400E), Color(0xFFB45309))),
            emphasizedBorder = Color(0xFFF59E0B),
            emphasizedText = Color(0xFFFFFFFF)
        )
    }
}

/**
 * Resolves effective rows with formatted values, prefixes, and postfixes.
 */
fun resolveTableRows(
    tableConfig: ExcelTableConfig,
    result: ParsedQrResult,
    variableDefs: List<VariableDefinition> = emptyList(),
    attachment: QrAttachment? = null
): List<Pair<ExcelTableCellItem, String>> {
    val itemsToUse = if (tableConfig.items.isNotEmpty()) {
        tableConfig.items
    } else {
        // Auto-generate from extracted variables + computed variables
        val autoList = mutableListOf<ExcelTableCellItem>()
        var coordIdx = 1
        result.extractedVariables.forEach { (name, _) ->
            if (!name.contains("_alias")) {
                val isComp = variableDefs.find { it.name == name }?.isComputed ?: false
                autoList.add(
                    ExcelTableCellItem(
                        source = if (isComp) TableItemSource.COMPUTED else TableItemSource.VARIABLE,
                        variableName = name,
                        label = name.replaceFirstChar { it.uppercase() },
                        coordinate = "A$coordIdx",
                        isVisible = true,
                        isEmphasized = isComp
                    )
                )
                coordIdx++
            }
        }
        autoList
    }

    val baseRows = itemsToUse.filter { it.isVisible }.mapIndexed { index, item ->
        val resolvedValue = when (item.source) {
            TableItemSource.VARIABLE, TableItemSource.COMPUTED -> {
                val numVal = result.extractedVariables[item.variableName]
                if (numVal != null) {
                    val varDef = variableDefs.find { it.name == item.variableName || it.alias == item.variableName }
                    val bareFormatted = if (varDef != null) {
                        com.example.engine.ResultFormatter.formatVariable(numVal, varDef, includePrefixPostfix = false)
                    } else {
                        if (numVal % 1.0 == 0.0) {
                            numVal.toLong().toString()
                        } else {
                            "%.2f".format(numVal)
                        }
                    }
                    val effectivePrefix = if (item.prefix.isNotEmpty()) item.prefix else (varDef?.prefix ?: "")
                    val effectivePostfix = if (item.postfix.isNotEmpty()) item.postfix else (varDef?.postfix ?: "")
                    "$effectivePrefix$bareFormatted$effectivePostfix"
                } else {
                    val strVal = result.extractedStrings[item.variableName]
                    if (strVal != null) {
                        val effectivePrefix = if (item.prefix.isNotEmpty()) item.prefix else ""
                        val effectivePostfix = if (item.postfix.isNotEmpty()) item.postfix else ""
                        "$effectivePrefix$strVal$effectivePostfix"
                    } else if (item.customValue.isNotBlank()) {
                        "${item.prefix}${item.customValue}${item.postfix}"
                    } else {
                        "-"
                    }
                }
            }
            TableItemSource.FORMULA_RESULT -> {
                when (val status = result.status) {
                    is CalculationStatus.Success -> {
                        "${item.prefix}${status.formattedText}${item.postfix}"
                    }
                    is CalculationStatus.ParseError -> "ERR"
                    is CalculationStatus.EvaluationError -> "ERR"
                }
            }
            TableItemSource.RAW_QR -> {
                "${item.prefix}${result.rawText.take(16)}${item.postfix}"
            }
            TableItemSource.CUSTOM_TEXT -> {
                "${item.prefix}${item.customValue}${item.postfix}"
            }
        }
        val effectiveItem = if (item.coordinate.isBlank()) {
            item.copy(coordinate = "A${index + 1}")
        } else item
        Pair(effectiveItem, resolvedValue)
    }

    val attachmentRows = mutableListOf<Pair<ExcelTableCellItem, String>>()
    if (attachment != null && attachment.showInTable) {
        var attachCoord = baseRows.size + 1
        if (attachment.title.isNotBlank()) {
            attachmentRows.add(
                ExcelTableCellItem(
                    label = "Item",
                    coordinate = "M$attachCoord",
                    isEmphasized = true
                ) to attachment.title
            )
            attachCoord++
        }
        attachment.customFields.forEach { (k, v) ->
            attachmentRows.add(
                ExcelTableCellItem(
                    label = k,
                    coordinate = "M$attachCoord",
                    isEmphasized = false
                ) to v
            )
            attachCoord++
        }
    }

    return baseRows + attachmentRows
}

/**
 * Renders the high-fidelity Mini Excel Spreadsheet Table overlay.
 */
@Composable
fun ExcelTableOverlayContent(
    result: ParsedQrResult,
    config: OverlayAppearance,
    attachment: QrAttachment? = null,
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tableConfig = config.tableConfig
    val themeColors = getExcelThemeColors(tableConfig.gridTheme)
    val shape = RoundedCornerShape(10.dp)

    val rows = resolveTableRows(tableConfig, result, attachment = attachment)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = config.scale
                scaleY = config.scale
            }
            .shadow(elevation = 12.dp, shape = shape, spotColor = themeColors.accent, ambientColor = themeColors.accent)
            .border(1.dp, themeColors.gridBorder, shape)
            .background(themeColors.containerBg, shape)
            .clip(shape)
            .widthIn(min = 200.dp, max = 340.dp)
            .padding(config.paddingDp.dp.coerceAtMost(10.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Attached Media Banner / Thumbnail
            if (attachment != null && attachment.showInOverlay) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, themeColors.accent.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clickable { onAttachmentClick?.invoke(attachment) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (attachment.allImages.isNotEmpty()) {
                            val firstImg = attachment.allImages.first()
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black)
                            ) {
                                AsyncImage(
                                    model = File(firstImg).takeIf { it.exists() } ?: Uri.parse(firstImg),
                                    contentDescription = "Thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (attachment.allImages.size > 1) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(topStart = 4.dp),
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                    ) {
                                        Text(
                                            text = "+${attachment.allImages.size - 1}",
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.title.ifBlank { "Linked Data" },
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (attachment.allVideos.isNotEmpty()) {
                                Text(
                                    text = if (attachment.allVideos.size > 1) "▶ ${attachment.allVideos.size} Videos Attached" else "▶ Video Attached",
                                    color = themeColors.accent,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // 1. Spreadsheet Header Bar (if enabled)
            if (tableConfig.showHeader) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(themeColors.headerBgStart, themeColors.headerBgEnd)
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(themeColors.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TableChart,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tableConfig.headerTitle.ifBlank { "DATA SHEET" }.uppercase(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Barcode Format Tag Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(themeColors.accent.copy(alpha = 0.22f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = result.formatName,
                                color = themeColors.accent,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp
                            )
                        }

                        if (tableConfig.headerSubtitle.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = tableConfig.headerSubtitle,
                                    color = themeColors.textSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 2. Column Headers Row (if enabled)
            if (tableConfig.showColumnHeaders && rows.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tableConfig.showCoordinates) {
                        Text(
                            text = "#",
                            color = themeColors.textSecondary.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(28.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = "CELL / PARAMETER",
                        color = themeColors.textSecondary.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1.1f)
                    )
                    Text(
                        text = "VALUE",
                        color = themeColors.textSecondary.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.End
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            // 3. Data Rows & Cells
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        if (tableConfig.showGridLines) 0.5.dp else 0.dp,
                        themeColors.gridBorder.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp)
                    )
            ) {
                rows.forEachIndexed { index, (item, resolvedVal) ->
                    val isEven = index % 2 == 0
                    val rowBg = if (item.isEmphasized) {
                        themeColors.emphasizedBg
                    } else if (isEven) {
                        Brush.horizontalGradient(listOf(themeColors.rowBgEven, themeColors.rowBgEven))
                    } else {
                        Brush.horizontalGradient(listOf(themeColors.rowBgOdd, themeColors.rowBgOdd))
                    }

                    val rowBorder = if (item.isEmphasized) {
                        Modifier.border(1.dp, themeColors.emphasizedBorder, RoundedCornerShape(4.dp))
                    } else Modifier

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(rowBorder)
                            .clip(RoundedCornerShape(4.dp))
                            .background(rowBg)
                            .padding(horizontal = 8.dp, vertical = if (item.isEmphasized) 6.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cell Coordinate (A1, A2...)
                        if (tableConfig.showCoordinates) {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (item.isEmphasized) Color.Black.copy(alpha = 0.3f) else themeColors.coordinateBg
                                    )
                                    .padding(vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.coordinate.ifBlank { "A${index + 1}" },
                                    color = if (item.isEmphasized) Color.White else themeColors.coordinateText,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // Parameter / Item Label
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1.1f)
                        ) {
                            if (item.isEmphasized) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Emphasized",
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier
                                        .size(11.dp)
                                        .padding(end = 3.dp)
                                )
                            }
                            Text(
                                text = item.label.ifBlank { item.variableName.ifBlank { "Item" } },
                                color = if (item.isEmphasized) themeColors.emphasizedText else themeColors.textPrimary,
                                fontSize = if (item.isEmphasized) 12.sp else 11.sp,
                                fontWeight = if (item.isEmphasized) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }

                        // Formatted Cell Value
                        Text(
                            text = resolvedVal,
                            color = if (item.isEmphasized) themeColors.emphasizedText else themeColors.accent,
                            fontSize = if (item.isEmphasized) 13.sp else 11.sp,
                            fontWeight = if (item.isEmphasized) FontWeight.ExtraBold else FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1.2f),
                            maxLines = 1
                        )
                    }

                    // Divider between rows if enabled
                    if (tableConfig.showGridLines && index < rows.size - 1 && !item.isEmphasized) {
                        HorizontalDivider(
                            color = themeColors.gridBorder.copy(alpha = 0.25f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }

            // 4. Status Warning if Parse or Math Error
            when (val status = result.status) {
                is CalculationStatus.ParseError -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF450A0A))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "#PARSE: ${status.message}",
                            color = Color(0xFFFCA5A5),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                is CalculationStatus.EvaluationError -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF451A03))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "#VALUE!: ${status.message}",
                            color = Color(0xFFFDE68A),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                is CalculationStatus.Success -> {
                    // All good
                }
            }
        }
    }
}

/**
 * Classic single-card overlay with variable pills.
 */
@Composable
fun CompactCardOverlayContent(
    result: ParsedQrResult,
    config: OverlayAppearance,
    attachment: QrAttachment? = null,
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cornerRadius = config.cornerRadiusDp.dp
    val shape = RoundedCornerShape(cornerRadius)

    val textColor = parseHexColor(config.textColorHex)
    val bgColor = parseHexColor(config.backgroundColorHex).copy(alpha = config.backgroundOpacity)
    val gradEndColor = parseHexColor(config.gradientColorEndHex).copy(alpha = config.backgroundOpacity)
    val borderColor = parseHexColor(config.borderColorHex)

    val backgroundModifier = when (config.fillMode) {
        OverlayFillMode.FILLED_SOLID -> Modifier.background(bgColor, shape)
        OverlayFillMode.FILLED_GRADIENT -> Modifier.background(
            Brush.linearGradient(
                listOf(bgColor, gradEndColor),
                start = Offset.Zero,
                end = Offset.Infinite
            ),
            shape
        )
        OverlayFillMode.SEMI_TRANSPARENT -> Modifier.background(bgColor.copy(alpha = 0.78f), shape)
        OverlayFillMode.OUTLINE_ONLY -> Modifier.background(Color.Transparent, shape)
    }

    val shadowModifier = if (config.showDropShadow) {
        Modifier.shadow(elevation = 10.dp, shape = shape, ambientColor = borderColor, spotColor = borderColor)
    } else Modifier

    val borderModifier = if (config.showBorder) {
        if (config.borderStyle == BorderStyle.DASHED) {
            Modifier.drawBehind {
                val strokeWidth = config.borderWidthDp.dp.toPx()
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
                drawRoundRect(
                    color = borderColor,
                    size = size,
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                    style = Stroke(width = strokeWidth, pathEffect = pathEffect)
                )
            }
        } else {
            Modifier.border(config.borderWidthDp.dp, borderColor, shape)
        }
    } else Modifier

    val widthModifier = if (config.autoWidth) Modifier.widthIn(min = 140.dp, max = 340.dp) else Modifier.width(config.customWidth.dp)
    val heightModifier = if (config.autoHeight) Modifier.heightIn(min = 50.dp) else Modifier.height(config.customHeight.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = config.scale
                scaleY = config.scale
            }
            .then(shadowModifier)
            .then(borderModifier)
            .then(backgroundModifier)
            .clip(shape)
            .then(widthModifier)
            .then(heightModifier)
            .padding(config.paddingDp.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (config.textAlignCenter) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            // Attached Media & Metadata Badge (Photo / Video / Title)
            if (attachment != null && attachment.showInOverlay) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .clickable { onAttachmentClick?.invoke(attachment) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (attachment.allImages.isNotEmpty()) {
                            val firstImg = attachment.allImages.first()
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black)
                            ) {
                                AsyncImage(
                                    model = File(firstImg).takeIf { it.exists() } ?: Uri.parse(firstImg),
                                    contentDescription = "Attached Thumbnail",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (attachment.allImages.size > 1) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.75f),
                                        shape = RoundedCornerShape(topStart = 4.dp),
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                    ) {
                                        Text(
                                            text = "+${attachment.allImages.size - 1}",
                                            color = Color.White,
                                            fontSize = 7.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = attachment.title.ifBlank { "Linked Data" },
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (attachment.allVideos.isNotEmpty()) {
                                Text(
                                    text = if (attachment.allVideos.size > 1) "▶ ${attachment.allVideos.size} Videos Attached" else "▶ Video Attached",
                                    color = Color(0xFF00F5D4),
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (config.textAlignCenter) Arrangement.Center else Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                val icon = getIconForName(config.iconName)
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "Overlay Icon",
                        tint = textColor,
                        modifier = Modifier
                            .size((config.fontSizeSp * 1.1f).dp)
                            .padding(end = 6.dp)
                    )
                }

                when (val status = result.status) {
                    is CalculationStatus.Success -> {
                        Text(
                            text = status.formattedText,
                            color = textColor,
                            fontSize = config.fontSizeSp.sp,
                            fontWeight = if (config.isBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (config.isItalic) FontStyle.Italic else FontStyle.Normal,
                            textAlign = if (config.textAlignCenter) TextAlign.Center else TextAlign.Start,
                            letterSpacing = config.letterSpacingSp.sp
                        )
                    }
                    is CalculationStatus.ParseError -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(16.dp).padding(end = 4.dp)
                            )
                            Text(
                                text = "Parse: ${status.message}",
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    is CalculationStatus.EvaluationError -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(16.dp).padding(end = 4.dp)
                            )
                            Text(
                                text = "Math: ${status.message}",
                                color = Color(0xFFFDE68A),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Individual Pills Row (Formula, Raw QR, and individual Variable pills)
            val visibleVariables = if (config.showVariableSummaryPill) {
                result.extractedVariables.entries
                    .filter { (name, _) -> name !in config.hiddenPillVariables && !name.contains("_alias") }
            } else emptyList()

            val hasPills = true

            if (hasPills) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = if (config.textAlignCenter) Arrangement.Center else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Barcode Format Tag Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(textColor.copy(alpha = 0.16f))
                            .border(0.5.dp, textColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = result.formatName,
                            color = textColor,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))

                    // Formula Pill
                    if (config.showCalculationFormulaHint && result.formulaExpression.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(0.5.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "= ${result.formulaExpression}",
                                color = textColor.copy(alpha = 0.9f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Raw QR Pill
                    if (config.showRawQrPill && result.rawText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(0.5.dp, Color(0xFF64748B), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "QR: ${result.rawText.take(15)}",
                                color = Color(0xFF94A3B8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Individual Variable Pills with custom prefix and postfix
                    visibleVariables.forEach { (name, value) ->
                        val formattedVal = result.variablePillDisplay[name] ?: run {
                            val cleanVal = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
                            cleanVal
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(0.5.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$name: $formattedVal",
                                color = Color.White.copy(alpha = 0.95f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }
        }
    }
}


/**
 * Draws high-tech corner brackets around the tracked QR code
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReticleCorners(
    box: RectF,
    color: Color
) {
    if (box.width() <= 0 || box.height() <= 0) return

    val strokeWidth = 3.dp.toPx()
    val cornerLen = 22.dp.toPx().coerceAtMost(box.width() / 3f)

    // Top-Left
    drawLine(color, Offset(box.left, box.top), Offset(box.left + cornerLen, box.top), strokeWidth)
    drawLine(color, Offset(box.left, box.top), Offset(box.left, box.top + cornerLen), strokeWidth)

    // Top-Right
    drawLine(color, Offset(box.right, box.top), Offset(box.right - cornerLen, box.top), strokeWidth)
    drawLine(color, Offset(box.right, box.top), Offset(box.right, box.top + cornerLen), strokeWidth)

    // Bottom-Left
    drawLine(color, Offset(box.left, box.bottom), Offset(box.left + cornerLen, box.bottom), strokeWidth)
    drawLine(color, Offset(box.left, box.bottom), Offset(box.left, box.bottom - cornerLen), strokeWidth)

    // Bottom-Right
    drawLine(color, Offset(box.right, box.bottom), Offset(box.right - cornerLen, box.bottom), strokeWidth)
    drawLine(color, Offset(box.right, box.bottom), Offset(box.right, box.bottom - cornerLen), strokeWidth)
}

fun parseHexColor(hex: String): Color {
    return try {
        val cleaned = hex.trim().removePrefix("#")
        val colorInt = when (cleaned.length) {
            6 -> AndroidColor.parseColor("#$cleaned")
            8 -> AndroidColor.parseColor("#$cleaned")
            3 -> {
                val r = cleaned[0]; val g = cleaned[1]; val b = cleaned[2]
                AndroidColor.parseColor("#$r$r$g$g$b$b")
            }
            else -> AndroidColor.CYAN
        }
        Color(colorInt)
    } catch (_: Exception) {
        Color(0xFF00F5D4)
    }
}

fun getIconForName(name: String): ImageVector? {
    return when (name.lowercase()) {
        "calculator" -> Icons.Default.Calculate
        "tag" -> Icons.Default.LocalOffer
        "qr" -> Icons.Default.QrCode
        "sparkle" -> Icons.Default.AutoAwesome
        "check" -> Icons.Default.CheckCircle
        "star" -> Icons.Default.Star
        else -> null
    }
}
