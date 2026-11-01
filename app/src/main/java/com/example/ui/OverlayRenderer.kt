package com.example.ui

import android.graphics.Color as AndroidColor
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import kotlin.math.roundToInt

@Composable
fun QrOverlaysCanvas(
    trackedItems: List<TrackedQr>,
    overlayConfig: OverlayAppearance,
    screenWidthPx: Float,
    screenHeightPx: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        for (item in trackedItems) {
            QrTrackingHudElement(
                tracked = item,
                overlayConfig = overlayConfig,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
        }
    }
}

@Composable
fun QrTrackingHudElement(
    tracked: TrackedQr,
    overlayConfig: OverlayAppearance,
    screenWidthPx: Float,
    screenHeightPx: Float
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
                    val posX = (finalTargetX - placeable.width / 2f).roundToInt()
                    val posY = (finalTargetY - placeable.height / 2f).roundToInt()
                    placeable.placeRelative(posX, posY)
                }
            }
            .alpha(animatedAlpha)
    ) {
        OverlayCardContent(
            result = tracked.result,
            config = overlayConfig
        )
    }
}

/**
 * Renders the customizable CSS-like visual card for a calculation result or error.
 */
@Composable
fun OverlayCardContent(
    result: ParsedQrResult,
    config: OverlayAppearance,
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

            val hasPills = (config.showCalculationFormulaHint && result.formulaExpression.isNotBlank()) ||
                    (config.showRawQrPill && result.rawText.isNotBlank()) ||
                    visibleVariables.isNotEmpty()

            if (hasPills) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = if (config.textAlignCenter) Arrangement.Center else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

                    // Individual Variable Pills
                    visibleVariables.forEach { (name, value) ->
                        val cleanVal = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(0.5.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$name: $cleanVal",
                                color = Color.White.copy(alpha = 0.9f),
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
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawReticleCorners(
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
