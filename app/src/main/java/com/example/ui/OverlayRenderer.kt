package com.example.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import com.example.debug.AppDebugLogger
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.resolveOverlayFontFamily
import com.example.ui.theme.resolveOverlayFontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
    variableDefs: List<VariableDefinition> = emptyList(),
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
                key(solved.tracked.id) {
                    val targetX = if (showRawQrData) solved.tracked.currentBox.centerX() else solved.cardCenterX
                    val targetY = if (showRawQrData) solved.tracked.currentBox.centerY() else solved.cardCenterY

                    val animatedX by animateFloatAsState(
                        targetValue = targetX,
                        animationSpec = tween(durationMillis = 160),
                        label = "callout_x_${solved.tracked.id}"
                    )
                    val animatedY by animateFloatAsState(
                        targetValue = targetY,
                        animationSpec = tween(durationMillis = 160),
                        label = "callout_y_${solved.tracked.id}"
                    )
                    val animatedAlpha by animateFloatAsState(
                        targetValue = solved.tracked.alpha,
                        animationSpec = tween(durationMillis = 140),
                        label = "callout_alpha_${solved.tracked.id}"
                    )

                    var measuredSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
                    val marginPx = with(density) { 14.dp.toPx() }
                    val topSafePx = with(density) { 70.dp.toPx() }
                    val bottomSafePx = with(density) { 84.dp.toPx() }

                    Box(
                        modifier = Modifier
                            .onSizeChanged { measuredSize = it }
                            .graphicsLayer {
                                val cardW = measuredSize.width.toFloat()
                                val cardH = measuredSize.height.toFloat()
                                val maxX = (screenWidthPx - cardW - marginPx).coerceAtLeast(marginPx)
                                val maxY = (screenHeightPx - cardH - bottomSafePx).coerceAtLeast(topSafePx)
                                translationX = (animatedX - cardW / 2f).coerceIn(marginPx, maxX)
                                translationY = (animatedY - cardH / 2f).coerceIn(topSafePx, maxY)
                                alpha = if (showRawQrData) 1f else animatedAlpha
                            }
                    ) {
                        if (showRawQrData) {
                            RawQrOverlayCardContent(
                                tracked = solved.tracked,
                                config = overlayConfig
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .testTag("overlay_label_${solved.tracked.id}")
                                    .clickable { onPinClick?.invoke(solved.tracked) }
                            ) {
                                OverlayCardContent(
                                    result = solved.tracked.result,
                                    config = overlayConfig,
                                    attachment = solved.tracked.attachment,
                                    variableDefs = variableDefs,
                                    onAttachmentClick = onAttachmentClick
                                )
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
 * Scales to full width, stays on screen, provides swipe-down to dismiss, 1-tap Copy & Share actions,
 * and an unpin button to resume live scanning.
 */
@Composable
fun PinnedFullWidthOverlayCard(
    tracked: TrackedQr,
    config: OverlayAppearance,
    onUnpin: () -> Unit,
    variableDefs: List<VariableDefinition> = emptyList(),
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    onSendToComputer: ((textToSend: String?, varName: String?) -> Unit)? = null,
    onToggleCustomerMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val themeColors = getExcelThemeColors(config.tableConfig.gridTheme)
    val shape = RoundedCornerShape(18.dp)

    // Vertical drag offset for swipe-down to dismiss
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val resultText = when (val st = tracked.result.status) {
        is CalculationStatus.Success -> st.formattedText
        is CalculationStatus.ParseError -> "Ayrıştırma Hatası"
        is CalculationStatus.EvaluationError -> "Hesaplama Hatası"
    }

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color(0xF5070D18)),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00F5D4)),
        elevation = CardDefaults.cardElevation(defaultElevation = 18.dp),
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount > 0 || dragOffsetY > 0) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (dragOffsetY > 110f) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onUnpin()
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        dragOffsetY = 0f
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag handle to visually indicate swipe-down gesture
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Top Bar: Pinned Badge + Scanning Paused Badge + Close/Unpin button
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
                                text = "SABİTLENDİ",
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
                            text = "TARAMA DURAKLATILDI",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Right actions: Customer Mode quick toggle + Unpin
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onToggleCustomerMode != null) {
                        FilledTonalIconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onToggleCustomerMode()
                            },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (config.customerPriceOnly) Color(0xFF00F5D4) else Color(0xFF1E293B),
                                contentColor = if (config.customerPriceOnly) Color(0xFF00382E) else Color.White
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("pinned_customer_mode_toggle")
                        ) {
                            Icon(
                                imageVector = if (config.customerPriceOnly) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (config.customerPriceOnly) "Müşteri Modu Açık (Maliyeti Gizle)" else "Müşteri Moduna Geç",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Dismiss / Unpin Button (Click to unpin and resume scanning)
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onUnpin()
                        },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF00F5D4),
                            contentColor = Color(0xFF00382E)
                        ),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("unpin_overlay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Sabitlemeyi kaldır ve taramaya dön",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Full-width card content according to customerPriceOnly or current display mode
            if (config.customerPriceOnly) {
                CustomerPriceOnlyCardContent(
                    result = tracked.result,
                    config = config.copy(scale = 1.0f),
                    attachment = tracked.attachment,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                when (config.displayMode) {
                    OverlayDisplayMode.EXCEL_TABLE -> {
                        ExcelTableOverlayContent(
                            result = tracked.result,
                            config = config.copy(scale = 1.0f),
                            attachment = tracked.attachment,
                            variableDefs = variableDefs,
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
                                variableDefs = variableDefs,
                                onAttachmentClick = onAttachmentClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Bluetooth Keystroke Action to Computer
            if (onSendToComputer != null) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSendToComputer.invoke(null, null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00F5D4),
                        contentColor = Color(0xFF00382E)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("send_to_computer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bilgisayara Tuşla / Gönder ⌨️",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // 1-Tap Copy & Share Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Copy Result Button
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboardManager.setText(AnnotatedString(resultText))
                        Toast.makeText(context, "Sonuç kopyalandı: $resultText", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF0E222E),
                        contentColor = Color(0xFF00F5D4)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("copy_result_button"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Sonucu Kopyala",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Copy Raw Barcode Button (Hidden when customerPriceOnly so customer doesn't see cost barcode)
                if (!config.customerPriceOnly) {
                    FilledTonalButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            clipboardManager.setText(AnnotatedString(tracked.rawContent))
                            Toast.makeText(context, "Ham barkod kopyalandı", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF0E222E),
                            contentColor = Color(0xFF38BDF8)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("copy_raw_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DataObject,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Ham Veri",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Share Button
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val shareContent = if (config.customerPriceOnly) {
                            buildString {
                                tracked.attachment?.title?.takeIf { it.isNotBlank() }?.let {
                                    appendLine(it)
                                }
                                appendLine("Satış Fiyatı: $resultText")
                            }
                        } else {
                            val varsBreakdown = tracked.result.extractedVariables.entries.joinToString("\n") { (k, v) -> "• $k: $v" }
                            buildString {
                                appendLine("📊 QR Barkod Canlı Hesaplama Özeti")
                                appendLine("Format: ${tracked.result.formatName}")
                                appendLine("Ham Barkod Verisi: ${tracked.rawContent}")
                                if (varsBreakdown.isNotBlank()) {
                                    appendLine("\nDeğişkenler:\n$varsBreakdown")
                                }
                                appendLine("\nHesaplanan Nihai Sonuç: $resultText")
                            }
                        }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareContent)
                            putExtra(Intent.EXTRA_SUBJECT, "ALVETAK QR - $resultText")
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(shareIntent, "Sonucu Paylaş"))
                        }.onFailure { err ->
                            AppDebugLogger.e("OVERLAY", "Sonuç paylaşım penceresi açılamadı", err)
                            Toast.makeText(context, "Paylaşım uygulaması bulunamadı", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF0E222E),
                        contentColor = Color(0xFFF59E0B)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("share_result_button"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Paylaş",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Unpin Button to resume scanning (or swipe down)
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onUnpin()
                },
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
                    text = "Sabitlemeyi Kaldır ve Taramaya Dön (Aşağı Kaydır)",
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
            fontFamily = AppMonoFontFamily,
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
    variableDefs: List<VariableDefinition> = emptyList(),
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
            variableDefs = variableDefs,
            onAttachmentClick = onAttachmentClick
        )
    }
}

/**
 * Main overlay router: dispatches between Customer-Price-Only, Mini Excel Table, Compact Card, or Hybrid mode.
 */
@Composable
fun OverlayCardContent(
    result: ParsedQrResult,
    config: OverlayAppearance,
    attachment: QrAttachment? = null,
    variableDefs: List<VariableDefinition> = emptyList(),
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (config.customerPriceOnly) {
        CustomerPriceOnlyCardContent(
            result = result,
            config = config,
            attachment = attachment,
            modifier = modifier
        )
        return
    }

    when (config.displayMode) {
        OverlayDisplayMode.EXCEL_TABLE -> {
            ExcelTableOverlayContent(
                result = result,
                config = config,
                attachment = attachment,
                variableDefs = variableDefs,
                onAttachmentClick = onAttachmentClick,
                modifier = modifier
            )
        }
        OverlayDisplayMode.COMPACT_CARD -> {
            CompactCardOverlayContent(
                result = result,
                config = config,
                attachment = attachment,
                variableDefs = variableDefs,
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
                    variableDefs = variableDefs,
                    onAttachmentClick = onAttachmentClick
                )
                ExcelTableOverlayContent(
                    result = result,
                    config = config,
                    attachment = attachment,
                    variableDefs = variableDefs,
                    onAttachmentClick = onAttachmentClick
                )
            }
        }
    }
}

/**
 * Clean Customer-Facing Display Card ("Müşteri Ekranı"):
 * Shows ONLY the final calculated selling price (and optional public title),
 * completely hiding wholesale cost, barcode date, inflation rate, and raw QR tokens.
 */
@Composable
fun CustomerPriceOnlyCardContent(
    result: ParsedQrResult,
    config: OverlayAppearance,
    attachment: QrAttachment? = null,
    modifier: Modifier = Modifier
) {
    val shape = overlayContainerShape(config)
    val accentColor = parseHexColor(config.accentColorHex.ifBlank { config.borderColorHex })
    val borderColor = parseHexColor(config.borderColorHex)
    val textColor = parseHexColor(config.textColorHex)
    val primaryBg = parseHexColor(config.backgroundColorHex)
    val secondaryBg = parseHexColor(config.gradientColorEndHex)
    val fontFamily = resolveOverlayFontFamily(config.fontFamily)
    val fontWeight = resolveOverlayFontWeight(config.fontWeightLevel, config.isBold)
    val textShadow = overlayTextShadow(config)
    val isLightBg = primaryBg.luminance() > 0.5f && config.fillMode != OverlayFillMode.OUTLINE_ONLY
    val titleColor = if (config.labelColorHex.isNotBlank()) parseHexColor(config.labelColorHex)
        else if (isLightBg) Color(0xFF334155) else Color.White

    val widthModifier = if (config.autoWidth) Modifier.widthIn(min = 170.dp, max = 330.dp)
        else Modifier.width(config.customWidth.coerceIn(120f, 360f).dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = config.scale
                scaleY = config.scale
            }
            .overlayFrameModifiers(
                config = config,
                shape = shape,
                borderColor = borderColor,
                primaryBg = primaryBg,
                secondaryBg = secondaryBg
            )
            .clip(shape)
            .then(widthModifier)
            .padding(horizontal = (config.paddingDp + 6f).coerceIn(8f, 32f).dp, vertical = (config.paddingDp + 2f).coerceIn(6f, 28f).dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (config.textAlignCenter) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            if (attachment != null && attachment.title.isNotBlank()) {
                Text(
                    text = attachment.title,
                    color = titleColor,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = accentColor.copy(alpha = 0.16f)
            ) {
                Text(
                    text = "SONUÇ",
                    color = accentColor,
                    fontSize = 10.sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            when (val status = result.status) {
                is CalculationStatus.Success -> {
                    Text(
                        text = status.formattedText,
                        color = textColor,
                        style = TextStyle(
                            fontSize = (config.fontSizeSp * 1.25f).coerceIn(18f, 44f).sp,
                            fontFamily = fontFamily,
                            fontWeight = fontWeight,
                            fontStyle = if (config.isItalic) FontStyle.Italic else FontStyle.Normal,
                            letterSpacing = config.letterSpacingSp.sp,
                            textAlign = if (config.textAlignCenter) TextAlign.Center else TextAlign.Start,
                            shadow = textShadow
                        )
                    )
                }
                is CalculationStatus.ParseError -> {
                    Text(
                        text = "Okunamadı",
                        color = Color(0xFFFCA5A5),
                        fontSize = 14.sp,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                is CalculationStatus.EvaluationError -> {
                    Text(
                        text = "Hesaplanamadı",
                        color = Color(0xFFFDE68A),
                        fontSize = 14.sp,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

fun overlayContainerShape(config: OverlayAppearance): Shape {
    val radius = config.cornerRadiusDp.coerceAtLeast(0f).dp
    return when (config.cornerStyle) {
        OverlayCornerStyle.ROUNDED -> RoundedCornerShape(radius)
        OverlayCornerStyle.CUT_CORNER -> CutCornerShape(radius)
    }
}

fun overlayTextShadow(config: OverlayAppearance): Shadow? {
    if (!config.showTextShadow) return null
    val shadowColor = parseHexColor(config.textShadowColorHex).copy(alpha = 0.72f)
    return Shadow(
        color = shadowColor,
        offset = Offset(1.5f, 2f),
        blurRadius = 4f
    )
}

fun overlayBackgroundBrush(
    config: OverlayAppearance,
    primaryBg: Color,
    secondaryBg: Color
): Brush {
    val alpha = config.backgroundOpacity.coerceIn(0f, 1f)
    return when (config.fillMode) {
        OverlayFillMode.FILLED_SOLID -> SolidColor(primaryBg.copy(alpha = alpha))
        OverlayFillMode.SEMI_TRANSPARENT -> SolidColor(primaryBg.copy(alpha = (alpha * 0.76f).coerceIn(0.12f, 0.90f)))
        OverlayFillMode.OUTLINE_ONLY -> SolidColor(Color.Transparent)
        OverlayFillMode.FILLED_GRADIENT -> {
            val c1 = primaryBg.copy(alpha = alpha)
            val c2 = secondaryBg.copy(alpha = alpha)
            when (config.gradientAngleDeg.roundToInt()) {
                0 -> Brush.horizontalGradient(listOf(c1, c2))
                90 -> Brush.verticalGradient(listOf(c1, c2))
                45 -> Brush.linearGradient(
                    colors = listOf(c1, c2),
                    start = Offset(0f, Float.POSITIVE_INFINITY),
                    end = Offset(Float.POSITIVE_INFINITY, 0f)
                )
                else -> Brush.linearGradient(
                    colors = listOf(c1, c2),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            }
        }
    }
}

fun Modifier.overlayFrameModifiers(
    config: OverlayAppearance,
    shape: Shape,
    borderColor: Color,
    primaryBg: Color,
    secondaryBg: Color
): Modifier {
    val elevation = if (config.showDropShadow) config.shadowElevationDp.coerceIn(0f, 28f).dp else 0.dp
    val shadowMod = if (elevation > 0.dp) {
        val shadowTint = if (config.showGlow) borderColor else Color.Black
        Modifier.shadow(elevation = elevation, shape = shape, ambientColor = shadowTint, spotColor = shadowTint)
    } else Modifier

    val glowMod = if (config.showGlow) {
        Modifier.drawBehind {
            val glowOutline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = glowOutline,
                color = borderColor.copy(alpha = 0.24f),
                style = Stroke(width = (config.borderWidthDp + 4.5f).dp.toPx())
            )
        }
    } else Modifier

    val bgBrush = overlayBackgroundBrush(config, primaryBg, secondaryBg)

    val borderMod = if (config.showBorder && config.borderWidthDp > 0f) {
        Modifier.drawWithContent {
            drawContent()
            val strokePx = config.borderWidthDp.dp.toPx()
            val outerOutline = shape.createOutline(size, layoutDirection, this)
            when (config.borderStyle) {
                BorderStyle.SOLID -> {
                    drawOutline(
                        outline = outerOutline,
                        color = borderColor,
                        style = Stroke(width = strokePx)
                    )
                }
                BorderStyle.DASHED -> {
                    drawOutline(
                        outline = outerOutline,
                        color = borderColor,
                        style = Stroke(
                            width = strokePx,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(strokePx * 4.5f, strokePx * 3f), 0f)
                        )
                    )
                }
                BorderStyle.DOTTED -> {
                    drawOutline(
                        outline = outerOutline,
                        color = borderColor,
                        style = Stroke(
                            width = strokePx,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(strokePx * 0.6f, strokePx * 2.8f), 0f)
                        )
                    )
                }
                BorderStyle.DOUBLE -> {
                    val thin = (strokePx * 0.55f).coerceAtLeast(1.2f)
                    drawOutline(
                        outline = outerOutline,
                        color = borderColor,
                        style = Stroke(width = thin)
                    )
                    val gap = thin * 2.2f
                    if (size.width > gap * 2f && size.height > gap * 2f) {
                        inset(gap) {
                            val innerOutline = shape.createOutline(size, layoutDirection, this)
                            drawOutline(
                                outline = innerOutline,
                                color = borderColor.copy(alpha = 0.72f),
                                style = Stroke(width = thin)
                            )
                        }
                    }
                }
            }
        }
    } else Modifier

    return this
        .then(shadowMod)
        .then(glowMod)
        .background(bgBrush, shape)
        .then(borderMod)
}

/**
 * Resolves effective table palette: respects custom Style Builder colors when enabled,
 * and honors opacity/striping/explicit color overrides even when starting from a theme.
 */
fun resolveEffectiveExcelColors(config: OverlayAppearance): ExcelThemeColors {
    val base = getExcelThemeColors(config.tableConfig.gridTheme)
    val stripeAlpha = config.rowStripeOpacity.coerceIn(0f, 0.45f)

    if (!config.useCustomTableColors) {
        val accent = if (config.accentColorHex.isNotBlank()) parseHexColor(config.accentColorHex) else base.accent
        val label = if (config.labelColorHex.isNotBlank()) parseHexColor(config.labelColorHex) else base.textSecondary
        val headerStart = if (config.headerBgColorHex.isNotBlank()) parseHexColor(config.headerBgColorHex) else base.headerBgStart
        val headerEnd = if (config.headerBgColorHex.isNotBlank()) headerStart.copy(alpha = 0.85f) else base.headerBgEnd
        return base.copy(
            accent = accent,
            headerBgStart = headerStart,
            headerBgEnd = headerEnd,
            containerBg = base.containerBg.copy(alpha = config.backgroundOpacity.coerceIn(0f, 1f)),
            rowBgOdd = accent.copy(alpha = stripeAlpha),
            textSecondary = label
        )
    }

    val bg = parseHexColor(config.backgroundColorHex)
    val bgEnd = parseHexColor(config.gradientColorEndHex)
    val textPrimary = parseHexColor(config.textColorHex)
    val borderCol = parseHexColor(config.borderColorHex)
    val accent = if (config.accentColorHex.isNotBlank()) parseHexColor(config.accentColorHex) else borderCol
    val isLightBg = bg.luminance() > 0.52f && config.fillMode != OverlayFillMode.OUTLINE_ONLY
    val textSecondary = if (config.labelColorHex.isNotBlank()) {
        parseHexColor(config.labelColorHex)
    } else if (isLightBg) {
        Color(0xFF475569)
    } else {
        textPrimary.copy(alpha = 0.72f)
    }

    val headerStart = if (config.headerBgColorHex.isNotBlank()) {
        parseHexColor(config.headerBgColorHex)
    } else if (isLightBg) {
        accent.copy(alpha = 0.16f)
    } else {
        bgEnd.copy(alpha = 0.95f)
    }
    val headerEnd = if (config.headerBgColorHex.isNotBlank()) {
        headerStart.copy(alpha = 0.78f)
    } else if (isLightBg) {
        accent.copy(alpha = 0.08f)
    } else {
        bg.copy(alpha = 0.95f)
    }

    val emphasizedText = if (accent.luminance() > 0.55f) Color(0xFF090D16) else Color.White

    return ExcelThemeColors(
        accent = accent,
        headerBgStart = headerStart,
        headerBgEnd = headerEnd,
        containerBg = bg.copy(alpha = config.backgroundOpacity.coerceIn(0f, 1f)),
        gridBorder = borderCol.copy(alpha = if (isLightBg) 0.45f else 0.35f),
        rowBgEven = Color.Transparent,
        rowBgOdd = accent.copy(alpha = stripeAlpha),
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        coordinateBg = accent.copy(alpha = if (isLightBg) 0.14f else 0.22f),
        coordinateText = if (isLightBg) textPrimary else accent,
        emphasizedBg = Brush.horizontalGradient(listOf(accent.copy(alpha = 0.88f), accent.copy(alpha = 0.68f))),
        emphasizedBorder = accent,
        emphasizedText = emphasizedText
    )
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
        // Respect user's showPill visibility toggle on individual variables
        tableConfig.items.filter { item ->
            if (item.source == TableItemSource.VARIABLE || item.source == TableItemSource.COMPUTED) {
                val linkedVar = variableDefs.find { it.name == item.variableName || (it.alias.isNotBlank() && it.alias == item.variableName) }
                linkedVar?.showPill ?: true
            } else {
                true
            }
        }
    } else if (variableDefs.isNotEmpty()) {
        // Pure variable-centric auto table: render strictly what the user chose to show, in user order
        val userRows = variableDefs.filter { it.showPill }.mapIndexed { idx, v ->
            val displayLabel = v.alias.takeIf { it.isNotBlank() }
                ?: v.name.replaceFirstChar { it.uppercase() }
            ExcelTableCellItem(
                source = if (v.isComputed) TableItemSource.COMPUTED else TableItemSource.VARIABLE,
                variableName = v.name,
                label = displayLabel,
                prefix = v.prefix,
                postfix = v.postfix,
                coordinate = "A${idx + 1}",
                isVisible = true,
                isEmphasized = v.isPrimaryOutput || v.isComputed
            )
        }.toMutableList()
        // Backward compatibility: if a legacy saved profile still uses a standalone profile.formula
        // and has no user-computed/starred primary variable, append its formula output row.
        val hasPrimaryOrComputed = variableDefs.any { it.isPrimaryOutput || it.isComputed }
        if (result.formulaExpression.isNotBlank() && !hasPrimaryOrComputed) {
            userRows.add(
                ExcelTableCellItem(
                    source = TableItemSource.FORMULA_RESULT,
                    label = "TOPLAM",
                    coordinate = "A${userRows.size + 1}",
                    isVisible = true,
                    isEmphasized = true
                )
            )
        }
        userRows
    } else {
        // Fallback when no variable definitions were supplied (e.g. unit preview)
        val autoList = mutableListOf<ExcelTableCellItem>()
        var coordIdx = 1
        result.extractedVariables.forEach { (name, _) ->
            if (!name.contains("_alias") && name !in setOf("bugun", "today")) {
                val displayLabel = name.replaceFirstChar { it.uppercase() }
                autoList.add(
                    ExcelTableCellItem(
                        source = TableItemSource.VARIABLE,
                        variableName = name,
                        label = displayLabel,
                        coordinate = "A$coordIdx",
                        isVisible = true,
                        isEmphasized = false
                    )
                )
                coordIdx++
            }
        }
        if (result.formulaExpression.isNotBlank() || result.status is CalculationStatus.Success) {
            autoList.add(
                ExcelTableCellItem(
                    source = TableItemSource.FORMULA_RESULT,
                    label = "SONUÇ",
                    coordinate = "A$coordIdx",
                    isVisible = true,
                    isEmphasized = true
                )
            )
        }
        autoList
    }

    val baseRows = itemsToUse.filter { it.isVisible }.mapIndexed { index, item ->
        val resolvedValue = when (item.source) {
            TableItemSource.VARIABLE, TableItemSource.COMPUTED -> {
                val varDef = variableDefs.find { it.name == item.variableName || (it.alias.isNotBlank() && it.alias == item.variableName) }
                val strVal = result.extractedStrings[item.variableName]
                val numVal = result.extractedVariables[item.variableName]
                val isTextualType = varDef?.dataType == FieldDataType.DATE ||
                    varDef?.dataType == FieldDataType.STRING ||
                    varDef?.dataType == FieldDataType.BOOLEAN

                if (isTextualType && strVal != null) {
                    val effectivePrefix = if (item.prefix.isNotEmpty()) item.prefix else (varDef?.prefix ?: "")
                    val effectivePostfix = if (item.postfix.isNotEmpty()) item.postfix else (varDef?.postfix ?: "")
                    "$effectivePrefix$strVal$effectivePostfix"
                } else if (numVal != null) {
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
                } else if (strVal != null) {
                    val effectivePrefix = if (item.prefix.isNotEmpty()) item.prefix else (varDef?.prefix ?: "")
                    val effectivePostfix = if (item.postfix.isNotEmpty()) item.postfix else (varDef?.postfix ?: "")
                    "$effectivePrefix$strVal$effectivePostfix"
                } else if (item.customValue.isNotBlank()) {
                    "${item.prefix}${item.customValue}${item.postfix}"
                } else {
                    "-"
                }
            }
            TableItemSource.FORMULA_RESULT -> {
                when (val status = result.status) {
                    is CalculationStatus.Success -> {
                        val baseText = status.formattedText
                        val baseTrim = baseText.trim()
                        val pfx = item.prefix
                        val sfx = item.postfix
                        val pfxTrim = pfx.trim()
                        val sfxTrim = sfx.trim()
                        val hasPrefix = pfxTrim.isNotEmpty() && (
                            baseTrim.startsWith(pfxTrim) ||
                                baseTrim.startsWith("-$pfxTrim") ||
                                baseTrim.startsWith("($pfxTrim")
                            )
                        val hasPostfix = sfxTrim.isNotEmpty() && (
                            baseTrim.endsWith(sfxTrim) ||
                                baseTrim.endsWith("$sfxTrim)")
                            )
                        val outPrefix = if (hasPrefix) "" else pfx
                        val outPostfix = if (hasPostfix) "" else sfx
                        "$outPrefix$baseText$outPostfix"
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
    variableDefs: List<VariableDefinition> = emptyList(),
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tableConfig = config.tableConfig
    val themeColors = resolveEffectiveExcelColors(config)
    val shape = overlayContainerShape(config)
    val primaryFont = resolveOverlayFontFamily(config.fontFamily)
    val valueFont = if (config.useMonoForValues) AppMonoFontFamily else primaryFont
    val baseWeight = resolveOverlayFontWeight(config.fontWeightLevel, config.isBold)
    val fontStyle = if (config.isItalic) FontStyle.Italic else FontStyle.Normal
    val textShadow = overlayTextShadow(config)
    val fontScale = (config.fontSizeSp / 16f).coerceIn(0.75f, 1.85f)
    val headerTitleSp = (11f * fontScale).coerceIn(9f, 22f).sp
    val headerSubSp = (9f * fontScale).coerceIn(8f, 16f).sp
    val colHeaderSp = (9f * fontScale).coerceIn(8f, 15f).sp
    val rowLabelSp = (11f * fontScale).coerceIn(9f, 22f).sp
    val rowLabelEmphSp = (12f * fontScale).coerceIn(10f, 24f).sp
    val rowValSp = (11.5f * fontScale).coerceIn(9f, 24f).sp
    val rowValEmphSp = (13.5f * fontScale).coerceIn(11f, 26f).sp
    val rowVertPad = config.rowPaddingDp.coerceIn(2f, 14f).dp
    val innerCorner = (config.cornerRadiusDp * 0.45f).coerceIn(2f, 12f).dp

    val primaryBg = if (config.useCustomTableColors) parseHexColor(config.backgroundColorHex) else themeColors.containerBg
    val secondaryBg = if (config.useCustomTableColors) parseHexColor(config.gradientColorEndHex) else themeColors.headerBgEnd
    val borderColor = if (config.useCustomTableColors) parseHexColor(config.borderColorHex) else themeColors.gridBorder
    val isLightHeader = themeColors.headerBgStart.luminance() > 0.55f
    val headerTitleColor = if (isLightHeader) Color(0xFF0F172A) else Color.White

    val widthModifier = if (config.autoWidth) {
        Modifier.widthIn(min = 200.dp, max = 340.dp)
    } else {
        Modifier.width(config.customWidth.coerceIn(140f, 360f).dp)
    }
    val heightModifier = if (config.autoHeight) {
        Modifier
    } else {
        Modifier.heightIn(min = config.customHeight.coerceIn(70f, 360f).dp)
    }

    val rows = resolveTableRows(
        tableConfig = tableConfig,
        result = result,
        variableDefs = variableDefs,
        attachment = attachment
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = config.scale
                scaleY = config.scale
            }
            .overlayFrameModifiers(
                config = config,
                shape = shape,
                borderColor = borderColor,
                primaryBg = primaryBg,
                secondaryBg = secondaryBg
            )
            .clip(shape)
            .then(widthModifier)
            .then(heightModifier)
            .padding(config.paddingDp.coerceIn(2f, 24f).dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Attached Media Banner / Thumbnail
            if (attachment != null && attachment.showInOverlay) {
                Surface(
                    shape = RoundedCornerShape(innerCorner),
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
                                fontFamily = primaryFont,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (attachment.allVideos.isNotEmpty()) {
                                Text(
                                    text = if (attachment.allVideos.size > 1) "▶ ${attachment.allVideos.size} Videos Attached" else "▶ Video Attached",
                                    color = themeColors.accent,
                                    fontSize = 8.sp,
                                    fontFamily = primaryFont,
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
                        .clip(RoundedCornerShape(innerCorner))
                        .background(
                            Brush.horizontalGradient(
                                listOf(themeColors.headerBgStart, themeColors.headerBgEnd)
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val headerIcon = getIconForName(config.iconName) ?: Icons.Default.TableChart
                        if (config.iconName != "none") {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(themeColors.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = headerIcon,
                                    contentDescription = null,
                                    tint = if (themeColors.accent.luminance() > 0.55f) Color(0xFF090D16) else Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tableConfig.headerTitle.ifBlank { "VERİ TABLOSU" }.uppercase(),
                                color = headerTitleColor,
                                style = TextStyle(
                                    fontSize = headerTitleSp,
                                    fontFamily = primaryFont,
                                    fontWeight = FontWeight.Bold,
                                    fontStyle = fontStyle,
                                    letterSpacing = (0.5f + config.letterSpacingSp * 0.4f).sp,
                                    shadow = textShadow
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (tableConfig.headerSubtitle.isNotBlank()) {
                                Text(
                                    text = tableConfig.headerSubtitle,
                                    color = themeColors.textSecondary,
                                    fontSize = headerSubSp,
                                    fontFamily = primaryFont,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Barcode Format Tag Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(themeColors.accent.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = result.formatName,
                            color = themeColors.accent,
                            fontSize = 8.5.sp,
                            fontFamily = valueFont,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 2. Column Headers Row (if enabled)
            if (tableConfig.showColumnHeaders && rows.isNotEmpty()) {
                val colHeaderBg = if (primaryBg.luminance() > 0.52f && config.fillMode != OverlayFillMode.OUTLINE_ONLY) {
                    Color.Black.copy(alpha = 0.08f)
                } else {
                    Color.Black.copy(alpha = 0.32f)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(colHeaderBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (tableConfig.showCoordinates) {
                        Text(
                            text = "#",
                            color = themeColors.textSecondary.copy(alpha = 0.85f),
                            fontSize = colHeaderSp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = valueFont,
                            modifier = Modifier.width(28.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = "ALAN",
                        color = themeColors.textSecondary.copy(alpha = 0.85f),
                        fontSize = colHeaderSp,
                        fontFamily = primaryFont,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1.1f)
                    )
                    Text(
                        text = "DEĞER",
                        color = themeColors.textSecondary.copy(alpha = 0.85f),
                        fontSize = colHeaderSp,
                        fontFamily = primaryFont,
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
                    .clip(RoundedCornerShape(innerCorner))
                    .border(
                        if (tableConfig.showGridLines) 0.5.dp else 0.dp,
                        themeColors.gridBorder.copy(alpha = 0.35f),
                        RoundedCornerShape(innerCorner)
                    )
            ) {
                rows.forEachIndexed { index, (item, resolvedVal) ->
                    val isEven = index % 2 == 0
                    val rowBg = if (item.isEmphasized) {
                        themeColors.emphasizedBg
                    } else if (isEven) {
                        SolidColor(themeColors.rowBgEven)
                    } else {
                        SolidColor(themeColors.rowBgOdd)
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
                            .padding(
                                horizontal = 8.dp,
                                vertical = if (item.isEmphasized) rowVertPad + 2.dp else rowVertPad
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Cell Coordinate (A1, A2...)
                        if (tableConfig.showCoordinates) {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (item.isEmphasized) Color.Black.copy(alpha = 0.25f) else themeColors.coordinateBg
                                    )
                                    .padding(vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.coordinate.ifBlank { "A${index + 1}" },
                                    color = if (item.isEmphasized) themeColors.emphasizedText else themeColors.coordinateText,
                                    fontSize = 8.sp,
                                    fontFamily = valueFont,
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
                                    contentDescription = "Öne Çıkan",
                                    tint = Color(0xFFFBBF24),
                                    modifier = Modifier
                                        .size(11.dp)
                                        .padding(end = 3.dp)
                                )
                            }
                            Text(
                                text = item.label.ifBlank { item.variableName.ifBlank { "Alan" } },
                                color = if (item.isEmphasized) themeColors.emphasizedText else themeColors.textPrimary,
                                style = TextStyle(
                                    fontSize = if (item.isEmphasized) rowLabelEmphSp else rowLabelSp,
                                    fontFamily = primaryFont,
                                    fontWeight = if (item.isEmphasized) FontWeight.Bold else baseWeight,
                                    fontStyle = fontStyle,
                                    letterSpacing = (config.letterSpacingSp * 0.4f).sp,
                                    shadow = textShadow
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Formatted Cell Value
                        Text(
                            text = resolvedVal,
                            color = if (item.isEmphasized) themeColors.emphasizedText else themeColors.accent,
                            style = TextStyle(
                                fontSize = if (item.isEmphasized) rowValEmphSp else rowValSp,
                                fontFamily = valueFont,
                                fontWeight = if (item.isEmphasized) FontWeight.ExtraBold else baseWeight,
                                fontStyle = fontStyle,
                                letterSpacing = (config.letterSpacingSp * 0.5f).sp,
                                textAlign = TextAlign.End,
                                shadow = textShadow
                            ),
                            modifier = Modifier.weight(1.2f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
    variableDefs: List<VariableDefinition> = emptyList(),
    onAttachmentClick: ((QrAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val shape = overlayContainerShape(config)
    val textColor = parseHexColor(config.textColorHex)
    val primaryBg = parseHexColor(config.backgroundColorHex)
    val secondaryBg = parseHexColor(config.gradientColorEndHex)
    val borderColor = parseHexColor(config.borderColorHex)
    val accentColor = parseHexColor(config.accentColorHex.ifBlank { config.borderColorHex })
    val primaryFont = resolveOverlayFontFamily(config.fontFamily)
    val fontWeight = resolveOverlayFontWeight(config.fontWeightLevel, config.isBold)
    val fontStyle = if (config.isItalic) FontStyle.Italic else FontStyle.Normal
    val textShadow = overlayTextShadow(config)

    val widthModifier = if (config.autoWidth) Modifier.widthIn(min = 140.dp, max = 340.dp) else Modifier.width(config.customWidth.coerceIn(120f, 360f).dp)
    val heightModifier = if (config.autoHeight) Modifier.heightIn(min = 50.dp) else Modifier.height(config.customHeight.coerceIn(50f, 320f).dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = config.scale
                scaleY = config.scale
            }
            .overlayFrameModifiers(
                config = config,
                shape = shape,
                borderColor = borderColor,
                primaryBg = primaryBg,
                secondaryBg = secondaryBg
            )
            .clip(shape)
            .then(widthModifier)
            .then(heightModifier)
            .padding(config.paddingDp.coerceIn(2f, 24f).dp)
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
                                fontFamily = primaryFont,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (attachment.allVideos.isNotEmpty()) {
                                Text(
                                    text = if (attachment.allVideos.size > 1) "▶ ${attachment.allVideos.size} Videos Attached" else "▶ Video Attached",
                                    color = accentColor,
                                    fontSize = 8.sp,
                                    fontFamily = primaryFont,
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
                        tint = accentColor,
                        modifier = Modifier
                            .size((config.fontSizeSp * 1.1f).coerceIn(14f, 36f).dp)
                            .padding(end = 6.dp)
                    )
                }

                when (val status = result.status) {
                    is CalculationStatus.Success -> {
                        Text(
                            text = status.formattedText,
                            color = textColor,
                            style = TextStyle(
                                fontSize = config.fontSizeSp.sp,
                                fontFamily = primaryFont,
                                fontWeight = fontWeight,
                                fontStyle = fontStyle,
                                textAlign = if (config.textAlignCenter) TextAlign.Center else TextAlign.Start,
                                letterSpacing = config.letterSpacingSp.sp,
                                shadow = textShadow
                            )
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

            // Individual Pills Row (respecting user's showPill toggles across ALL data types)
            val visiblePills: List<Pair<String, String>> = if (config.showVariableSummaryPill) {
                if (variableDefs.isNotEmpty()) {
                    variableDefs
                        .filter { it.showPill && it.name !in config.hiddenPillVariables }
                        .mapNotNull { v ->
                            val display = result.variablePillDisplay[v.name]
                                ?: result.extractedStrings[v.name]?.let { "${v.prefix}$it${v.postfix}" }
                                ?: result.extractedVariables[v.name]?.let { num ->
                                    if (num % 1.0 == 0.0) num.toLong().toString() else "%.2f".format(num)
                                }
                            display?.let { (v.alias.ifBlank { v.name }) to it }
                        }
                } else {
                    result.extractedVariables.entries
                        .filter { (name, _) ->
                            name !in config.hiddenPillVariables &&
                                !name.contains("_alias") &&
                                name !in setOf("bugun", "today")
                        }
                        .map { (name, value) ->
                            val formattedVal = result.variablePillDisplay[name]
                                ?: if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
                            name to formattedVal
                        }
                }
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
                    visiblePills.forEach { (label, formattedVal) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .border(0.5.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$label: $formattedVal",
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
    } catch (e: Exception) {
        AppDebugLogger.w("UI-CLR", "Geçersiz renk kodu: '$hex'", e)
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
