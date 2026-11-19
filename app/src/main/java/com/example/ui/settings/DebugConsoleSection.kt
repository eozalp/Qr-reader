package com.example.ui.settings

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.debug.AppDebugLogger
import com.example.debug.DebugLogEntry
import com.example.debug.DebugLogLevel
import com.example.ui.theme.AmberGold
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.NeonCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DebugLogFilter(val label: String) {
    ALL("Tümü"),
    ERRORS("Hatalar"),
    WARNINGS("Uyarı+"),
    BLUETOOTH("Bluetooth"),
    CAMERA("Kamera"),
    IME("IME")
}

@Composable
fun DebugConsoleSection(
    hidManager: BluetoothHidManager? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val manager = remember(context) { hidManager ?: BluetoothHidManager.getInstance(context) }

    val logs by AppDebugLogger.logs.collectAsState()
    val lastCrashReport by AppDebugLogger.lastCrashReport.collectAsState()
    val connectionStatus by manager.connectionStatus.collectAsState()
    val connectedDeviceName by manager.connectedDeviceName.collectAsState()
    val hidTelemetry by manager.telemetry.collectAsState()
    val pairedDevices by manager.pairedDevices.collectAsState()

    var selectedFilter by remember { mutableStateOf(DebugLogFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var toggledTraceIds by remember { mutableStateOf(setOf<Long>()) }

    val errorCount = remember(logs) {
        logs.count { it.level.severity >= DebugLogLevel.ERROR.severity }
    }

    val filteredLogs = remember(logs, selectedFilter, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        logs.filter { entry ->
            val matchesFilter = when (selectedFilter) {
                DebugLogFilter.ALL -> true
                DebugLogFilter.ERRORS -> entry.level.severity >= DebugLogLevel.ERROR.severity
                DebugLogFilter.WARNINGS -> entry.level.severity >= DebugLogLevel.WARN.severity
                DebugLogFilter.BLUETOOTH -> entry.tag.contains("BT", ignoreCase = true) ||
                    entry.tag.contains("MOUSE", ignoreCase = true) ||
                    entry.tag.contains("HID", ignoreCase = true)
                DebugLogFilter.CAMERA -> entry.tag.contains("CAM", ignoreCase = true) ||
                    entry.tag.contains("QR", ignoreCase = true)
                DebugLogFilter.IME -> entry.tag.contains("IME", ignoreCase = true)
            }
            val matchesSearch = q.isEmpty() ||
                entry.tag.lowercase(Locale.ROOT).contains(q) ||
                entry.message.lowercase(Locale.ROOT).contains(q) ||
                (entry.stackTrace?.lowercase(Locale.ROOT)?.contains(q) == true)
            matchesFilter && matchesSearch
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun copyFullReport(entriesToInclude: List<DebugLogEntry>) {
        val report = AppDebugLogger.buildShareableReport(
            hidStatus = connectionStatus.label,
            connectedHost = connectedDeviceName,
            hidTelemetry = hidTelemetry,
            pairedCount = pairedDevices.size,
            entries = entriesToInclude
        )
        clipboard.setText(AnnotatedString(report))
        android.widget.Toast.makeText(
            context,
            "Debug raporu panoya kopyalandı (${entriesToInclude.size} kayıt)",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun shareFullReport(entriesToInclude: List<DebugLogEntry>) {
        val report = AppDebugLogger.buildShareableReport(
            hidStatus = connectionStatus.label,
            connectedHost = connectedDeviceName,
            hidTelemetry = hidTelemetry,
            pairedCount = pairedDevices.size,
            entries = entriesToInclude
        )
        runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ALVETAK QR Debug Raporu")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Debug Loglarını Paylaş"))
        }.onFailure { err ->
            AppDebugLogger.e("UI", "Debug raporu paylaşılamadı", err)
            clipboard.setText(AnnotatedString(report))
            android.widget.Toast.makeText(context, "Paylaşım açılamadı, rapor panoya kopyalandı", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = 16.dp,
            bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Previous Fatal Crash Alert Banner (if captured before restart)
        if (!lastCrashReport.isNullOrBlank()) {
            item(key = "crash_banner") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B0B0E)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Kritik Çökme Kaydı",
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "ÖNCEKİ OTURUMDA ÇÖKME YAKALANDI",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFFCA5A5)
                            )
                        }
                        Text(
                            text = lastCrashReport ?: "",
                            fontSize = 10.5.sp,
                            fontFamily = AppMonoFontFamily,
                            color = Color(0xFFFEE2E2),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    clipboard.setText(AnnotatedString(lastCrashReport ?: ""))
                                    android.widget.Toast.makeText(context, "Çökme raporu panoya kopyalandı", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEF4444),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("debug_copy_crash_btn")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Çökmeyi Kopyala", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { AppDebugLogger.clearCrashBanner() },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("debug_dismiss_crash_btn")
                            ) {
                                Text("Kapat", fontSize = 12.sp, color = Color(0xFFFCA5A5))
                            }
                        }
                    }
                }
            }
        }

        // 2. Live Hardware / Bluetooth HID Telemetry Card
        item(key = "telemetry_summary") {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1624)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFF1F2D45)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SİSTEM & BLUETOOTH HID ÖZETİ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (errorCount > 0) Color(0xFFEF4444).copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (errorCount > 0) "$errorCount Hata" else "Hata Yok",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (errorCount > 0) Color(0xFFFCA5A5) else NeonCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = AppDebugLogger.buildDeviceSummary(),
                        fontSize = 10.5.sp,
                        fontFamily = AppMonoFontFamily,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "Durum: ${connectionStatus.label} (${connectedDeviceName ?: "Yok"}) · Eşleşmiş: ${pairedDevices.size}",
                        fontSize = 11.sp,
                        fontFamily = AppMonoFontFamily,
                        color = Color.White
                    )
                    Text(
                        text = "Protokol: ${if (hidTelemetry.isBootMode) "BOOT MODE" else "REPORT MODE"} · ${hidTelemetry.qosLabel}",
                        fontSize = 11.sp,
                        fontFamily = AppMonoFontFamily,
                        color = NeonCyan
                    )
                    Text(
                        text = "Klavye TX: ${hidTelemetry.keyboardReportsSent} (Hata: ${hidTelemetry.keyboardReportsFailed}) · Mouse TX: ${hidTelemetry.mouseReportsSent} (Hata: ${hidTelemetry.mouseReportsFailed})",
                        fontSize = 11.sp,
                        fontFamily = AppMonoFontFamily,
                        color = Color(0xFFCBD5E1)
                    )
                    if (!hidTelemetry.lastFailureReason.isNullOrBlank()) {
                        Text(
                            text = "Son HID Hatası: ${hidTelemetry.lastFailureReason}",
                            fontSize = 11.sp,
                            color = Color(0xFFFCA5A5),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { copyFullReport(logs) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = Color(0xFF00382E)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1.25f)
                                .height(48.dp)
                                .testTag("debug_copy_all_btn")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Logları Kopyala", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = { shareFullReport(logs) },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(0.95f)
                                .height(48.dp)
                                .testTag("debug_share_btn")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paylaş", fontSize = 12.sp, color = Color.White, maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = {
                                AppDebugLogger.clearLogs()
                                toggledTraceIds = emptySet()
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("debug_clear_btn")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Temizle", tint = AmberGold, modifier = Modifier.size(18.dp))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                manager.reregisterHidProfile()
                                android.widget.Toast.makeText(context, "HID profili yeniden kaydediliyor...", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("debug_hid_reregister_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("HID Profili Yenile", fontSize = 11.5.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. Filter Chips & Search Input
        item(key = "filter_controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DebugLogFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label, fontSize = 11.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = Color(0xFF00382E)
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Etiket, hata mesajı veya yığın ara...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Aramayı Temizle")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("debug_search_input")
                )

                Text(
                    text = "Gösterilen: ${filteredLogs.size} / ${logs.size} kayıt (Dokunup tek satır kopyalayabilirsiniz)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 4. Empty State or Live Terminal Log Entries
        if (filteredLogs.isEmpty()) {
            item(key = "empty_logs") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF070B14)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Bu filtre için kayıt bulunamadı",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Text(
                            text = "Uygulamadaki yakalanan istisnalar, Bluetooth HID durumları ve kamera olayları burada gerçek zamanlı birikir.",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { entry ->
                val defaultExpanded = entry.level.severity >= DebugLogLevel.ERROR.severity
                val isExpanded = if (defaultExpanded) entry.id !in toggledTraceIds else entry.id in toggledTraceIds
                DebugLogEntryCard(
                    entry = entry,
                    timeText = timeFmt.format(Date(entry.timestampMs)),
                    isExpanded = isExpanded,
                    onToggleExpand = {
                        toggledTraceIds = if (entry.id in toggledTraceIds) {
                            toggledTraceIds - entry.id
                        } else {
                            toggledTraceIds + entry.id
                        }
                    },
                    onCopyEntry = {
                        val singleText = buildString {
                            append("[${timeFmt.format(Date(entry.timestampMs))}] [${entry.level.shortLabel}/${entry.tag}] (${entry.threadName}) ${entry.message}")
                            if (!entry.stackTrace.isNullOrBlank()) {
                                appendLine()
                                append(entry.stackTrace)
                            }
                        }
                        clipboard.setText(AnnotatedString(singleText))
                        android.widget.Toast.makeText(context, "Kayıt panoya kopyalandı", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun DebugLogEntryCard(
    entry: DebugLogEntry,
    timeText: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopyEntry: () -> Unit
) {
    val badgeColor = when (entry.level) {
        DebugLogLevel.FATAL -> Color(0xFFDC2626)
        DebugLogLevel.ERROR -> Color(0xFFEF4444)
        DebugLogLevel.WARN -> AmberGold
        DebugLogLevel.INFO -> NeonCyan
        DebugLogLevel.DEBUG -> Color(0xFF64748B)
    }
    val cardBorder = if (entry.level.severity >= DebugLogLevel.ERROR.severity) {
        Color(0xFFEF4444).copy(alpha = 0.55f)
    } else {
        Color(0xFF1E293B)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF070B14),
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                if (!entry.stackTrace.isNullOrBlank()) onToggleExpand() else onCopyEntry()
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = badgeColor.copy(alpha = 0.18f)
                    ) {
                        Text(
                            text = entry.level.shortLabel,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = AppMonoFontFamily,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = entry.tag,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AppMonoFontFamily,
                        color = Color(0xFFE2E8F0)
                    )
                    Text(
                        text = timeText,
                        fontSize = 10.sp,
                        fontFamily = AppMonoFontFamily,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "(${entry.threadName})",
                        fontSize = 9.5.sp,
                        fontFamily = AppMonoFontFamily,
                        color = Color(0xFF475569),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onCopyEntry,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Satırı Kopyala",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Text(
                text = entry.message,
                fontSize = 11.sp,
                fontFamily = AppMonoFontFamily,
                color = if (entry.level.severity >= DebugLogLevel.ERROR.severity) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
            )

            if (!entry.stackTrace.isNullOrBlank()) {
                if (isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0D1320),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = entry.stackTrace,
                            fontSize = 9.5.sp,
                            fontFamily = AppMonoFontFamily,
                            color = Color(0xFFFDA4AF),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    Text(
                        text = "▸ Yığın izini göstermek için dokunun",
                        fontSize = 10.sp,
                        color = AmberGold
                    )
                }
            }
        }
    }
}
