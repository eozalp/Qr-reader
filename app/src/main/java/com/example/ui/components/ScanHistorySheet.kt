package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.debug.AppDebugLogger
import com.example.engine.ScanCartAggregator
import com.example.model.BluetoothKeyboardConfig
import com.example.model.KeyboardEndingKey
import com.example.model.ScanHistoryItem
import com.example.ui.theme.AmberGold
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.OnDarkSurface
import com.example.ui.theme.OnDarkSurfaceVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHistorySheet(
    historyItems: List<ScanHistoryItem>,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    hidManager: BluetoothHidManager? = null,
    keyboardConfig: BluetoothKeyboardConfig? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale("tr", "TR")) }
    val dateTimeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("tr", "TR")) }

    val btStatus by (hidManager?.connectionStatus?.collectAsState()
        ?: remember { mutableStateOf(HidConnectionStatus.DISCONNECTED) })
    val isPcConnected = btStatus == HidConnectionStatus.CONNECTED

    var isCartView by remember { mutableStateOf(true) }
    var quantityOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var removedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSendingBatchToPc by remember { mutableStateOf(false) }

    val cartItems = remember(historyItems, quantityOverrides, removedKeys) {
        ScanCartAggregator.aggregate(historyItems, quantityOverrides, removedKeys)
    }
    val totalUnits = remember(cartItems) { ScanCartAggregator.totalUnits(cartItems) }
    val grandTotal = remember(cartItems) { ScanCartAggregator.grandTotal(cartItems) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCartCheckout,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Sayım Sepeti & Geçmiş",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${cartItems.size} farklı kod · $totalUnits toplam adet",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = OnDarkSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Grand Total Summary Banner
            if (cartItems.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF082F29),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "SEPET GENEL TOPLAMI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp,
                                color = NeonCyan.copy(alpha = 0.85f)
                            )
                            Text(
                                text = "$totalUnits ürün · ${cartItems.size} kalem",
                                fontSize = 11.sp,
                                color = OnDarkSurfaceVariant
                            )
                        }
                        Text(
                            text = ScanCartAggregator.formatMoney(grandTotal),
                            fontFamily = AppMonoFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            color = NeonCyan
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // View Toggle: Sepet Özeti vs Zaman Akışı
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = isCartView,
                    onClick = { isCartView = true },
                    leadingIcon = {
                        Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Sepet Özeti (${cartItems.size})") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("history_cart_tab")
                )
                FilterChip(
                    selected = !isCartView,
                    onClick = { isCartView = false },
                    leadingIcon = {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    label = { Text("Zaman Akışı (${historyItems.size})") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("history_timeline_tab")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary Action Row: PC Excel Batch Push + CSV Export + Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hidManager != null && keyboardConfig != null) {
                    Button(
                        onClick = {
                            if (cartItems.isEmpty() || isSendingBatchToPc) return@Button
                            if (!isPcConnected) {
                                Toast.makeText(context, "Excel'e aktarmak için önce PC Bluetooth bağlantısı kurun", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSendingBatchToPc = true
                            coroutineScope.launch {
                                try {
                                    var sentRows = 0
                                    for (row in cartItems) {
                                        val cols = ScanCartAggregator.buildExcelColumns(row)
                                        for (colIdx in cols.indices) {
                                            val endKey = if (colIdx < cols.lastIndex) KeyboardEndingKey.TAB else KeyboardEndingKey.ENTER
                                            hidManager.sendToComputer(
                                                textToSend = cols[colIdx],
                                                endingKey = endKey,
                                                typingDelayMs = keyboardConfig.typingDelayMs,
                                                convertTurkishChars = keyboardConfig.convertTurkishChars,
                                                keyboardLayout = keyboardConfig.keyboardLayout
                                            )
                                            delay(35L)
                                        }
                                        sentRows++
                                    }
                                    Toast.makeText(context, "$sentRows satır PC Excel'e aktarıldı!", Toast.LENGTH_SHORT).show()
                                } catch (err: Exception) {
                                    AppDebugLogger.e("HISTORY", "Toplu Excel BT gönderim hatası", err)
                                    Toast.makeText(context, "PC aktarımı kesildi: ${err.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isSendingBatchToPc = false
                                }
                            }
                        },
                        enabled = cartItems.isNotEmpty() && !isSendingBatchToPc,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPcConnected) NeonCyan else Color(0xFF334155),
                            contentColor = if (isPcConnected) Color(0xFF00382E) else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp)
                            .testTag("batch_send_excel_pc_btn")
                    ) {
                        Icon(
                            imageVector = if (isSendingBatchToPc) Icons.Default.Sync else Icons.Default.TableView,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSendingBatchToPc) "Yazılıyor..." else "PC Excel'e Bas",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }

                // Export CSV Button
                FilledTonalButton(
                    onClick = {
                        if (cartItems.isEmpty() && historyItems.isEmpty()) {
                            Toast.makeText(context, "Dışa aktarılacak kayıt bulunmuyor", Toast.LENGTH_SHORT).show()
                            return@FilledTonalButton
                        }
                        val csvBuilder = StringBuilder()
                        if (isCartView && cartItems.isNotEmpty()) {
                            csvBuilder.append("Barkod,Format,Profil,Adet,BirimSonuc,SatirToplami\n")
                            cartItems.forEach { item ->
                                val safeRaw = item.rawText.replace("\"", "\"\"")
                                val safeResult = item.resultText.replace("\"", "\"\"")
                                val safeProfile = item.profileName.replace("\"", "\"\"")
                                val lineTotalStr = item.lineTotal?.let { String.format(Locale.US, "%.2f", it) } ?: ""
                                csvBuilder.append("\"$safeRaw\",\"${item.formatName}\",\"$safeProfile\",${item.quantity},\"$safeResult\",$lineTotalStr\n")
                            }
                        } else {
                            csvBuilder.append("ZamanDamgasi,TarihSaat,BarkodFormati,Profil,HamVeri,HesaplananSonuc,SayisalDeger\n")
                            historyItems.forEach { item ->
                                val safeRaw = item.rawText.replace("\"", "\"\"")
                                val safeResult = item.resultText.replace("\"", "\"\"")
                                val safeProfile = item.profileName.replace("\"", "\"\"")
                                val dateStr = dateTimeFormat.format(Date(item.timestamp))
                                csvBuilder.append("${item.timestamp},\"$dateStr\",\"${item.formatName}\",\"$safeProfile\",\"$safeRaw\",\"$safeResult\",${item.numericValue ?: ""}\n")
                            }
                        }
                        val csvContent = csvBuilder.toString()
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TEXT, csvContent)
                            putExtra(Intent.EXTRA_SUBJECT, "ALVETAK_Sayim_${System.currentTimeMillis()}.csv")
                            putExtra(Intent.EXTRA_TITLE, "Sayım ve Tarama Geçmişi (CSV)")
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(sendIntent, "CSV Paylaş"))
                        }.onFailure { err ->
                            AppDebugLogger.e("HISTORY", "CSV paylaşım penceresi açılamadı", err)
                            clipboardManager.setText(AnnotatedString(csvContent))
                            Toast.makeText(context, "Paylaşım açılamadı, CSV panoya kopyalandı", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(0.95f)
                        .height(48.dp)
                        .testTag("export_csv_button")
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("CSV", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                // Clear History Button
                OutlinedButton(
                    onClick = {
                        quantityOverrides = emptyMap()
                        removedKeys = emptySet()
                        onClearHistory()
                        Toast.makeText(context, "Sayım sepeti ve geçmiş temizlendi", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("clear_history_button")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Temizle", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Empty State vs List
            val isEmpty = if (isCartView) cartItems.isEmpty() else historyItems.isEmpty()
            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = OnDarkSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Henüz barkod taranmadı",
                            color = OnDarkSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Okuttuğunuz ürünler burada adet ve toplam tutarla birikir",
                            color = OnDarkSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }
            } else if (isCartView) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(cartItems, key = { it.rawText }) { line ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = line.rawText,
                                        color = OnDarkSurface,
                                        fontSize = 12.5.sp,
                                        fontFamily = AppMonoFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = line.lineTotal?.let { ScanCartAggregator.formatMoney(it) } ?: line.resultText,
                                        color = NeonCyan,
                                        fontFamily = AppMonoFontFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "${line.profileName} · Birim: ${line.resultText}",
                                            color = OnDarkSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }

                                    // Quantity stepper (- qty +)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                val nextQty = line.quantity - 1
                                                if (nextQty <= 0) {
                                                    removedKeys = removedKeys + line.rawText
                                                } else {
                                                    quantityOverrides = quantityOverrides + (line.rawText to nextQty)
                                                }
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (line.quantity <= 1) Icons.Default.DeleteOutline else Icons.Default.Remove,
                                                contentDescription = "Azalt",
                                                tint = if (line.quantity <= 1) Color(0xFFEF4444) else AmberGold,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Text(
                                            text = "×${line.quantity}",
                                            color = Color.White,
                                            fontFamily = AppMonoFontFamily,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                quantityOverrides = quantityOverrides + (line.rawText to (line.quantity + 1))
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Artır",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(historyItems.reversed(), key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = NeonCyan.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = item.formatName,
                                                color = NeonCyan,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                            )
                                        }
                                        Text(
                                            text = item.profileName,
                                            color = OnDarkSurfaceVariant,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Text(
                                        text = timeFormat.format(Date(item.timestamp)),
                                        color = OnDarkSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontFamily = AppMonoFontFamily
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = item.rawText,
                                    color = OnDarkSurface,
                                    fontSize = 12.sp,
                                    fontFamily = AppMonoFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "Sonuç: ", color = OnDarkSurfaceVariant, fontSize = 11.sp)
                                        Text(
                                            text = item.resultText,
                                            color = NeonCyan,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(item.rawText))
                                                Toast.makeText(context, "Ham barkod kopyalandı", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DataObject,
                                                contentDescription = "Ham Veriyi Kopyala",
                                                tint = OnDarkSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(item.resultText))
                                                Toast.makeText(context, "Sonuç kopyalandı: ${item.resultText}", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Sonucu Kopyala",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
