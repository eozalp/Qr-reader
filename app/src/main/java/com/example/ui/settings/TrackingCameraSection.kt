package com.example.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.TrackingSettings

@Composable
fun TrackingCameraSection(
    tracking: TrackingSettings,
    onTrackingChanged: (TrackingSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        text = "Keeps overlay on screen briefly and smoothly fades it out if QR code is temporarily obscured or moving fast.",
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
                        text = "Max Concurrent QR Codes: ${tracking.maxTrackedCodes}",
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
    }
}
