package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.debug.AppDebugLogger
import com.example.model.ScanHistoryItem
import com.example.ui.theme.AppMonoFontFamily
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.OnDarkSurface
import com.example.ui.theme.OnDarkSurfaceVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHistorySheet(
    historyItems: List<ScanHistoryItem>,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("tr", "TR"))
    val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("tr", "TR"))

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
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Tarama Oturumu Geçmişi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Bu oturumda ${historyItems.size} barkod kaydedildi",
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

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Export CSV & Clear History
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Export CSV Button
                Button(
                    onClick = {
                        if (historyItems.isEmpty()) {
                            Toast.makeText(context, "Dışa aktarılacak tarama geçmişi bulunmuyor", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val csvBuilder = StringBuilder()
                        csvBuilder.append("ZamanDamgasi,TarihSaat,BarkodFormati,Profil,HamVeri,HesaplananSonuc,SayisalDeger\n")
                        historyItems.forEach { item ->
                            val safeRaw = item.rawText.replace("\"", "\"\"")
                            val safeResult = item.resultText.replace("\"", "\"\"")
                            val safeProfile = item.profileName.replace("\"", "\"\"")
                            val dateStr = dateTimeFormat.format(Date(item.timestamp))
                            csvBuilder.append("${item.timestamp},\"$dateStr\",\"${item.formatName}\",\"$safeProfile\",\"$safeRaw\",\"$safeResult\",${item.numericValue ?: ""}\n")
                        }
                        val csvContent = csvBuilder.toString()
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TEXT, csvContent)
                            putExtra(Intent.EXTRA_SUBJECT, "Barkod_Tarama_Gecmisi_${System.currentTimeMillis()}.csv")
                            putExtra(Intent.EXTRA_TITLE, "Tarama Geçmişini CSV Olarak Dışa Aktar")
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(sendIntent, "Tarama Geçmişini Paylaş (CSV)"))
                        }.onFailure { err ->
                            AppDebugLogger.e("HISTORY", "CSV paylaşım penceresi açılamadı", err)
                            clipboardManager.setText(AnnotatedString(csvContent))
                            Toast.makeText(context, "Paylaşım açılamadı, CSV panoya kopyalandı", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF00382E)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("export_csv_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CSV Olarak Aktar",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                // Clear History Button
                OutlinedButton(
                    onClick = {
                        onClearHistory()
                        Toast.makeText(context, "Tarama geçmişi temizlendi", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFEF4444)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Temizle",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // History List or Empty State
            if (historyItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
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
                            text = "Kamerayı barkodlara doğrultun veya AR Simülatörünü kullanın",
                            color = OnDarkSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(historyItems.reversed(), key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                // Top row: format tag, time, profile
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

                                // Raw Code and Result
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
                                        Text(
                                            text = "Sonuç: ",
                                            color = OnDarkSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = item.resultText,
                                            color = NeonCyan,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // Copy raw
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

                                        // Copy result
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
