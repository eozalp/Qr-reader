package com.example.ui.settings

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.OverlayCardContent
import com.example.ui.parseHexColor

@Composable
fun OverlayAppearanceSection(
    overlay: OverlayAppearance,
    definedVariables: List<VariableDefinition> = emptyList(),
    onOverlayChanged: (OverlayAppearance) -> Unit,
    modifier: Modifier = Modifier
) {
    // Mock result for live preview
    val sampleResult = remember {
        ParsedQrResult(
            rawText = "10-20-30-40",
            extractedVariables = mapOf("a" to 10.0, "b" to 20.0, "c" to 30.0, "d" to 40.0),
            status = CalculationStatus.Success(100.0, "₺100.00 TL"),
            formulaExpression = "a + b + c + d"
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live Real-Time Interactive Preview
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0x3300F5D4), RoundedCornerShape(20.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "LIVE OVERLAY & LEADER LINE PREVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF020617)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Render mini demo barcode and bonded leader line when callout mode is enabled
                        if (overlay.calloutMode != CalloutLayoutMode.DIRECT_FOLLOW) {
                            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                val strokeCol = parseHexColor(overlay.borderColorHex).copy(alpha = 0.8f)

                                // Demo QR barcode dashed box on left
                                val qrW = 44.dp.toPx()
                                val qrH = 44.dp.toPx()
                                val qrLeft = 18.dp.toPx()
                                val qrTop = (size.height - qrH) / 2f
                                val qrRect = RectF(qrLeft, qrTop, qrLeft + qrW, qrTop + qrH)

                                if (overlay.showBarcodeDashedOutline) {
                                    drawRoundRect(
                                        color = strokeCol,
                                        topLeft = Offset(qrRect.left, qrRect.top),
                                        size = Size(qrRect.width(), qrRect.height()),
                                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash)
                                    )
                                }

                                val barcAnchor = Offset(qrRect.right, qrRect.centerY())
                                val cardAnchor = Offset(size.width * 0.42f, size.height / 2f)

                                // Anchor pin dot
                                if (overlay.showAnchorDot) {
                                    drawCircle(color = strokeCol.copy(alpha = 0.3f), radius = 5.dp.toPx(), center = barcAnchor)
                                    drawCircle(color = strokeCol, radius = 2.5.dp.toPx(), center = barcAnchor)
                                }

                                // Bonded leader line
                                val lineEffect = if (overlay.leaderLineStyle == LeaderLineStyle.DASHED) dash else null
                                drawLine(
                                    color = strokeCol,
                                    start = barcAnchor,
                                    end = cardAnchor,
                                    strokeWidth = overlay.leaderLineWidthDp.dp.toPx(),
                                    pathEffect = lineEffect
                                )
                                drawCircle(color = strokeCol, radius = 2.dp.toPx(), center = cardAnchor)
                            }
                        }

                        OverlayCardContent(
                            result = sampleResult,
                            config = overlay
                        )
                    }
                }
            }
        }

        // Overlay Display Mode (Excel Table vs Compact Card vs Hybrid)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Overlay Display Mode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF107C41))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("SPREADSHEET", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Choose between the Mini Excel Cells Grid, Classic Card with pills, or Hybrid display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OverlayDisplayMode.entries.forEach { mode ->
                            val isSelected = overlay.displayMode == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = { onOverlayChanged(overlay.copy(displayMode = mode)) },
                                label = {
                                    Text(
                                        when (mode) {
                                            OverlayDisplayMode.EXCEL_TABLE -> "Mini Excel Grid"
                                            OverlayDisplayMode.COMPACT_CARD -> "Compact Card"
                                            OverlayDisplayMode.HYBRID -> "Hybrid (Both)"
                                        }
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }
                }
            }
        }

        // Müşteri Fiyat Ekranı Modu (Sadece Nihai Fiyatı Göster / Maliyeti Gizle)
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (overlay.customerPriceOnly) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
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
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Müşteri Fiyat Ekranı Modu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Açıkken ekranda sadece hesaplanan nihai satış fiyatı gösterilir; maliyet, alış tarihi, enflasyon oranı ve ham barkod müşteriden gizlenir.",
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
        }

        // Multi-QR Callout Layout & Bonded Leader Lines (Anti-Overlap)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("callout_layout_settings_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Callout Layout & Bonded Lines",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Prevents multi-QR data boxes from overlapping by repelling boxes and bonding them with a thin leader line to the barcode dashed line.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Layout Placement Mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))

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
                                            CalloutLayoutMode.SMART_ANTI_COLLISION -> "Anti-Collision (Bonded)"
                                            CalloutLayoutMode.DOCKED_SIDEBAR -> "Docked Margin Rail"
                                            CalloutLayoutMode.DIRECT_FOLLOW -> "Direct Follow (Attached)"
                                        },
                                        fontSize = 12.sp
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }

                    if (overlay.calloutMode != CalloutLayoutMode.DIRECT_FOLLOW) {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(14.dp))

                        Text("Leader Line Visual Style", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LeaderLineStyle.entries.forEach { style ->
                                val isSelected = overlay.leaderLineStyle == style
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onOverlayChanged(overlay.copy(leaderLineStyle = style)) },
                                    label = {
                                        Text(
                                            when (style) {
                                                LeaderLineStyle.DASHED -> "Technical Dashed"
                                                LeaderLineStyle.THIN_SOLID -> "Thin Glowing Solid"
                                                LeaderLineStyle.ELBOW_JOINT -> "HUD 90° Elbow"
                                            },
                                            fontSize = 12.sp
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Barcode Dashed Outline", style = MaterialTheme.typography.bodyMedium)
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
                            Text("Anchor Pin Dot on Barcode", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = overlay.showAnchorDot,
                                onCheckedChange = { onOverlayChanged(overlay.copy(showAnchorDot = it)) }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Line Thickness: ${"%.1f".format(overlay.leaderLineWidthDp)} dp",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = overlay.leaderLineWidthDp,
                            onValueChange = { onOverlayChanged(overlay.copy(leaderLineWidthDp = it)) },
                            valueRange = 1.0f..4.0f,
                            steps = 5
                        )
                    }
                }
            }
        }

        // Anchor Position
        item {
            Text(text = "Anchor Position Relative to QR", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OverlayAnchorPosition.entries.forEach { anchor ->
                    val label = anchor.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                    FilterChip(
                        selected = overlay.anchorPosition == anchor,
                        onClick = { onOverlayChanged(overlay.copy(anchorPosition = anchor)) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Offsets & Edge Avoidance
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Avoid Screen Edges", fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = overlay.autoAvoidEdges,
                            onCheckedChange = { onOverlayChanged(overlay.copy(autoAvoidEdges = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("X Offset: ${overlay.offsetX.toInt()} dp")
                    Slider(
                        value = overlay.offsetX,
                        onValueChange = { onOverlayChanged(overlay.copy(offsetX = it)) },
                        valueRange = -150f..150f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text("Y Offset: ${overlay.offsetY.toInt()} dp")
                    Slider(
                        value = overlay.offsetY,
                        onValueChange = { onOverlayChanged(overlay.copy(offsetY = it)) },
                        valueRange = -150f..150f
                    )
                }
            }
        }

        // Fill Mode & Background
        item {
            Text(text = "Background & Fill Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OverlayFillMode.entries.forEach { mode ->
                    val label = mode.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                    FilterChip(
                        selected = overlay.fillMode == mode,
                        onClick = { onOverlayChanged(overlay.copy(fillMode = mode)) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Theme Color Palette", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))

            val colorPresets = listOf(
                "#00F5D4" to "Neon Cyan",
                "#10B981" to "Emerald",
                "#3B82F6" to "Electric Blue",
                "#8B5CF6" to "Violet",
                "#EC4899" to "Neon Pink",
                "#F59E0B" to "Amber Gold",
                "#FFFFFF" to "Pure White"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                colorPresets.forEach { (hex, _) ->
                    val color = parseHexColor(hex)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (overlay.textColorHex.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                                color = if (overlay.textColorHex.equals(hex, ignoreCase = true)) Color.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable {
                                onOverlayChanged(
                                    overlay.copy(
                                        textColorHex = hex,
                                        borderColorHex = hex
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (overlay.textColorHex.equals(hex, ignoreCase = true)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = overlay.textColorHex,
                    onValueChange = { onOverlayChanged(overlay.copy(textColorHex = it)) },
                    label = { Text("Text Color Hex") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = overlay.borderColorHex,
                    onValueChange = { onOverlayChanged(overlay.copy(borderColorHex = it)) },
                    label = { Text("Border Color Hex") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(8.dp))

            Text("Overlay Scale / Zoom: ${(overlay.scale * 100).toInt()}% (or pinch-to-zoom on screen)")
            Slider(
                value = overlay.scale,
                onValueChange = { onOverlayChanged(overlay.copy(scale = it)) },
                valueRange = 0.5f..3.0f
            )
        }

        // Typography
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = "Typography", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Font Size: ${overlay.fontSizeSp.toInt()} sp")
                    Slider(
                        value = overlay.fontSizeSp,
                        onValueChange = { onOverlayChanged(overlay.copy(fontSizeSp = it)) },
                        valueRange = 12f..36f
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = overlay.isBold,
                            onClick = { onOverlayChanged(overlay.copy(isBold = !overlay.isBold)) },
                            label = { Text("Bold") }
                        )
                        FilterChip(
                            selected = overlay.isItalic,
                            onClick = { onOverlayChanged(overlay.copy(isItalic = !overlay.isItalic)) },
                            label = { Text("Italic") }
                        )
                        FilterChip(
                            selected = overlay.textAlignCenter,
                            onClick = { onOverlayChanged(overlay.copy(textAlignCenter = !overlay.textAlignCenter)) },
                            label = { Text("Center Align") }
                        )
                    }
                }
            }
        }

        // Border & Shapes
        item {
            Text(text = "Border & Corner Radius", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = overlay.showBorder,
                    onClick = { onOverlayChanged(overlay.copy(showBorder = !overlay.showBorder)) },
                    label = { Text("Show Border") }
                )
                FilterChip(
                    selected = overlay.borderStyle == BorderStyle.SOLID,
                    onClick = { onOverlayChanged(overlay.copy(borderStyle = BorderStyle.SOLID)) },
                    label = { Text("Solid") }
                )
                FilterChip(
                    selected = overlay.borderStyle == BorderStyle.DASHED,
                    onClick = { onOverlayChanged(overlay.copy(borderStyle = BorderStyle.DASHED)) },
                    label = { Text("Dashed") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Corner Radius: ${overlay.cornerRadiusDp.toInt()} dp")
            Slider(
                value = overlay.cornerRadiusDp,
                onValueChange = { onOverlayChanged(overlay.copy(cornerRadiusDp = it)) },
                valueRange = 0f..32f
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text("Border Width: ${overlay.borderWidthDp.toInt()} dp")
            Slider(
                value = overlay.borderWidthDp,
                onValueChange = { onOverlayChanged(overlay.copy(borderWidthDp = it)) },
                valueRange = 1f..6f
            )
        }

        // Icon & Badges
        item {
            Text(text = "Icon & Additional HUD Pills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("calculator", "tag", "qr", "sparkle", "check", "star", "none").forEach { iconKey ->
                    FilterChip(
                        selected = overlay.iconName == iconKey,
                        onClick = { onOverlayChanged(overlay.copy(iconName = iconKey)) },
                        label = { Text(iconKey.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

        }

        // HUD Pills & Individual Visibility
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Individual Pill Visibility",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Customize exactly which badges and variables appear in the AR overlay card.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Formula Pill (= a + b)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = overlay.showCalculationFormulaHint,
                            onCheckedChange = { onOverlayChanged(overlay.copy(showCalculationFormulaHint = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Raw QR Content Pill", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = overlay.showRawQrPill,
                            onCheckedChange = { onOverlayChanged(overlay.copy(showRawQrPill = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Variable Pills (All)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = overlay.showVariableSummaryPill,
                            onCheckedChange = { onOverlayChanged(overlay.copy(showVariableSummaryPill = it)) }
                        )
                    }

                    if (overlay.showVariableSummaryPill) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Toggle Individual Variable Pills:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val varsToDisplay = if (definedVariables.isNotEmpty()) {
                            definedVariables.map { it.name }
                        } else {
                            listOf("a", "b", "c", "d")
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            varsToDisplay.forEach { varName ->
                                val isVisible = varName !in overlay.hiddenPillVariables
                                FilterChip(
                                    selected = isVisible,
                                    onClick = {
                                        val updatedHidden = if (isVisible) {
                                            overlay.hiddenPillVariables + varName
                                        } else {
                                            overlay.hiddenPillVariables.filterNot { it == varName }
                                        }
                                        onOverlayChanged(overlay.copy(hiddenPillVariables = updatedHidden))
                                    },
                                    label = { Text("Pill: $varName") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
