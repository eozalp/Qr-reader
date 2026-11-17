package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.model.BluetoothKeyboardConfig
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardLayoutType
import com.example.model.KeyboardSendTarget
import com.example.model.ParsedQrResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

enum class HidConnectionStatus(val label: String) {
    DISCONNECTED("Bağlantı Yok"),
    CONNECTING("Bağlanıyor..."),
    CONNECTED("Bilgisayara Bağlı"),
    BLUETOOTH_OFF("Bluetooth Kapalı"),
    PERMISSION_REQUIRED("Bluetooth İzni Gerekli"),
    NOT_SUPPORTED("HID Desteklenmiyor (Sanal Test Modu)")
}

data class KeystrokeLogItem(
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val endingKey: KeyboardEndingKey,
    val isSimulated: Boolean,
    val statusMessage: String,
    val deliveryConfirmed: Boolean = true
)

data class PairedBluetoothDevice(
    val name: String,
    val address: String,
    val isConnected: Boolean
)

data class BatchSentEntry(
    val rawBarcode: String,
    val payload: String,
    val count: Int,
    val lastSentTimestamp: Long
)

sealed class AutoSendDecision {
    object SkipDisabled : AutoSendDecision()
    object SkipAlreadyActive : AutoSendDecision()
    object SendNow : AutoSendDecision()
    data class RequireConfirmation(val previousEntry: BatchSentEntry) : AutoSendDecision()
}

/**
 * Reliable, Mutex-serialized Bluetooth HID Keyboard emulator and Batch Inventory engine.
 * Fixes concurrent key interleaving ("spam gibberish"), Turkish-Q vs US-Q scancode mismatches,
 * L2CAP QoS connection rejections, and enforces reliable batch inventory duplicate guards.
 */
class BluetoothHidManager(private val context: Context? = null) {

    private val bluetoothManager: BluetoothManager? =
        context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDeviceProfile: BluetoothHidDevice? = null
    private var connectedHostDevice: BluetoothDevice? = null
    private var isAppRegistered: Boolean = false

    private val _connectionStatus = MutableStateFlow(HidConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<HidConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectionDiagnostic = MutableStateFlow<String?>(null)
    val connectionDiagnostic: StateFlow<String?> = _connectionDiagnostic.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<KeystrokeLogItem>>(emptyList())
    val recentLogs: StateFlow<List<KeystrokeLogItem>> = _recentLogs.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedBluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedBluetoothDevice>> = _pairedDevices.asStateFlow()

    private val _batchSentMap = MutableStateFlow<Map<String, BatchSentEntry>>(emptyMap())
    val batchSentMap: StateFlow<Map<String, BatchSentEntry>> = _batchSentMap.asStateFlow()

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private val sendMutex = Mutex()

    // Track barcodes currently present in camera frame / pending confirmation to prevent frame-by-frame spam
    private val activeInFrameBarcodes = mutableSetOf<String>()
    private val lastSeenInFrameMs = mutableMapOf<String, Long>()
    private val dismissedUntilLeftFrame = mutableSetOf<String>()
    private var lastSentTimestamp: Long = 0L
    private var lastSentRawBarcode: String = ""

    init {
        refreshState()
        initHidProfile()
    }

    @SuppressLint("MissingPermission")
    fun refreshState() {
        if (bluetoothAdapter == null) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _connectionStatus.value = HidConnectionStatus.BLUETOOTH_OFF
            _connectionDiagnostic.value = "Bluetooth kapalı. Ayarlardan Bluetooth'u açın."
            return
        }
        if (!hasBluetoothPermissions()) {
            _connectionStatus.value = HidConnectionStatus.PERMISSION_REQUIRED
            _connectionDiagnostic.value = "Bluetooth bağlantı izni verilmedi."
            return
        }

        // Sync active HID connections if system already connected a host
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDeviceProfile != null) {
            try {
                val activeHosts = hidDeviceProfile?.getDevicesMatchingConnectionStates(
                    intArrayOf(BluetoothProfile.STATE_CONNECTED)
                )
                val firstConnected = activeHosts?.firstOrNull()
                if (firstConnected != null) {
                    connectedHostDevice = firstConnected
                    _connectedDeviceName.value = getDeviceDisplayName(firstConnected)
                }
            } catch (_: Exception) {}
        }

        if (connectedHostDevice != null) {
            _connectionStatus.value = HidConnectionStatus.CONNECTED
            _connectionDiagnostic.value = null
        } else {
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            if (hidDeviceProfile == null) {
                initHidProfile()
            } else if (!isAppRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                registerHidApp(hidDeviceProfile!!)
            }
        }
        refreshPairedDevices()
    }

    fun hasBluetoothPermissions(): Boolean {
        val ctx = context ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun refreshPairedDevices() {
        if (!hasBluetoothPermissions() || bluetoothAdapter == null) return
        try {
            val bonded = bluetoothAdapter.bondedDevices ?: emptySet()
            _pairedDevices.value = bonded
                .sortedByDescending { connectedHostDevice?.address == it.address }
                .map { dev ->
                    PairedBluetoothDevice(
                        name = dev.name ?: dev.address,
                        address = dev.address,
                        isConnected = connectedHostDevice?.address == dev.address
                    )
                }
        } catch (_: Exception) {}
    }

    private fun initHidProfile() {
        val ctx = context
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || bluetoothAdapter == null) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            return
        }

        try {
            bluetoothAdapter.getProfileProxy(
                ctx,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                            hidDeviceProfile = proxy
                            registerHidApp(proxy)
                            refreshState()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HID_DEVICE) {
                            hidDeviceProfile = null
                            isAppRegistered = false
                            connectedHostDevice = null
                            _connectedDeviceName.value = null
                            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                        }
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
        } catch (e: Exception) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            _connectionDiagnostic.value = "HID servisi başlatılamadı: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp(hid: BluetoothHidDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasBluetoothPermissions()) return

        try {
            // Re-register cleanly if needed
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "ALVETAK QR Klavye",
                "ALVETAK Kablosuz Barkod Klavyesi",
                "ALVETAK",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                HID_KEYBOARD_REPORT_DESCRIPTOR
            )

            // Passing null for inQos and outQos lets Windows/macOS/Linux negotiate default L2CAP HID parameters
            // smoothly instead of rejecting strict bandwidth constraints.
            val started = hid.registerApp(sdp, null, null, executor, hidCallback)
            if (!started) {
                _connectionDiagnostic.value = "HID profili kaydedilemedi. Bilgisayardan eşleştirmeyi kaldırıp tekrar deneyin."
            }
        } catch (e: Exception) {
            _connectionDiagnostic.value = "HID kayıt hatası: ${e.message}"
        }
    }

    private val hidCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                isAppRegistered = registered
                if (registered) {
                    _connectionDiagnostic.value = null
                    if (pluggedDevice != null) {
                        connectedHostDevice = pluggedDevice
                        _connectedDeviceName.value = getDeviceDisplayName(pluggedDevice)
                        _connectionStatus.value = HidConnectionStatus.CONNECTED
                        refreshPairedDevices()
                    }
                } else {
                    isAppRegistered = false
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedHostDevice = device
                        _connectedDeviceName.value = getDeviceDisplayName(device)
                        _connectionStatus.value = HidConnectionStatus.CONNECTED
                        _connectionDiagnostic.value = null
                        refreshPairedDevices()
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionStatus.value = HidConnectionStatus.CONNECTING
                        _connectionDiagnostic.value = "Bilgisayarla HID el sıkışması yapılıyor..."
                    }
                    BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                        if (connectedHostDevice?.address == device?.address || connectedHostDevice == null) {
                            connectedHostDevice = null
                            _connectedDeviceName.value = null
                            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                            refreshPairedDevices()
                        }
                    }
                }
            }
        }
    } else null

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || hidDeviceProfile == null) return
        if (!hasBluetoothPermissions()) {
            _connectionStatus.value = HidConnectionStatus.PERMISSION_REQUIRED
            return
        }

        val hid = hidDeviceProfile ?: return
        try {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
            _connectionStatus.value = HidConnectionStatus.CONNECTING
            _connectionDiagnostic.value = "${getDeviceDisplayName(device)} cihazına bağlanılıyor..."

            // Disconnect any previous host first
            connectedHostDevice?.let { prev ->
                if (prev.address != address) {
                    runCatching { hid.disconnect(prev) }
                }
            }

            if (!isAppRegistered) {
                registerHidApp(hid)
            }

            val initiated = hid.connect(device)
            if (!initiated) {
                _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                _connectionDiagnostic.value =
                    "Bağlantı başlatılamadı. Bilgisayar Bluetooth ayarlarından telefonunuzu kaldırıp 'Keşfedilebilir Yap' ile tekrar eşleştirin veya bilgisayardan 'Bağlan' deyin."
            }
        } catch (e: Exception) {
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            _connectionDiagnostic.value = "Bağlantı hatası: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || hidDeviceProfile == null) return
        if (!hasBluetoothPermissions()) return

        try {
            connectedHostDevice?.let { dev ->
                hidDeviceProfile?.disconnect(dev)
            }
            connectedHostDevice = null
            _connectedDeviceName.value = null
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            _connectionDiagnostic.value = null
            refreshPairedDevices()
        } catch (_: Exception) {}
    }

    fun makeDiscoverableIntent(durationSeconds: Int = 180): Intent {
        return Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)
        }
    }

    fun openBluetoothSettingsIntent(): Intent {
        return Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceDisplayName(device: BluetoothDevice?): String {
        if (device == null) return "Bilinmeyen Cihaz"
        return try {
            device.name ?: device.address
        } catch (_: Exception) {
            device.address
        }
    }

    /**
     * Resolves the string payload to send based on user's configuration.
     */
    fun resolvePayload(
        rawBarcode: String,
        status: com.example.model.CalculationStatus?,
        variableValues: Map<String, String>,
        config: BluetoothKeyboardConfig
    ): String {
        val basePayload = when (config.sendTarget) {
            KeyboardSendTarget.WHOLE_RAW -> rawBarcode

            KeyboardSendTarget.FORMULA_RESULT -> {
                when (status) {
                    is com.example.model.CalculationStatus.Success -> status.formattedText
                    else -> rawBarcode
                }
            }

            KeyboardSendTarget.SINGLE_VARIABLE -> {
                val targetVar = config.selectedVariableName.ifBlank {
                    variableValues.keys.firstOrNull() ?: ""
                }
                variableValues[targetVar]
                    ?: variableValues.entries.find { it.key.equals(targetVar, ignoreCase = true) }?.value
                    ?: rawBarcode
            }

            KeyboardSendTarget.MULTIPLE_VARIABLES -> {
                val targets = if (config.selectedVariables.isNotEmpty()) {
                    config.selectedVariables
                } else {
                    variableValues.keys.toList()
                }
                val parts = targets.mapNotNull { varName ->
                    variableValues[varName] ?: variableValues.entries.find { it.key.equals(varName, ignoreCase = true) }?.value
                }
                if (parts.isNotEmpty()) {
                    val sep = if (config.variableSeparator == "\\t") "\t" else config.variableSeparator
                    parts.joinToString(separator = sep)
                } else {
                    rawBarcode
                }
            }

            KeyboardSendTarget.CUSTOM_TEMPLATE -> {
                var templated = config.customTemplate
                for ((k, v) in variableValues) {
                    templated = templated.replace("{$k}", v)
                }
                if (status is com.example.model.CalculationStatus.Success) {
                    templated = templated.replace("{result}", status.formattedText)
                    templated = templated.replace("{sayi}", status.resultNumber.toString())
                }
                templated = templated.replace("\\t", "\t").replace("\\n", "\n")
                templated
            }
        }

        return config.prefixText + basePayload + config.postfixText
    }

    /**
     * Convenience overload accepting a [ParsedQrResult] directly.
     */
    fun resolvePayload(
        result: ParsedQrResult,
        config: BluetoothKeyboardConfig
    ): String {
        val combined = mutableMapOf<String, String>()
        result.extractedVariables.forEach { (k, v) ->
            combined[k] = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        }
        result.extractedStrings.forEach { (k, v) ->
            combined[k] = v
        }
        result.variablePillDisplay.forEach { (k, v) ->
            combined[k] = v
        }
        return resolvePayload(
            rawBarcode = result.rawText,
            status = result.status,
            variableValues = combined,
            config = config
        )
    }

    /**
     * Synchronizes barcodes currently visible in the camera frame.
     * Barcodes must disappear for > 1200ms before their "in-frame lock" is cleared,
     * preventing brief camera focus flicker from re-triggering sends.
     */
    fun syncVisibleBarcodes(visibleRawBarcodes: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        // First, any barcode (even one now re-appearing!) that hasn't been seen for >1200ms
        // has cleanly left the camera frame and must be unlocked so it can trigger RequireConfirmation.
        val toUnlock = activeInFrameBarcodes.filter { raw ->
            val prevSeen = lastSeenInFrameMs[raw] ?: 0L
            (raw !in visibleRawBarcodes && (nowMs - prevSeen) > 1200L) ||
                (raw in visibleRawBarcodes && (nowMs - prevSeen) > 1200L)
        }
        for (raw in toUnlock) {
            activeInFrameBarcodes.remove(raw)
            dismissedUntilLeftFrame.remove(raw)
        }
        val toUndismiss = dismissedUntilLeftFrame.filter { raw ->
            val prevSeen = lastSeenInFrameMs[raw] ?: 0L
            (nowMs - prevSeen) > 1200L
        }
        dismissedUntilLeftFrame.removeAll(toUndismiss.toSet())

        for (raw in visibleRawBarcodes) {
            lastSeenInFrameMs[raw] = nowMs
        }
    }

    /**
     * Batch-safe Auto-Send evaluation using the RAW barcode identifier (not resolved payload!).
     * - Prevents same-frame / continuous spam while barcode stays in view.
     * - Once sent and acknowledged, locks while in frame.
     * - When scanned again after leaving view, asks for confirmation if `requireDuplicateConfirmation` is enabled.
     */
    fun evaluateAutoSend(
        rawBarcode: String,
        config: BluetoothKeyboardConfig,
        nowMs: Long = System.currentTimeMillis()
    ): AutoSendDecision {
        if (!config.isAutoSendEnabled) return AutoSendDecision.SkipDisabled
        if (sendMutex.isLocked || _isTransmitting.value) return AutoSendDecision.SkipAlreadyActive
        if (rawBarcode in activeInFrameBarcodes || rawBarcode in dismissedUntilLeftFrame) {
            return AutoSendDecision.SkipAlreadyActive
        }

        val existingEntry = _batchSentMap.value[rawBarcode]
        if (existingEntry != null) {
            // Respect cooldown before even asking for confirmation
            if ((nowMs - existingEntry.lastSentTimestamp) < config.sendCooldownMs) {
                return AutoSendDecision.SkipAlreadyActive
            }
            return if (config.requireDuplicateConfirmation) {
                AutoSendDecision.RequireConfirmation(existingEntry)
            } else {
                AutoSendDecision.SendNow
            }
        }

        return AutoSendDecision.SendNow
    }

    /**
     * Backwards-compatible helper used by unit tests and callers checking simple cooldown eligibility.
     */
    fun shouldAutoSend(barcode: String, config: BluetoothKeyboardConfig): Boolean {
        return evaluateAutoSend(barcode, config) == AutoSendDecision.SendNow
    }

    fun markBarcodeInFrame(rawBarcode: String, nowMs: Long = System.currentTimeMillis()) {
        activeInFrameBarcodes.add(rawBarcode)
        lastSeenInFrameMs[rawBarcode] = nowMs
    }

    fun dismissDuplicateForCurrentFrame(rawBarcode: String, nowMs: Long = System.currentTimeMillis()) {
        dismissedUntilLeftFrame.add(rawBarcode)
        activeInFrameBarcodes.add(rawBarcode)
        lastSeenInFrameMs[rawBarcode] = nowMs
    }

    /**
     * Records a successful barcode transmission in the batch inventory count map.
     */
    fun recordSentBarcode(
        rawBarcode: String,
        payload: String = rawBarcode,
        nowMs: Long = System.currentTimeMillis()
    ) {
        lastSentRawBarcode = rawBarcode
        lastSentTimestamp = nowMs
        activeInFrameBarcodes.add(rawBarcode)
        lastSeenInFrameMs[rawBarcode] = nowMs

        val current = _batchSentMap.value.toMutableMap()
        val prev = current[rawBarcode]
        val newCount = (prev?.count ?: 0) + 1
        current[rawBarcode] = BatchSentEntry(
            rawBarcode = rawBarcode,
            payload = payload,
            count = newCount,
            lastSentTimestamp = nowMs
        )
        _batchSentMap.value = current
    }

    fun resetBatchSession() {
        _batchSentMap.value = emptyMap()
        activeInFrameBarcodes.clear()
        dismissedUntilLeftFrame.clear()
        lastSentRawBarcode = ""
        lastSentTimestamp = 0L
    }

    /**
     * Transmits the payload sequentially using a Mutex lock so reports never overlap or produce gibberish.
     * Supports Turkish-Q and US-Q keyboard scancodes and updates batch ACK counter upon confirmed delivery.
     */
    suspend fun sendToComputer(
        textToSend: String,
        rawBarcodeKey: String = textToSend,
        endingKey: KeyboardEndingKey = KeyboardEndingKey.ENTER,
        typingDelayMs: Long = 5L,
        convertTurkishChars: Boolean = false,
        keyboardLayout: KeyboardLayoutType = KeyboardLayoutType.TR_Q
    ): Boolean = sendMutex.withLock {
        withContext(Dispatchers.IO) {
            _isTransmitting.value = true
            try {
                // Immediately lock raw barcode in-frame to avoid duplicate triggers while transmitting
                markBarcodeInFrame(rawBarcodeKey)

                val effectiveConvert = convertTurkishChars || keyboardLayout == KeyboardLayoutType.US_Q
                val sanitizedText = if (effectiveConvert) {
                    transliterateTurkish(textToSend)
                } else {
                    textToSend.replace("₺", "TL")
                }

                val isRealHidConnected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        hidDeviceProfile != null &&
                        connectedHostDevice != null

                if (isRealHidConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val success = sendOverBluetoothHid(
                        text = sanitizedText,
                        endingKey = endingKey,
                        typingDelayMs = typingDelayMs,
                        layout = keyboardLayout
                    )
                    if (success) {
                        recordSentBarcode(rawBarcodeKey, sanitizedText)
                    }
                    val countNow = _batchSentMap.value[rawBarcodeKey]?.count ?: 1
                    val logMsg = if (success) "PC'ye iletildi ✓ (Sayım: #$countNow)" else "Gönderim hatası — Bağlantıyı kontrol edin"
                    addLog(
                        KeystrokeLogItem(
                            text = sanitizedText,
                            endingKey = endingKey,
                            isSimulated = false,
                            statusMessage = logMsg,
                            deliveryConfirmed = success
                        )
                    )
                    return@withContext success
                } else {
                    // Virtual / Simulation Mode (Emulators or no PC paired)
                    delay(20)
                    recordSentBarcode(rawBarcodeKey, sanitizedText)
                    val countNow = _batchSentMap.value[rawBarcodeKey]?.count ?: 1
                    addLog(
                        KeystrokeLogItem(
                            text = sanitizedText,
                            endingKey = endingKey,
                            isSimulated = true,
                            statusMessage = "Sanal Tuş Simülasyonu ✓ (Sayım: #$countNow)",
                            deliveryConfirmed = true
                        )
                    )
                    return@withContext true
                }
            } finally {
                _isTransmitting.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendOverBluetoothHid(
        text: String,
        endingKey: KeyboardEndingKey,
        typingDelayMs: Long,
        layout: KeyboardLayoutType
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val hid = hidDeviceProfile ?: return false
        val dev = connectedHostDevice ?: return false

        val holdMs = typingDelayMs.coerceIn(2L, 25L)
        val gapMs = typingDelayMs.coerceIn(2L, 25L)

        try {
            // Ensure clean modifier/key release before starting sequence
            sendReportWithRetry(hid, dev, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
            delay(4L)

            for (char in text) {
                when (char) {
                    '\t' -> {
                        if (!sendKeyStroke(hid, dev, 0x2B.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                    }
                    '\n' -> {
                        if (!sendKeyStroke(hid, dev, 0x28.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                    }
                    else -> {
                        val keyInfo = charToHidKey(char, layout)
                        if (keyInfo != null) {
                            if (!sendKeyStroke(hid, dev, keyInfo.keyCode, keyInfo.modifier, holdMs, gapMs)) return false
                        }
                    }
                }
            }

            // Send Suffix / Terminator Key
            when (endingKey) {
                KeyboardEndingKey.ENTER -> {
                    if (!sendKeyStroke(hid, dev, 0x28.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                }
                KeyboardEndingKey.TAB -> {
                    if (!sendKeyStroke(hid, dev, 0x2B.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                }
                KeyboardEndingKey.TAB_THEN_ENTER -> {
                    if (!sendKeyStroke(hid, dev, 0x2B.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                    delay(gapMs)
                    if (!sendKeyStroke(hid, dev, 0x28.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                }
                KeyboardEndingKey.SPACE -> {
                    if (!sendKeyStroke(hid, dev, 0x2C.toByte(), MODIFIER_NONE, holdMs, gapMs)) return false
                }
                KeyboardEndingKey.NONE -> {
                    // No suffix
                }
            }

            // Explicit final release report for safety
            sendReportWithRetry(hid, dev, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
            return true
        } catch (_: Exception) {
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendReportWithRetry(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        report: ByteArray
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        repeat(3) { attempt ->
            if (hid.sendReport(device, 1, report)) {
                return true
            }
            delay(4L * (attempt + 1))
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendKeyStroke(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        keyCode: Byte,
        modifier: Byte,
        holdMs: Long,
        gapMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val reportDown = byteArrayOf(modifier, 0x00, keyCode, 0x00, 0x00, 0x00, 0x00, 0x00)
        val reportUp = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        if (!sendReportWithRetry(hid, device, reportDown)) return false
        delay(holdMs)
        if (!sendReportWithRetry(hid, device, reportUp)) return false
        delay(gapMs)
        return true
    }

    private fun addLog(item: KeystrokeLogItem) {
        val current = _recentLogs.value.toMutableList()
        current.add(0, item)
        if (current.size > 30) {
            _recentLogs.value = current.take(30)
        } else {
            _recentLogs.value = current
        }
    }

    fun clearLogs() {
        _recentLogs.value = emptyList()
    }

    companion object {
        const val MODIFIER_NONE: Byte = 0x00
        const val MODIFIER_SHIFT: Byte = 0x02

        data class HidKey(val keyCode: Byte, val modifier: Byte = MODIFIER_NONE)

        fun transliterateTurkish(input: String): String {
            return input
                .replace('ç', 'c').replace('Ç', 'C')
                .replace('ğ', 'g').replace('Ğ', 'G')
                .replace('ı', 'i').replace('İ', 'I')
                .replace('ö', 'o').replace('Ö', 'O')
                .replace('ş', 's').replace('Ş', 'S')
                .replace('ü', 'u').replace('Ü', 'U')
                .replace("₺", "TL")
        }

        /**
         * Maps characters to exact USB HID Keyboard Usage IDs (Page 0x07).
         * Supports both Turkish Q (TR_Q) and English US QWERTY (US_Q) host layouts so
         * punctuation like '-', '.', ',', ':', '/', ';' and Turkish letters never turn into gibberish.
         */
        fun charToHidKey(c: Char, layout: KeyboardLayoutType = KeyboardLayoutType.TR_Q): HidKey? {
            // Digits 0-9 are invariant across US-Q and TR-Q
            when (c) {
                '1' -> return HidKey(0x1E.toByte(), MODIFIER_NONE)
                '2' -> return HidKey(0x1F.toByte(), MODIFIER_NONE)
                '3' -> return HidKey(0x20.toByte(), MODIFIER_NONE)
                '4' -> return HidKey(0x21.toByte(), MODIFIER_NONE)
                '5' -> return HidKey(0x22.toByte(), MODIFIER_NONE)
                '6' -> return HidKey(0x23.toByte(), MODIFIER_NONE)
                '7' -> return HidKey(0x24.toByte(), MODIFIER_NONE)
                '8' -> return HidKey(0x25.toByte(), MODIFIER_NONE)
                '9' -> return HidKey(0x26.toByte(), MODIFIER_NONE)
                '0' -> return HidKey(0x27.toByte(), MODIFIER_NONE)
                ' ' -> return HidKey(0x2C.toByte(), MODIFIER_NONE)
            }

            if (layout == KeyboardLayoutType.TR_Q) {
                return when (c) {
                    // On Turkish Q, 'i' (dotted lowercase) is key 0x34 (US apostrophe position),
                    // while 'ı' (dotless lowercase) is key 0x0C (US 'i' position)!
                    'i' -> HidKey(0x34.toByte(), MODIFIER_NONE)
                    'İ' -> HidKey(0x34.toByte(), MODIFIER_SHIFT)
                    'ı' -> HidKey(0x0C.toByte(), MODIFIER_NONE)
                    'I' -> HidKey(0x0C.toByte(), MODIFIER_SHIFT)
                    'ğ' -> HidKey(0x2F.toByte(), MODIFIER_NONE)
                    'Ğ' -> HidKey(0x2F.toByte(), MODIFIER_SHIFT)
                    'ü' -> HidKey(0x30.toByte(), MODIFIER_NONE)
                    'Ü' -> HidKey(0x30.toByte(), MODIFIER_SHIFT)
                    'ş' -> HidKey(0x33.toByte(), MODIFIER_NONE)
                    'Ş' -> HidKey(0x33.toByte(), MODIFIER_SHIFT)
                    'ö' -> HidKey(0x36.toByte(), MODIFIER_NONE)
                    'Ö' -> HidKey(0x36.toByte(), MODIFIER_SHIFT)
                    'ç' -> HidKey(0x37.toByte(), MODIFIER_NONE)
                    'Ç' -> HidKey(0x37.toByte(), MODIFIER_SHIFT)

                    // Standard Latin letters (identical positions on TR-Q except I/İ/ı/i handled above)
                    in 'a'..'z' -> HidKey((0x04 + (c - 'a')).toByte(), MODIFIER_NONE)
                    in 'A'..'Z' -> HidKey((0x04 + (c - 'A')).toByte(), MODIFIER_SHIFT)

                    // Turkish Q punctuation & symbols (exact physical HID positions)
                    '-' -> HidKey(0x2E.toByte(), MODIFIER_NONE) // Key right of '*'
                    '_' -> HidKey(0x2E.toByte(), MODIFIER_SHIFT)
                    '*' -> HidKey(0x2D.toByte(), MODIFIER_NONE) // Key right of '0'
                    '?' -> HidKey(0x2D.toByte(), MODIFIER_SHIFT)
                    ',' -> HidKey(0x31.toByte(), MODIFIER_NONE) // Key right of 'ü' / above Enter
                    ';' -> HidKey(0x31.toByte(), MODIFIER_SHIFT)
                    '.' -> HidKey(0x38.toByte(), MODIFIER_NONE) // Key right of 'ç'
                    ':' -> HidKey(0x38.toByte(), MODIFIER_SHIFT)
                    '"' -> HidKey(0x35.toByte(), MODIFIER_NONE) // Key left of '1'
                    '!' -> HidKey(0x1E.toByte(), MODIFIER_SHIFT)
                    '\'' -> HidKey(0x1F.toByte(), MODIFIER_SHIFT)
                    '^' -> HidKey(0x20.toByte(), MODIFIER_SHIFT)
                    '+' -> HidKey(0x21.toByte(), MODIFIER_SHIFT)
                    '%' -> HidKey(0x22.toByte(), MODIFIER_SHIFT)
                    '&' -> HidKey(0x23.toByte(), MODIFIER_SHIFT)
                    '/' -> HidKey(0x24.toByte(), MODIFIER_SHIFT)
                    '(' -> HidKey(0x25.toByte(), MODIFIER_SHIFT)
                    ')' -> HidKey(0x26.toByte(), MODIFIER_SHIFT)
                    '=' -> HidKey(0x27.toByte(), MODIFIER_SHIFT)
                    else -> null
                }
            }

            // US QWERTY mapping
            return when (c) {
                in 'a'..'z' -> HidKey((0x04 + (c - 'a')).toByte(), MODIFIER_NONE)
                in 'A'..'Z' -> HidKey((0x04 + (c - 'A')).toByte(), MODIFIER_SHIFT)
                '-' -> HidKey(0x2D.toByte(), MODIFIER_NONE)
                '_' -> HidKey(0x2D.toByte(), MODIFIER_SHIFT)
                '=' -> HidKey(0x2E.toByte(), MODIFIER_NONE)
                '+' -> HidKey(0x2E.toByte(), MODIFIER_SHIFT)
                '[' -> HidKey(0x2F.toByte(), MODIFIER_NONE)
                '{' -> HidKey(0x2F.toByte(), MODIFIER_SHIFT)
                ']' -> HidKey(0x30.toByte(), MODIFIER_NONE)
                '}' -> HidKey(0x30.toByte(), MODIFIER_SHIFT)
                '\\' -> HidKey(0x31.toByte(), MODIFIER_NONE)
                '|' -> HidKey(0x31.toByte(), MODIFIER_SHIFT)
                ';' -> HidKey(0x33.toByte(), MODIFIER_NONE)
                ':' -> HidKey(0x33.toByte(), MODIFIER_SHIFT)
                '\'' -> HidKey(0x34.toByte(), MODIFIER_NONE)
                '"' -> HidKey(0x34.toByte(), MODIFIER_SHIFT)
                '`' -> HidKey(0x35.toByte(), MODIFIER_NONE)
                '~' -> HidKey(0x35.toByte(), MODIFIER_SHIFT)
                ',' -> HidKey(0x36.toByte(), MODIFIER_NONE)
                '<' -> HidKey(0x36.toByte(), MODIFIER_SHIFT)
                '.' -> HidKey(0x37.toByte(), MODIFIER_NONE)
                '>' -> HidKey(0x37.toByte(), MODIFIER_SHIFT)
                '/' -> HidKey(0x38.toByte(), MODIFIER_NONE)
                '?' -> HidKey(0x38.toByte(), MODIFIER_SHIFT)
                '!' -> HidKey(0x1E.toByte(), MODIFIER_SHIFT)
                '@' -> HidKey(0x1F.toByte(), MODIFIER_SHIFT)
                '#' -> HidKey(0x20.toByte(), MODIFIER_SHIFT)
                '$' -> HidKey(0x21.toByte(), MODIFIER_SHIFT)
                '%' -> HidKey(0x22.toByte(), MODIFIER_SHIFT)
                '^' -> HidKey(0x23.toByte(), MODIFIER_SHIFT)
                '&' -> HidKey(0x24.toByte(), MODIFIER_SHIFT)
                '*' -> HidKey(0x25.toByte(), MODIFIER_SHIFT)
                '(' -> HidKey(0x26.toByte(), MODIFIER_SHIFT)
                ')' -> HidKey(0x27.toByte(), MODIFIER_SHIFT)
                else -> null
            }
        }

        // Standard USB HID Keyboard Descriptor
        val HID_KEYBOARD_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), //   Report ID (1)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (224)
            0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (231)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute) - Modifier byte
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x81.toByte(), 0x01.toByte(), //   Input (Constant) - Reserved byte
            0x95.toByte(), 0x05.toByte(), //   Report Count (5)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x05.toByte(), 0x08.toByte(), //   Usage Page (LEDs)
            0x19.toByte(), 0x01.toByte(), //   Usage Minimum (1)
            0x29.toByte(), 0x05.toByte(), //   Usage Maximum (5)
            0x91.toByte(), 0x02.toByte(), //   Output (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x75.toByte(), 0x03.toByte(), //   Report Size (3)
            0x91.toByte(), 0x01.toByte(), //   Output (Constant)
            0x95.toByte(), 0x06.toByte(), //   Report Count (6)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(), //   Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Key Codes)
            0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(), //   Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(), //   Input (Data, Array)
            0xC0.toByte()                // End Collection
        )
    }
}
