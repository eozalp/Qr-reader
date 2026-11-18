package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.example.model.KeyboardLayoutType
import com.example.model.KeyboardSendTarget
import com.example.model.VariableDefinition
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothKeyboardSheet(
    hidManager: BluetoothHidManager,
    currentConfig: BluetoothKeyboardConfig,
    availableVariables: List<VariableDefinition>,
    onConfigChanged: (BluetoothKeyboardConfig) -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val connectionStatus by hidManager.connectionStatus.collectAsState()
    val connectedDeviceName by hidManager.connectedDeviceName.collectAsState()
    val connectionDiagnostic by hidManager.connectionDiagnostic.collectAsState()
    val recentLogs by hidManager.recentLogs.collectAsState()
    val pairedDevices by hidManager.pairedDevices.collectAsState()
    val batchSentMap by hidManager.batchSentMap.collectAsState()

    val isLocalImeEnabled = remember(context) {
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
        }.getOrDefault(false)
    }

    var testInputText by remember { mutableStateOf("ALVETAK-TEST-100") }
    var isSendingTest by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0B111E),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color(0xFF334155))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
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
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Bluetooth Klavye & PC Girişi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Barkodları ve değişkenleri bilgisayara tuşlayarak iletin",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = Color(0xFF94A3B8))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // 1. Connection Status Card
                item {
                    ConnectionStatusCard(
                        status = connectionStatus,
                        connectedName = connectedDeviceName,
                        diagnosticMessage = connectionDiagnostic,
                        pairedDevices = pairedDevices,
                        onConnectDevice = { hidManager.connectToDevice(it) },
                        onDisconnect = { hidManager.disconnectDevice() },
                        onRequestPermission = onRequestPermission,
                        onMakeDiscoverable = {
                            runCatching { context.startActivity(hidManager.makeDiscoverableIntent()) }
                        },
                        onOpenBtSettings = {
                            runCatching { context.startActivity(hidManager.openBluetoothSettingsIntent()) }
                        },
                        onRefresh = { hidManager.refreshState() }
                    )
                }

                // 1b. Local Phone IME Keyboard Card (Chrome / Mobile Forms)
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101C33)),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricIndigo.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "TELEFON İÇİ KLAVYE (CHROME / FORM)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isLocalImeEnabled) CyberLime.copy(alpha = 0.15f) else AmberGold.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isLocalImeEnabled) "ETKİN ✓" else "KURULUM GEREKLİ",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isLocalImeEnabled) CyberLime else AmberGold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Chrome veya Excel mobil uygulamasında form kutusuna dokunun, alttan klavye simgesiyle 'ALVETAK QR Klavye' seçin: altta kamera veya yerleşik Klasik Klavye açılır; Yerel ya da Bluetooth ile yazar.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("1. Ayarlardan Aç", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = {
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                        imm?.showInputMethodPicker()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricIndigo, contentColor = Color.White),
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("2. Klavyeyi Seç", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 2. Direct Keystroke Quick Test Bar
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "HIZLI TEST TUŞ GÖNDERİMİ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
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
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NeonCyan,
                                        unfocusedBorderColor = Color(0xFF334155),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    modifier = Modifier.weight(1f).testTag("bt_test_text_input")
                                )

                                Button(
                                    onClick = {
                                        if (testInputText.isNotBlank()) {
                                            isSendingTest = true
                                            coroutineScope.launch {
                                                hidManager.sendToComputer(
                                                    textToSend = testInputText,
                                                    endingKey = currentConfig.endingKey,
                                                    typingDelayMs = currentConfig.typingDelayMs,
                                                    convertTurkishChars = currentConfig.convertTurkishChars,
                                                    keyboardLayout = currentConfig.keyboardLayout
                                                )
                                                isSendingTest = false
                                            }
                                        }
                                    },
                                    enabled = !isSendingTest,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonCyan,
                                        contentColor = Color(0xFF00382E)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(52.dp).testTag("bt_send_test_btn")
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Yaz", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 3. Auto-Send Switch
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
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
                                    text = "Okunduğunda Otomatik Gönder",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Yüzük barkod veya kamera kodu algıladığı an bilgisayara yazar ve bitiş tuşuna basar",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Switch(
                                checked = currentConfig.isAutoSendEnabled,
                                onCheckedChange = {
                                    onConfigChanged(currentConfig.copy(isAutoSendEnabled = it))
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NeonCyan,
                                    checkedTrackColor = Color(0xFF00382E)
                                ),
                                modifier = Modifier.testTag("auto_send_switch")
                            )
                        }
                    }
                }

                // 4. Payload Target (What to Send: Whole Barcode vs Single Variable vs Multiple Variables)
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "BİLGİSAYARA GÖNDERİLECEK VERİ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            KeyboardSendTarget.entries.forEach { target ->
                                val isSelected = currentConfig.sendTarget == target
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) NeonCyan.copy(alpha = 0.12f) else Color(0xFF0E1624),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) NeonCyan else Color(0xFF1E293B)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onConfigChanged(currentConfig.copy(sendTarget = target))
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { onConfigChanged(currentConfig.copy(sendTarget = target)) },
                                            colors = RadioButtonDefaults.colors(selectedColor = NeonCyan)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = target.label,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) NeonCyan else Color.White,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = target.description,
                                                fontSize = 11.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }

                            // Sub-config for SINGLE_VARIABLE
                            AnimatedVisibility(visible = currentConfig.sendTarget == KeyboardSendTarget.SINGLE_VARIABLE) {
                                Column(modifier = Modifier.padding(top = 10.dp)) {
                                    Text(
                                        text = "Gönderilecek Değişkeni Seçin:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        availableVariables.forEach { variable ->
                                            val isChosen = currentConfig.selectedVariableName == variable.name ||
                                                    (currentConfig.selectedVariableName.isBlank() && availableVariables.firstOrNull() == variable)
                                            FilterChip(
                                                selected = isChosen,
                                                onClick = {
                                                    onConfigChanged(currentConfig.copy(selectedVariableName = variable.name))
                                                },
                                                label = {
                                                    Text(variable.name + if (variable.alias.isNotBlank()) " (${variable.alias})" else "")
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = NeonCyan,
                                                    selectedLabelColor = Color(0xFF00382E)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            // Sub-config for MULTIPLE_VARIABLES
                            AnimatedVisibility(visible = currentConfig.sendTarget == KeyboardSendTarget.MULTIPLE_VARIABLES) {
                                Column(modifier = Modifier.padding(top = 10.dp)) {
                                    Text(
                                        text = "Dahil Edilecek Değişkenler (Sırayla):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        availableVariables.forEach { variable ->
                                            val inList = if (currentConfig.selectedVariables.isEmpty()) true else currentConfig.selectedVariables.contains(variable.name)
                                            FilterChip(
                                                selected = inList,
                                                onClick = {
                                                    val current = if (currentConfig.selectedVariables.isEmpty()) availableVariables.map { it.name }.toMutableList() else currentConfig.selectedVariables.toMutableList()
                                                    if (inList) {
                                                        current.remove(variable.name)
                                                    } else {
                                                        current.add(variable.name)
                                                    }
                                                    onConfigChanged(currentConfig.copy(selectedVariables = current))
                                                },
                                                label = { Text(variable.name) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = NeonCyan,
                                                    selectedLabelColor = Color(0xFF00382E)
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Değişkenler Arası Tuş / Ayırıcı:",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            "\t" to "Tab ⇥ (Excel Sütunu)",
                                            " " to "Boşluk",
                                            "," to "Virgül",
                                            ";" to "Noktalı Virgül"
                                        ).forEach { (sep, label) ->
                                            val isSepSelected = currentConfig.variableSeparator == sep
                                            SuggestionChip(
                                                onClick = { onConfigChanged(currentConfig.copy(variableSeparator = sep)) },
                                                label = { Text(label, fontSize = 11.sp) },
                                                colors = SuggestionChipDefaults.suggestionChipColors(
                                                    containerColor = if (isSepSelected) NeonCyan.copy(alpha = 0.2f) else Color(0xFF0E1624),
                                                    labelColor = if (isSepSelected) NeonCyan else Color.White
                                                ),
                                                border = SuggestionChipDefaults.suggestionChipBorder(
                                                    enabled = true,
                                                    borderColor = if (isSepSelected) NeonCyan else Color(0xFF334155)
                                                )
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
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "BİTİŞ TUŞU (SONLANDIRICI)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Veri yazıldıktan hemen sonra basılacak tuş:",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            KeyboardEndingKey.entries.forEach { key ->
                                val isSelected = currentConfig.endingKey == key
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onConfigChanged(currentConfig.copy(endingKey = key)) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { onConfigChanged(currentConfig.copy(endingKey = key)) },
                                        colors = RadioButtonDefaults.colors(selectedColor = NeonCyan)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = key.label,
                                        fontSize = 13.sp,
                                        color = if (isSelected) NeonCyan else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. Batch Sayım Güvenliği, Klavye Düzeni (TR-Q / US-Q) & Yazım Hızı
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Batch Duplicate Guard
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Batch Sayım: Tekrarda Onay İste",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Aynı barkod bir kez gönderildikten sonra kilitlenir; tekrar okunursa onay ister (${batchSentMap.values.sumOf { it.count }} okuma)",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Switch(
                                    checked = currentConfig.requireDuplicateConfirmation,
                                    onCheckedChange = {
                                        onConfigChanged(currentConfig.copy(requireDuplicateConfirmation = it))
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                                )
                            }

                            if (batchSentMap.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { hidManager.resetBatchSession() },
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AmberGold, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Sayım Oturumunu Sıfırla (${batchSentMap.size} farklı SKU)",
                                        color = AmberGold,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            HorizontalDivider(color = Color(0xFF1E293B))

                            // Target PC Keyboard Layout
                            Column {
                                Text(
                                    text = "HEDEF BİLGİSAYAR KLAVYE DÜZENİ",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Nokta/tire ve Türkçe karakterlerin bozulmaması için bilgisayarınızın düzenini seçin:",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    KeyboardLayoutType.entries.forEach { layout ->
                                        FilterChip(
                                            selected = currentConfig.keyboardLayout == layout,
                                            onClick = {
                                                onConfigChanged(
                                                    currentConfig.copy(
                                                        keyboardLayout = layout,
                                                        convertTurkishChars = (layout == KeyboardLayoutType.US_Q)
                                                    )
                                                )
                                            },
                                            label = { Text(layout.label, fontSize = 12.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NeonCyan,
                                                selectedLabelColor = Color(0xFF00382E)
                                            )
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = Color(0xFF1E293B))

                            // Typing Speed Presets
                            Column {
                                Text(
                                    text = "TUŞ YAZIM HIZI",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        12L to "Hızlı (12ms)",
                                        16L to "Dengeli (16ms)",
                                        24L to "Güvenli Eski PC (24ms)"
                                    ).forEach { (ms, label) ->
                                        FilterChip(
                                            selected = currentConfig.typingDelayMs == ms,
                                            onClick = { onConfigChanged(currentConfig.copy(typingDelayMs = ms)) },
                                            label = { Text(label, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NeonCyan,
                                                selectedLabelColor = Color(0xFF00382E)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 7. Live Keystroke Transmission Terminal Log
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
                                    TextButton(onClick = { hidManager.clearLogs() }, contentPadding = PaddingValues(0.dp)) {
                                        Text("Temizle", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                            }

                            if (recentLogs.isEmpty()) {
                                Text(
                                    text = "Henüz bilgisayara veri gönderilmedi.",
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
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = NeonCyan.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        text = when (log.endingKey) {
                                                            KeyboardEndingKey.ENTER -> "↵ ENTER"
                                                            KeyboardEndingKey.TAB -> "⇥ TAB"
                                                            KeyboardEndingKey.TAB_THEN_ENTER -> "⇥ TAB+↵"
                                                            KeyboardEndingKey.SPACE -> "SPACE"
                                                            KeyboardEndingKey.NONE -> "METİN"
                                                        },
                                                        fontSize = 9.sp,
                                                        color = NeonCyan,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
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

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = Color(0xFF00382E)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("bt_sheet_done_btn")
            ) {
                Text("Tamam", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    status: HidConnectionStatus,
    connectedName: String?,
    diagnosticMessage: String?,
    pairedDevices: List<com.example.bluetooth.PairedBluetoothDevice>,
    onConnectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermission: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onOpenBtSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (status) {
                HidConnectionStatus.CONNECTED -> Color(0xFF062822)
                HidConnectionStatus.CONNECTING -> Color(0xFF2E2606)
                HidConnectionStatus.PERMISSION_REQUIRED -> Color(0xFF2E0606)
                else -> Color(0xFF131D2E)
            }
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when (status) {
                HidConnectionStatus.CONNECTED -> NeonCyan
                HidConnectionStatus.CONNECTING -> AmberGold
                HidConnectionStatus.PERMISSION_REQUIRED -> Color(0xFFEF4444)
                else -> Color(0xFF334155)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                                when (status) {
                                    HidConnectionStatus.CONNECTED -> NeonCyan
                                    HidConnectionStatus.CONNECTING -> AmberGold
                                    HidConnectionStatus.PERMISSION_REQUIRED -> Color(0xFFEF4444)
                                    else -> Color(0xFF94A3B8)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (status) {
                            HidConnectionStatus.CONNECTED -> "Bağlı: ${connectedName ?: "Bilgisayar"}"
                            HidConnectionStatus.CONNECTING -> "Bağlanıyor..."
                            HidConnectionStatus.BLUETOOTH_OFF -> "Bluetooth Kapalı"
                            HidConnectionStatus.PERMISSION_REQUIRED -> "Bluetooth İzni Bekleniyor"
                            HidConnectionStatus.NOT_SUPPORTED -> "Sanal Test Modu (Emülatör)"
                            HidConnectionStatus.DISCONNECTED -> "Bilgisayar Bağlantısı Yok"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }

                if (status == HidConnectionStatus.CONNECTED) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Bağlantıyı Kes", fontSize = 11.sp, color = Color(0xFFEF4444))
                    }
                } else if (status == HidConnectionStatus.PERMISSION_REQUIRED) {
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF00382E)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("İzin Ver", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (!diagnosticMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = diagnosticMessage,
                    fontSize = 11.sp,
                    color = AmberGold
                )
            }

            // Show paired PC list & pairing helpers if disconnected or connecting
            if (status == HidConnectionStatus.DISCONNECTED || status == HidConnectionStatus.CONNECTING) {
                if (pairedDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Eşleşmiş Bilgisayarlar (Bağlanmak için dokunun):",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        pairedDevices.take(4).forEach { dev ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0B111E))
                                    .clickable { onConnectDevice(dev.address) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Computer, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(dev.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Text("Bağlan", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onMakeDiscoverable,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Keşfedilebilir Yap", fontSize = 11.sp, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = onOpenBtSettings,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.SettingsBluetooth, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("BT Ayarları", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
