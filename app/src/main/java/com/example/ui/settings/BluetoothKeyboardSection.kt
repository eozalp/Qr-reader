package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.model.BluetoothKeyboardConfig
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardSendTarget
import com.example.model.VariableDefinition
import com.example.ui.theme.AmberGold
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothKeyboardSection(
    config: BluetoothKeyboardConfig,
    onConfigChanged: (BluetoothKeyboardConfig) -> Unit,
    availableVariables: List<VariableDefinition>,
    hidManager: BluetoothHidManager? = null,
    onRequestBluetoothPermission: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val manager = remember(context) { hidManager ?: BluetoothHidManager(context) }

    val connectionStatus by manager.connectionStatus.collectAsState()
    val connectedDeviceName by manager.connectedDeviceName.collectAsState()
    val recentLogs by manager.recentLogs.collectAsState()
    val pairedDevices by manager.pairedDevices.collectAsState()

    var testInputText by remember { mutableStateOf("ALVETAK-PC-TEST") }
    var isSendingTest by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Overview & Bluetooth Status Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionStatus) {
                        HidConnectionStatus.CONNECTED -> Color(0xFF062822)
                        HidConnectionStatus.CONNECTING -> Color(0xFF2E2606)
                        HidConnectionStatus.PERMISSION_REQUIRED -> Color(0xFF2E0606)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (connectionStatus) {
                        HidConnectionStatus.CONNECTED -> NeonCyan
                        HidConnectionStatus.CONNECTING -> AmberGold
                        HidConnectionStatus.PERMISSION_REQUIRED -> Color(0xFFEF4444)
                        else -> Color(0xFF334155)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (connectionStatus) {
                                            HidConnectionStatus.CONNECTED -> NeonCyan
                                            HidConnectionStatus.CONNECTING -> AmberGold
                                            HidConnectionStatus.PERMISSION_REQUIRED -> Color(0xFFEF4444)
                                            else -> Color(0xFF94A3B8)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when (connectionStatus) {
                                    HidConnectionStatus.CONNECTED -> "Bağlı: ${connectedDeviceName ?: "Bilgisayar"}"
                                    HidConnectionStatus.CONNECTING -> "Bağlantı Kuruluyor..."
                                    HidConnectionStatus.BLUETOOTH_OFF -> "Bluetooth Kapalı"
                                    HidConnectionStatus.PERMISSION_REQUIRED -> "Bluetooth İzni Gerekli"
                                    HidConnectionStatus.NOT_SUPPORTED -> "Sanal Test Modu (Emülatör)"
                                    HidConnectionStatus.DISCONNECTED -> "Bilgisayar Bağlantısı Yok"
                                },
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            )
                        }

                        if (connectionStatus == HidConnectionStatus.CONNECTED) {
                            OutlinedButton(
                                onClick = { manager.disconnectDevice() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Bağlantıyı Kes", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        } else if (connectionStatus == HidConnectionStatus.PERMISSION_REQUIRED) {
                            Button(
                                onClick = { onRequestBluetoothPermission?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF00382E)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("İzin Ver", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = { manager.refreshState() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = NeonCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Telefonunuzu veya yüzük barkod tarayıcı düzeneğinizi kablosuz Bluetooth klavye olarak kullanarak, taranan barkodları veya hesaplanan değişkenleri doğrudan bilgisayarınızdaki Excel, ERP veya form alanlarına tuşlayın.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Paired Device List
                    if (connectionStatus == HidConnectionStatus.DISCONNECTED && pairedDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Eşleşmiş Cihazlar (Bağlanmak için dokunun):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeonCyan
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            pairedDevices.forEach { dev ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF334155)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { manager.connectToDevice(dev.address) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Computer, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(dev.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text("Bağlan", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Quick Test Bar
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "HIZLI TEST TUŞ GÖNDERİMİ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = testInputText,
                            onValueChange = { testInputText = it },
                            label = { Text("Test Metni") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                if (testInputText.isNotBlank()) {
                                    isSendingTest = true
                                    coroutineScope.launch {
                                        manager.sendToComputer(
                                            textToSend = testInputText,
                                            endingKey = config.endingKey,
                                            typingDelayMs = config.typingDelayMs,
                                            convertTurkishChars = config.convertTurkishChars
                                        )
                                        isSendingTest = false
                                    }
                                }
                            },
                            enabled = !isSendingTest,
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Yaz")
                        }
                    }
                }
            }
        }

        // 3. Auto-Send Switch
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Barkod Okunduğunda Otomatik Gönder",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Yüzük barkod veya kamera kodu algıladığında bilgisayara otomatik tuşlar",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.isAutoSendEnabled,
                        onCheckedChange = { onConfigChanged(config.copy(isAutoSendEnabled = it)) }
                    )
                }
            }
        }

        // 4. Payload Options (Whole barcode vs variables)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "BİLGİSAYARA GÖNDERİLECEK VERİ BİÇİMİ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    KeyboardSendTarget.entries.forEach { target ->
                        val isSelected = config.sendTarget == target
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onConfigChanged(config.copy(sendTarget = target)) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onConfigChanged(config.copy(sendTarget = target)) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = target.label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = target.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Single variable selector
                    AnimatedVisibility(visible = config.sendTarget == KeyboardSendTarget.SINGLE_VARIABLE) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Text(
                                text = "Gönderilecek Değişkeni Seçin:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                availableVariables.forEach { variable ->
                                    val isChosen = config.selectedVariableName == variable.name ||
                                            (config.selectedVariableName.isBlank() && availableVariables.firstOrNull() == variable)
                                    FilterChip(
                                        selected = isChosen,
                                        onClick = { onConfigChanged(config.copy(selectedVariableName = variable.name)) },
                                        label = { Text(variable.name + if (variable.alias.isNotBlank()) " (${variable.alias})" else "") }
                                    )
                                }
                            }
                        }
                    }

                    // Multiple variables selector
                    AnimatedVisibility(visible = config.sendTarget == KeyboardSendTarget.MULTIPLE_VARIABLES) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            Text(
                                text = "Dahil Edilecek Değişkenler (Sırayla):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                availableVariables.forEach { variable ->
                                    val inList = if (config.selectedVariables.isEmpty()) true else config.selectedVariables.contains(variable.name)
                                    FilterChip(
                                        selected = inList,
                                        onClick = {
                                            val current = if (config.selectedVariables.isEmpty()) availableVariables.map { it.name }.toMutableList() else config.selectedVariables.toMutableList()
                                            if (inList) current.remove(variable.name) else current.add(variable.name)
                                            onConfigChanged(config.copy(selectedVariables = current))
                                        },
                                        label = { Text(variable.name) }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Değişkenler Arası Tuş (Ayırıcı):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "\t" to "Tab ⇥ (Excel Sütunu)",
                                    " " to "Boşluk",
                                    "," to "Virgül",
                                    ";" to "Noktalı Virgül"
                                ).forEach { (sep, label) ->
                                    val isSepSelected = config.variableSeparator == sep
                                    SuggestionChip(
                                        onClick = { onConfigChanged(config.copy(variableSeparator = sep)) },
                                        label = { Text(label, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Ending Key (Suffix / Terminator)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "BİTİŞ TUŞU (SONLANDIRICI)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Metin bilgisayara yazıldıktan sonra basılacak tuş:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    KeyboardEndingKey.entries.forEach { key ->
                        val isSelected = config.endingKey == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onConfigChanged(config.copy(endingKey = key)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onConfigChanged(config.copy(endingKey = key)) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = key.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // 6. Turkish Transliteration Toggle
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Türkçe Karakterleri Dönüştür (ç->c, ş->s vb.)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Yabancı US klavye düzenine sahip ERP ve terminallerde karakter kaybını engeller",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.convertTurkishChars,
                        onCheckedChange = { onConfigChanged(config.copy(convertTurkishChars = it)) }
                    )
                }
            }
        }

        // 7. Live Terminal Log
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF070B14)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CANLI İLETİM GÜNLÜĞÜ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                letterSpacing = 0.5.sp
                            )
                        }
                        if (recentLogs.isNotEmpty()) {
                            TextButton(onClick = { manager.clearLogs() }, contentPadding = PaddingValues(0.dp)) {
                                Text("Temizle", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }
                    }

                    if (recentLogs.isEmpty()) {
                        Text(
                            text = "Henüz bilgisayara tuş gönderimi yapılmadı.",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            recentLogs.take(5).forEach { log ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = sdf.format(Date(log.timestamp)),
                                            fontSize = 10.sp,
                                            color = Color(0xFF64748B),
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "\"${log.text}\"",
                                            fontSize = 12.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        text = if (log.isSimulated) "Sanal" else "İletildi",
                                        fontSize = 10.sp,
                                        color = if (log.isSimulated) Color(0xFFF59E0B) else NeonCyan
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
