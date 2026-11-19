package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = 16.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Barkod Okuma Seçenekleri (2D, 3D, 1D)
        item {
            Text(
                text = "Barkod Okuma Seçenekleri",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "2D matris, 3D yığın ve 1D perakende/endüstriyel barkod formatlarını yapılandırın.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Aktif Tarama Ön Ayarı",
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
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Formatlar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Scrollable Preset Chips Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
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
                                            BarcodeScanPreset.ALL_FORMATS -> "Tümü (Oto)"
                                            BarcodeScanPreset.TWO_D_ONLY -> "2D + 3D"
                                            BarcodeScanPreset.ONE_D_ONLY -> "Sadece 1D"
                                            BarcodeScanPreset.CUSTOM -> "Özel"
                                        },
                                        fontSize = 11.5.sp
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
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "3D & Ters Renkli Mod",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "DPM / Lazer",
                                        fontSize = 8.5.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = "Lazer kazıma, koyu zemin üzerine beyaz ve yüksek yoğunluklu barkodlar için çift geçişli analiz.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = tracking.enableInvertedDetection,
                            onCheckedChange = { onTrackingChanged(tracking.copy(enableInvertedDetection = it)) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Status pill showing total supported formats
                    val effectiveCount = when (tracking.scanPreset) {
                        BarcodeScanPreset.ALL_FORMATS -> "Tüm 13 barkod formatı etkin"
                        BarcodeScanPreset.TWO_D_ONLY -> "4 format etkin: QR, Data Matrix, PDF417, Aztec"
                        BarcodeScanPreset.ONE_D_ONLY -> "9 format etkin: EAN, UPC, Code 128, 39, 93, Codabar, ITF"
                        BarcodeScanPreset.CUSTOM -> "${tracking.enabledFormats.size} özel format etkin"
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showBarcodeOptionsSheet = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = effectiveCount,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Formatları Düzenle",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Section: Takip & Süreklilik Motoru
        item {
            Text(
                text = "Takip & Kararlılık Motoru",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "AR kutusu yumuşatma katsayısı, ekranda kalma süresi ve aynı anda taranacak kod sayısını ayarlayın.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Konum Yumuşatma (EMA): %${(tracking.smoothingFactor * 100).toInt()}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Yüksek değer anlık tepki verir; düşük değer titremesiz ve akıcı bir takip sağlar.",
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
                        text = "Kaybolma Gecikmesi: ${tracking.disappearanceTimeoutMs} ms",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Barkod anlık olarak kadrajdan çıktığında kartı bir süre daha korur ve yumuşakça gizler.",
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
                        text = "Eşzamanlı Maksimum Kod: ${tracking.maxTrackedCodes}",
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

        // Section: Kamera Donanım Tercihleri
        item {
            Text(
                text = "Kamera Donanım Tercihleri",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Varsayılan Olarak Ön Kamerayı Aç", fontWeight = FontWeight.SemiBold)
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
                        Text("Flaş / El Feneri Varsayılan Açık", fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = tracking.enableTorch,
                            onCheckedChange = { onTrackingChanged(tracking.copy(enableTorch = it)) }
                        )
                    }
                }
            }
        }

        // Section: AR Simülatörü Bilgisi
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
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
                            text = "Sanal AR Barkod Simülatörü",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Canlı kamera ekranındaki sağ üst menüden (⋮) test QR, EAN-13, Code 128 veya PDF417 barkodları enjekte edebilirsiniz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
