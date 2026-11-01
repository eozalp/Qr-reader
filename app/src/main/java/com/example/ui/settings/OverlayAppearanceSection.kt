package com.example.ui.settings

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
import androidx.compose.ui.graphics.Color
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
                        text = "LIVE OVERLAY PREVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF020617)),
                        contentAlignment = Alignment.Center
                    ) {
                        OverlayCardContent(
                            result = sampleResult,
                            config = overlay
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

            Text("Background Opacity: ${(overlay.backgroundOpacity * 100).toInt()}%")
            Slider(
                value = overlay.backgroundOpacity,
                onValueChange = { onOverlayChanged(overlay.copy(backgroundOpacity = it)) },
                valueRange = 0.2f..1.0f
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
                        modifier = Modifier.fillMaxWidth(),
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
