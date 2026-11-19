package com.example.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bluetooth.BluetoothHidManager
import com.example.bluetooth.HidConnectionStatus
import com.example.debug.AppDebugLogger
import com.example.model.BluetoothKeyboardConfig
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardLayoutType
import com.example.model.KeyboardSendTarget
import com.example.model.VariableDefinition
import com.example.ui.theme.AmberGold
import com.example.ui.theme.CyberLime
import com.example.ui.theme.ElectricIndigo
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
    onOpenRemoteControl: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val manager = remember(context) { hidManager ?: BluetoothHidManager.getInstance(context) }

    val connectionStatus by manager.connectionStatus.collectAsState()
    val connectedDeviceName by manager.connectedDeviceName.collectAsState()
    val connectionDiagnostic by manager.connectionDiagnostic.collectAsState()
    val recentLogs by manager.recentLogs.collectAsState()
    val pairedDevices by manager.pairedDevices.collectAsState()
    val batchSentMap by manager.batchSentMap.collectAsState()
    val telemetry by manager.telemetry.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    fun checkImeEnabled(): Boolean = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    }.getOrDefault(false)

    var isLocalImeEnabled by remember(context) { mutableStateOf(checkImeEnabled()) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isLocalImeEnabled = checkImeEnabled()
                manager.refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var testInputText by remember { mutableStateOf("ALVETAK-PC-TEST") }
    var isSendingTest by remember { mutableStateOf(false) }

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
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                Text("Bağlantıyı Kes", fontSize = 11.5.sp, color = Color(0xFFEF4444))
                            }
                        } else if (connectionStatus == HidConnectionStatus.PERMISSION_REQUIRED) {
                            Button(
                                onClick = { onRequestBluetoothPermission?.invoke() },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF00382E)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Text("İzin Ver", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            IconButton(onClick = { manager.refreshState() }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = NeonCyan, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Telefonunuzu veya yüzük barkod tarayıcı düzeneğinizi kablosuz Bluetooth klavye olarak kullanarak, taranan barkodları veya hesaplanan değişkenleri doğrudan bilgisayarınızdaki Excel, ERP veya form alanlarına tuşlayın.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!connectionDiagnostic.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ℹ $connectionDiagnostic",
                            fontSize = 11.sp,
                            color = AmberGold,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (connectionStatus == HidConnectionStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0A1622),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (telemetry.lastFailureReason != null) Color(0xFFEF4444).copy(alpha = 0.7f) else Color(0xFF1E3A3A)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "HID Modu: ${if (telemetry.isBootMode) "Boot Modu" else "Report Modu"} · ${telemetry.qosLabel}",
                                        fontSize = 10.5.sp,
                                        color = NeonCyan,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedButton(
                                        onClick = { manager.reregisterHidProfile() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .testTag("settings_hid_reregister_btn")
                                    ) {
                                        Text("HID Yenile", fontSize = 10.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(
                                    text = "Klavye TX: ${telemetry.keyboardReportsSent} (Hata: ${telemetry.keyboardReportsFailed}) · Mouse TX: ${telemetry.mouseReportsSent} (Hata: ${telemetry.mouseReportsFailed})",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (!telemetry.lastFailureReason.isNullOrBlank()) {
                                    Text(
                                        text = "⚠ ${telemetry.lastFailureReason}",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "Mouse/medya tuşları yanıt vermiyorsa PC Bluetooth'tan cihazı 'Kaldır' deyip bir kez yeniden eşleştirin.",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    if (connectionStatus == HidConnectionStatus.DISCONNECTED || connectionStatus == HidConnectionStatus.CONNECTING) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    runCatching { context.startActivity(manager.makeDiscoverableIntent()) }
                                        .onFailure { err ->
                                            AppDebugLogger.e("BT-UI", "BT görünür yapma ekranı açılamadı", err)
                                            Toast.makeText(context, "BT ekranı açılamadı: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Görünür Yap (PC)", fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching { context.startActivity(manager.openBluetoothSettingsIntent()) }
                                        .onFailure { err ->
                                            AppDebugLogger.e("BT-UI", "BT ayarları açılamadı", err)
                                            Toast.makeText(context, "BT ayarları açılamadı: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.SettingsBluetooth, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("BT Ayarları", fontSize = 11.sp, maxLines = 1)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "İpucu: İlk kez bağlıyorsanız veya bağlantı kopuyorsa PC Bluetooth ayarlarından cihazı 'Kaldır' deyip telefonla yeniden eşleştirin.",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

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
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
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

        // 1a. Full PC Mouse, Air Mouse & Full Keyboard Launcher
        if (onOpenRemoteControl != null) {
            item {
                Button(
                    onClick = onOpenRemoteControl,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF00382E)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("settings_open_remote_btn")
                ) {
                    Icon(Icons.Default.Mouse, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tam Klavye, Mouse & Air Mouse Kumandası Aç",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 1b. Local Phone IME Keyboard Card (Chrome / Mobile Forms)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
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
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isLocalImeEnabled) CyberLime.copy(alpha = 0.2f) else AmberGold.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = if (isLocalImeEnabled) "ETKİN ✓" else "KURULUM",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLocalImeEnabled) CyberLime else AmberGold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Chrome, ERP veya Excel mobil formunda klavye simgesine dokunup 'ALVETAK QR Klavye'yi seçin: altta kamera veya yerleşik Klasik Klavye açılır; Yerel ya da Bluetooth ile doğrudan yazar.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                }.onFailure { err ->
                                    AppDebugLogger.e("IME-UI", "Klavye ayarları açılamadı", err)
                                    Toast.makeText(context, "Klavye ayarları açılamadı: ${err.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("1. Ayarlardan Aç", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                runCatching {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                    imm?.showInputMethodPicker()
                                }.onFailure { err ->
                                    AppDebugLogger.e("IME-UI", "Klavye seçici açılamadı", err)
                                }
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
                                        val ok = manager.sendToComputer(
                                            textToSend = testInputText,
                                            rawBarcodeKey = testInputText,
                                            endingKey = config.endingKey,
                                            typingDelayMs = config.typingDelayMs,
                                            convertTurkishChars = config.convertTurkishChars,
                                            keyboardLayout = config.keyboardLayout
                                        )
                                        isSendingTest = false
                                        val msg = when {
                                            !ok -> "BT gönderim hatası — Bağlantıyı kontrol edin"
                                            manager.lastSendWasSimulated -> "Sanal Test kaydı eklendi (PC BT bağlı değil)"
                                            else -> "Bilgisayara gönderildi ✓"
                                        }
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "\t" to "Tab ⇥ (Excel Sütunu)",
                                    " " to "Boşluk",
                                    "," to "Virgül",
                                    ";" to "Noktalı Virgül"
                                ).forEach { (sep, label) ->
                                    val isSepSelected = config.variableSeparator == sep
                                    FilterChip(
                                        selected = isSepSelected,
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

        // 6. Batch Sayım Güvenliği, Klavye Düzeni (TR-Q / US-Q) & Yazım Hızı
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
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
                                text = "Art Arda Aynı Barkodda Onay İste",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Aynı barkodun yanlışlıkla arka arkaya okunmasını önler; arada başka kod okununca veya uzakta tekrar gelince uyarmaz (${batchSentMap.values.sumOf { it.count }} okuma)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.requireDuplicateConfirmation,
                            onCheckedChange = { onConfigChanged(config.copy(requireDuplicateConfirmation = it)) }
                        )
                    }

                    if (batchSentMap.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { manager.resetBatchSession() },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
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

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Target PC Keyboard Layout
                    Column {
                        Text(
                            text = "HEDEF BİLGİSAYAR KLAVYE DÜZENİ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Nokta/tire ve Türkçe karakterlerin bozulmaması için bilgisayarınızın düzenini seçin:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeyboardLayoutType.entries.forEach { layout ->
                                FilterChip(
                                    selected = config.keyboardLayout == layout,
                                    onClick = {
                                        onConfigChanged(
                                            config.copy(
                                                keyboardLayout = layout,
                                                convertTurkishChars = (layout == KeyboardLayoutType.US_Q)
                                            )
                                        )
                                    },
                                    label = { Text(layout.label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Typing Speed Presets
                    Column {
                        Text(
                            text = "TUŞ YAZIM HIZI (TURBO / DÜŞÜK GECİKME)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                12L to "Turbo (12ms)",
                                16L to "Hızlı (16ms)",
                                22L to "Dengeli (22ms)",
                                35L to "Eski PC (35ms)"
                            ).forEach { (ms, label) ->
                                FilterChip(
                                    selected = config.typingDelayMs == ms,
                                    onClick = { onConfigChanged(config.copy(typingDelayMs = ms)) },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Turbo (12ms) ~45 krk/sn, Hızlı (14–16ms) ~35–40 krk/sn hızında 11.25ms Bluetooth yuvasıyla uyumlu ve tekrarsız yazar.",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
