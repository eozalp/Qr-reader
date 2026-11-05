package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BarcodeCatalog
import com.example.model.BarcodeScanPreset
import com.example.model.TrackingSettings
import com.example.ui.components.BarcodeFormatOptionsSheet
import com.google.mlkit.vision.barcode.common.Barcode

@Composable
fun TrackingCameraSection(
    tracking: TrackingSettings,
    onTrackingChanged: (TrackingSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var showBarcodeOptionsSheet by remember { mutableStateOf(false) }

    if (showBarcodeOptionsSheet) {
        BarcodeFormatOptionsSheet(
            currentSettings = tracking,
            onSettingsChanged = onTrackingChanged,
            onDismiss = { showBarcodeOptionsSheet = false }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Barcode Reading Options (2D, 3D, 1D)
        item {
            Text(
                text = "Barcode Reading Options",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure scanner capabilities for 2D matrix, 3D stacked, and 1D retail & industrial barcodes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active Scanning Preset",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = tracking.scanPreset.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { showBarcodeOptionsSheet = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Options", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Preset Chips Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BarcodeScanPreset.entries.forEach { preset ->
                            val isSelected = tracking.scanPreset == preset
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    val formats = if (preset == BarcodeScanPreset.ALL_FORMATS) {
                                        listOf(Barcode.FORMAT_ALL_FORMATS)
                                    } else {
                                        BarcodeCatalog.resolveEffectiveFormats(preset, tracking.enabledFormats)
                                    }
                                    onTrackingChanged(
                                        tracking.copy(
                                            scanPreset = preset,
                                            enabledFormats = formats
                                        )
                                    )
                                },
                                label = {
                                    Text(
                                        text = when (preset) {
                                            BarcodeScanPreset.ALL_FORMATS -> "All (Auto)"
                                            BarcodeScanPreset.TWO_D_ONLY -> "2D + 3D"
                                            BarcodeScanPreset.ONE_D_ONLY -> "1D Only"
                                            BarcodeScanPreset.CUSTOM -> "Custom"
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Inverted & 3D Scanning Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "3D & Inverted Mode",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "DPM / Laser",
                                        fontSize = 8.5.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = "Dual-pass reading for laser-etched, dot-peen, inverted dark-surface, and high-density barcodes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tracking.enableInvertedDetection,
                            onCheckedChange = { onTrackingChanged(tracking.copy(enableInvertedDetection = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Status pill showing total supported formats
                    val effectiveCount = if (tracking.scanPreset == BarcodeScanPreset.ALL_FORMATS) {
                        "All 13 formats active"
                    } else if (tracking.scanPreset == BarcodeScanPreset.TWO_D_ONLY) {
                        "4 formats: QR, Data Matrix, PDF417, Aztec"
                    } else if (tracking.scanPreset == BarcodeScanPreset.ONE_D_ONLY) {
                        "9 formats: EAN, UPC, Code 128, 39, 93, Codabar, ITF"
                    } else {
                        "${tracking.enabledFormats.size} custom formats active"
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBarcodeOptionsSheet = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = effectiveCount,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Section: Tracking & Continuity Engine
        item {
            Text(
                text = "Tracking & Continuity Engine",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Adjust spatial bounding-box stabilization, disappearance timeout, and multi-code concurrency.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Spatial EMA Smoothing (${(tracking.smoothingFactor * 100).toInt()}%)",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Higher value updates instantly with minimal delay; lower value yields silky-smooth stabilized motion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = tracking.smoothingFactor,
                        onValueChange = { onTrackingChanged(tracking.copy(smoothingFactor = it)) },
                        valueRange = 0.15f..1.0f
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Disappearance Timeout: ${tracking.disappearanceTimeoutMs} ms",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Keeps overlay on screen briefly and smoothly fades it out if barcode is temporarily obscured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = tracking.disappearanceTimeoutMs.toFloat(),
                        onValueChange = { onTrackingChanged(tracking.copy(disappearanceTimeoutMs = it.toLong())) },
                        valueRange = 250f..3000f,
                        steps = 10
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Max Concurrent Codes: ${tracking.maxTrackedCodes}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = tracking.maxTrackedCodes.toFloat(),
                        onValueChange = { onTrackingChanged(tracking.copy(maxTrackedCodes = it.toInt())) },
                        valueRange = 1f..15f,
                        steps = 13
                    )
                }
            }
        }

        // Section: Hardware Camera Preferences
        item {
            Text(
                text = "Hardware Camera Preferences",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

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
                        Text("Default to Front Camera", fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = tracking.useFrontCamera,
                            onCheckedChange = { onTrackingChanged(tracking.copy(useFrontCamera = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Torch / Flashlight by Default", fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = tracking.enableTorch,
                            onCheckedChange = { onTrackingChanged(tracking.copy(enableTorch = it)) }
                        )
                    }
                }
            }
        }

        // Section: Emulator & Test Tools ("Inner Menu")
        item {
            Text(
                text = "Emulator Testing Lab",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Virtual QR & barcode simulation tools have been moved to the inner menu to keep the camera view clean and uncrowded. Access simulation anytime via the top-right menu (⋮) on the scanner screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = Color(0xFF00F5D4),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inner Menu Virtual Simulator",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Tap the top-right menu icon (⋮) on the live scanner screen to inject test QR, EAN-13, Code 128, or PDF417 codes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
