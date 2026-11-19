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

    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + navBottomPadding),
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
                        text = "CANLI OVERLAY & REHBER ÇİZGİ ÖNİZLEME",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF020617))
                            .padding(vertical = 8.dp),
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
                            config = overlay,
                            variableDefs = definedVariables
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
                            text = "Görünüm Modu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF107C41))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("TABLO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Mini Excel tablo, klasik özet kart veya ikisi bir arada hibrit görünüm arasından seçin.",
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
                                            OverlayDisplayMode.EXCEL_TABLE -> "Mini Excel Tablo"
                                            OverlayDisplayMode.COMPACT_CARD -> "Özet Kart"
                                            OverlayDisplayMode.HYBRID -> "Hibrit (İkisi)"
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
                        text = "Çakışma Önleme & Rehber Çizgiler",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Çoklu barkod kutularının üst üste binmesini önler ve her kutuyu ince rehber çizgiyle barkoda bağlar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Yerleşim Modu", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
                                            CalloutLayoutMode.SMART_ANTI_COLLISION -> "Akıllı Çakışma Önleme"
                                            CalloutLayoutMode.DOCKED_SIDEBAR -> "Kenar Şeridi"
                                            CalloutLayoutMode.DIRECT_FOLLOW -> "Doğrudan Takip"
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

                        Text("Rehber Çizgi Stili", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
                                                LeaderLineStyle.DASHED -> "Kesikli Çizgi"
                                                LeaderLineStyle.THIN_SOLID -> "İnce Düz Çizgi"
                                                LeaderLineStyle.ELBOW_JOINT -> "HUD 90° Dirsek"
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
                            Text("Barkod Çevresi Kesikli Çerçeve", style = MaterialTheme.typography.bodyMedium)
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

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Çizgi Kalınlığı: ${"%.1f".format(overlay.leaderLineWidthDp)} dp",
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
            Text(text = "Barkoda Göre Konum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

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
                        Text("Ekran Kenarlarından Otomatik Kaçın", fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = overlay.autoAvoidEdges,
                            onCheckedChange = { onOverlayChanged(overlay.copy(autoAvoidEdges = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Yatay Kaydırma (X): ${overlay.offsetX.toInt()} dp")
                    Slider(
                        value = overlay.offsetX,
                        onValueChange = { onOverlayChanged(overlay.copy(offsetX = it)) },
                        valueRange = -150f..150f
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text("Dikey Kaydırma (Y): ${overlay.offsetY.toInt()} dp")
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
            Text(text = "Arka Plan & Dolgu Stili", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

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
                        OverlayFillMode.OUTLINE_ONLY -> "Sadece Çerçeve"
                        OverlayFillMode.SEMI_TRANSPARENT -> "Yarı Saydam"
                    }
                    FilterChip(
                        selected = overlay.fillMode == mode,
                        onClick = { onOverlayChanged(overlay.copy(fillMode = mode)) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Tema Renk Paleti", style = MaterialTheme.typography.labelLarge)
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
                            .size(44.dp)
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
                            Icon(Icons.Default.Check, contentDescription = "Seçili renk", tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = overlay.textColorHex,
                    onValueChange = { onOverlayChanged(overlay.copy(textColorHex = it)) },
                    label = { Text("Metin Rengi (Hex)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = overlay.borderColorHex,
                    onValueChange = { onOverlayChanged(overlay.copy(borderColorHex = it)) },
                    label = { Text("Çerçeve Rengi (Hex)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Spacer(modifier = Modifier.height(8.dp))

            Text("Ölçek / Yakınlaştırma: %${(overlay.scale * 100).toInt()} (ekranda iki parmakla değiştirin)")
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
                    Text(text = "Yazı Tipi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Yazı Boyutu: ${overlay.fontSizeSp.toInt()} sp")
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
                            label = { Text("Kalın") }
                        )
                        FilterChip(
                            selected = overlay.isItalic,
                            onClick = { onOverlayChanged(overlay.copy(isItalic = !overlay.isItalic)) },
                            label = { Text("İtalik") }
                        )
                        FilterChip(
                            selected = overlay.textAlignCenter,
                            onClick = { onOverlayChanged(overlay.copy(textAlignCenter = !overlay.textAlignCenter)) },
                            label = { Text("Ortala") }
                        )
                    }
                }
            }
        }

        // Border & Shapes
        item {
            Text(text = "Çerçeve & Köşe Yuvarlaklığı", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    label = { Text("Çerçeve Göster") }
                )
                FilterChip(
                    selected = overlay.borderStyle == BorderStyle.SOLID,
                    onClick = { onOverlayChanged(overlay.copy(borderStyle = BorderStyle.SOLID)) },
                    label = { Text("Düz") }
                )
                FilterChip(
                    selected = overlay.borderStyle == BorderStyle.DASHED,
                    onClick = { onOverlayChanged(overlay.copy(borderStyle = BorderStyle.DASHED)) },
                    label = { Text("Kesikli") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Köşe Yuvarlaklığı: ${overlay.cornerRadiusDp.toInt()} dp")
            Slider(
                value = overlay.cornerRadiusDp,
                onValueChange = { onOverlayChanged(overlay.copy(cornerRadiusDp = it)) },
                valueRange = 0f..32f
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text("Çerçeve Kalınlığı: ${overlay.borderWidthDp.toInt()} dp")
            Slider(
                value = overlay.borderWidthDp,
                onValueChange = { onOverlayChanged(overlay.copy(borderWidthDp = it)) },
                valueRange = 1f..6f
            )
        }

        // Icon & Badges
        item {
            Text(text = "Simge ve Rozetler", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

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
                        label = { Text(iconLabel) }
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
                        text = "Rozet Görünürlüğü",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Kamera kartında hangi değişkenlerin ve rozetlerin görüneceğini seçin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Formül Rozeti (= a + b)", style = MaterialTheme.typography.bodyMedium)
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
                        Text("Ham Barkod İçeriği Rozeti", style = MaterialTheme.typography.bodyMedium)
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
                        Text("Değişken Rozetleri (Tümü)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = overlay.showVariableSummaryPill,
                            onCheckedChange = { onOverlayChanged(overlay.copy(showVariableSummaryPill = it)) }
                        )
                    }

                    if (overlay.showVariableSummaryPill) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Tekil Değişken Rozetleri:",
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
                                    label = { Text("Rozet: $varName") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
