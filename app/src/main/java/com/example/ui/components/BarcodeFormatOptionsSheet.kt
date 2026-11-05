package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.google.mlkit.vision.barcode.common.Barcode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeFormatOptionsSheet(
    currentSettings: TrackingSettings,
    onSettingsChanged: (TrackingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember { mutableStateOf(currentSettings.scanPreset) }
    var selectedFormats by remember {
        mutableStateOf(
            if (currentSettings.enabledFormats.contains(Barcode.FORMAT_ALL_FORMATS) || currentSettings.enabledFormats.isEmpty()) {
                BarcodeCatalog.ALL_ITEMS.map { it.format }.toSet()
            } else {
                currentSettings.enabledFormats.toSet()
            }
        )
    }
    var enableInverted by remember { mutableStateOf(currentSettings.enableInvertedDetection) }

    fun commitChanges(
        newPreset: BarcodeScanPreset = selectedPreset,
        newFormats: Set<Int> = selectedFormats,
        newInverted: Boolean = enableInverted
    ) {
        val formatsToSave = if (newPreset == BarcodeScanPreset.ALL_FORMATS) {
            listOf(Barcode.FORMAT_ALL_FORMATS)
        } else {
            BarcodeCatalog.resolveEffectiveFormats(newPreset, newFormats.toList())
        }
        onSettingsChanged(
            currentSettings.copy(
                scanPreset = newPreset,
                enabledFormats = formatsToSave,
                enableInvertedDetection = newInverted
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF00F5D4).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Color(0xFF00F5D4),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Barcode Reading Options",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "2D, 3D Stacked, and 1D Barcode Formats",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Preset Selection Cards
                item {
                    Text(
                        text = "SCANNING PRESET",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00F5D4),
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarcodeScanPreset.entries.forEach { preset ->
                            val isSelected = selectedPreset == preset
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color(0xFF1E293B) else Color(0xFF131E33),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 1.5.dp else 0.5.dp,
                                    if (isSelected) Color(0xFF00F5D4) else Color(0xFF334155)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPreset = preset
                                        commitChanges(newPreset = preset)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            selectedPreset = preset
                                            commitChanges(newPreset = preset)
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = Color(0xFF00F5D4),
                                            unselectedColor = Color(0xFF64748B)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.label,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isSelected) Color(0xFF00F5D4) else Color.White
                                        )
                                        Text(
                                            text = preset.description,
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3D / Engraved & Inverted Scanning Mode
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF172033)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF3B82F6).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InvertColors,
                                    contentDescription = null,
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "3D & Inverted Barcode Mode",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF3B82F6).copy(alpha = 0.3f))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("DPM / 3D", fontSize = 8.5.sp, color = Color(0xFF93C5FD), fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    text = "Dual-pass luminance analysis for laser-etched, dot-peen, inverted dark surfaces, and embossed 3D barcodes.",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = enableInverted,
                                onCheckedChange = {
                                    enableInverted = it
                                    commitChanges(newInverted = it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFF3B82F6)
                                )
                            )
                        }
                    }
                }

                // Granular Formats Catalog
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SUPPORTED FORMATS & SPECIFICATIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F5D4),
                            letterSpacing = 0.8.sp
                        )

                        if (selectedPreset == BarcodeScanPreset.CUSTOM) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        selectedFormats = BarcodeCatalog.ALL_ITEMS.map { it.format }.toSet()
                                        commitChanges(newFormats = selectedFormats)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Select All", fontSize = 11.sp, color = Color(0xFF00F5D4))
                                }
                                TextButton(
                                    onClick = {
                                        selectedFormats = emptySet()
                                        commitChanges(newFormats = selectedFormats)
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Clear", fontSize = 11.sp, color = Color(0xFFF87171))
                                }
                            }
                        }
                    }
                }

                // Group formats by category
                BarcodeCategory.entries.forEach { category ->
                    val itemsInCategory = BarcodeCatalog.ALL_ITEMS.filter { it.category == category }

                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = category.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${itemsInCategory.size} formats",
                                        fontSize = 10.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                Text(
                                    text = category.subtitle,
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    items(itemsInCategory) { item ->
                        val isEffectiveEnabled = when (selectedPreset) {
                            BarcodeScanPreset.ALL_FORMATS -> true
                            BarcodeScanPreset.TWO_D_ONLY -> item.category == BarcodeCategory.TWO_D_AND_STACKED
                            BarcodeScanPreset.ONE_D_ONLY -> item.category != BarcodeCategory.TWO_D_AND_STACKED
                            BarcodeScanPreset.CUSTOM -> selectedFormats.contains(item.format)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isEffectiveEnabled) Color(0xFF1E293B) else Color(0xFF0B1120),
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                if (isEffectiveEnabled) Color(0xFF00F5D4).copy(alpha = 0.4f) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = selectedPreset == BarcodeScanPreset.CUSTOM) {
                                    val updated = if (selectedFormats.contains(item.format)) {
                                        selectedFormats - item.format
                                    } else {
                                        selectedFormats + item.format
                                    }
                                    selectedFormats = updated
                                    commitChanges(newFormats = updated)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedPreset == BarcodeScanPreset.CUSTOM) {
                                    Checkbox(
                                        checked = selectedFormats.contains(item.format),
                                        onCheckedChange = { checked ->
                                            val updated = if (checked) {
                                                selectedFormats + item.format
                                            } else {
                                                selectedFormats - item.format
                                            }
                                            selectedFormats = updated
                                            commitChanges(newFormats = updated)
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF00F5D4),
                                            checkmarkColor = Color(0xFF0F172A)
                                        )
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (isEffectiveEnabled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isEffectiveEnabled) Color(0xFF00F5D4) else Color(0xFF475569),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.displayName,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp,
                                            color = if (isEffectiveEnabled) Color.White else Color(0xFF64748B)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (item.badge.contains("3D")) Color(0xFF8B5CF6).copy(alpha = 0.25f)
                                                    else if (item.badge.contains("2D")) Color(0xFF00F5D4).copy(alpha = 0.15f)
                                                    else Color(0xFF3B82F6).copy(alpha = 0.15f)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = item.badge,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.badge.contains("3D")) Color(0xFFA78BFA)
                                                else if (item.badge.contains("2D")) Color(0xFF00F5D4)
                                                else Color(0xFF60A5FA)
                                            )
                                        }
                                    }
                                    Text(
                                        text = item.summary,
                                        fontSize = 10.5.sp,
                                        color = if (isEffectiveEnabled) Color(0xFF94A3B8) else Color(0xFF475569)
                                    )
                                    Text(
                                        text = "e.g. ${item.exampleUsage}",
                                        fontSize = 9.5.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectedPreset = BarcodeScanPreset.ALL_FORMATS
                        selectedFormats = BarcodeCatalog.ALL_ITEMS.map { it.format }.toSet()
                        enableInverted = true
                        commitChanges(
                            newPreset = BarcodeScanPreset.ALL_FORMATS,
                            newFormats = selectedFormats,
                            newInverted = true
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                ) {
                    Text("Reset Defaults", fontSize = 12.sp)
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4), contentColor = Color(0xFF0F172A))
                ) {
                    Text("Done", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
