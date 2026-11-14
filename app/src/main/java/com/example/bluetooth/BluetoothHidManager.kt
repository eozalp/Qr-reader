package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.BluetoothKeyboardConfig
import com.example.model.CalculationStatus
import com.example.model.KeyboardEndingKey
import com.example.model.KeyboardSendTarget
import com.example.model.ParsedQrResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val statusMessage: String
)

data class PairedBluetoothDevice(
    val name: String,
    val address: String,
    val isConnected: Boolean
)

/**
 * High-performance Bluetooth HID Keyboard emulator and transmission engine.
 * Allows the Android device (or ring barcode scanner setup) to act as a wireless
 * keyboard to type scanned barcodes and variables directly into computer applications
 * (Excel, ERP, POS, Word, browser forms).
 */
class BluetoothHidManager(private val context: Context? = null) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val bluetoothManager: BluetoothManager? =
        context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDeviceProfile: BluetoothHidDevice? = null
    private var connectedHostDevice: BluetoothDevice? = null

    private val _connectionStatus = MutableStateFlow(HidConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<HidConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<KeystrokeLogItem>>(emptyList())
    val recentLogs: StateFlow<List<KeystrokeLogItem>> = _recentLogs.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<PairedBluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedBluetoothDevice>> = _pairedDevices.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()

    // Last barcode and time to handle auto-send debounce cooldown
    private var lastSentBarcode: String = ""
    private var lastSentTimestamp: Long = 0L

    init {
        refreshState()
        initHidProfile()
    }

    fun refreshState() {
        if (bluetoothAdapter == null) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            _connectionStatus.value = HidConnectionStatus.BLUETOOTH_OFF
            return
        }
        if (!hasBluetoothPermissions()) {
            _connectionStatus.value = HidConnectionStatus.PERMISSION_REQUIRED
            return
        }
        if (connectedHostDevice != null) {
            _connectionStatus.value = HidConnectionStatus.CONNECTED
        } else {
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
        }
        refreshPairedDevices()
    }

    fun hasBluetoothPermissions(): Boolean {
        val ctx = context ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            return connectGranted
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
            _pairedDevices.value = bonded.map { dev ->
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
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HID_DEVICE) {
                            hidDeviceProfile = null
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
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp(hid: BluetoothHidDevice) {
        if (!hasBluetoothPermissions()) return

        try {
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "ALVETAK QR Keyboard",
                "ALVETAK Barcode to Computer Wedge",
                "ALVETAK",
                BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                HID_KEYBOARD_REPORT_DESCRIPTOR
            )

            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
            )

            hid.registerApp(sdp, null, qos, executor, hidCallback)
        } catch (_: Exception) {}
    }

    private val hidCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registered && pluggedDevice != null) {
                    connectedHostDevice = pluggedDevice
                    _connectedDeviceName.value = getDeviceDisplayName(pluggedDevice)
                    _connectionStatus.value = HidConnectionStatus.CONNECTED
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedHostDevice = device
                        _connectedDeviceName.value = getDeviceDisplayName(device)
                        _connectionStatus.value = HidConnectionStatus.CONNECTED
                        refreshPairedDevices()
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionStatus.value = HidConnectionStatus.CONNECTING
                    }
                    BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                        if (connectedHostDevice?.address == device?.address) {
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
        if (!hasBluetoothPermissions()) return

        try {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
            _connectionStatus.value = HidConnectionStatus.CONNECTING
            hidDeviceProfile?.connect(device)
        } catch (_: Exception) {}
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
            refreshPairedDevices()
        } catch (_: Exception) {}
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
     * Can extract whole barcode, formula result, single variable, or multiple variables joined by Tab.
     */
    fun resolvePayload(
        rawBarcode: String,
        status: CalculationStatus?,
        variableValues: Map<String, String>,
        config: BluetoothKeyboardConfig
    ): String {
        val basePayload = when (config.sendTarget) {
            KeyboardSendTarget.WHOLE_RAW -> rawBarcode

            KeyboardSendTarget.FORMULA_RESULT -> {
                when (status) {
                    is CalculationStatus.Success -> status.formattedText
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
                if (status is CalculationStatus.Success) {
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
     * Checks if this barcode should auto-send based on cooldown debounce.
     */
    fun shouldAutoSend(barcode: String, config: BluetoothKeyboardConfig): Boolean {
        if (!config.isAutoSendEnabled) return false
        val now = System.currentTimeMillis()
        if (barcode == lastSentBarcode && (now - lastSentTimestamp) < config.sendCooldownMs) {
            return false
        }
        return true
    }

    /**
     * Manually updates the last sent barcode and timestamp for debounce tracking.
     */
    fun recordSentBarcode(barcode: String) {
        lastSentBarcode = barcode
        lastSentTimestamp = System.currentTimeMillis()
    }

    /**
     * Transmits the payload as keystrokes to the connected computer.
     * If no Bluetooth HID device is connected (e.g. on emulator or test),
     * it simulates the keystrokes and logs them for developer & user testing.
     */
    suspend fun sendToComputer(
        textToSend: String,
        endingKey: KeyboardEndingKey = KeyboardEndingKey.ENTER,
        typingDelayMs: Long = 12L,
        convertTurkishChars: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val sanitizedText = if (convertTurkishChars) {
            transliterateTurkish(textToSend)
        } else {
            textToSend
        }

        val isRealHidConnected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                hidDeviceProfile != null &&
                connectedHostDevice != null

        lastSentBarcode = textToSend
        lastSentTimestamp = System.currentTimeMillis()

        if (isRealHidConnected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val success = sendOverBluetoothHid(sanitizedText, endingKey, typingDelayMs)
            val logMsg = if (success) "Bilgisayara iletildi" else "Gönderim hatası"
            addLog(KeystrokeLogItem(text = sanitizedText, endingKey = endingKey, isSimulated = false, statusMessage = logMsg))
            return@withContext success
        } else {
            // Virtual / Simulation Mode (For emulators or test mode)
            delay(50)
            addLog(
                KeystrokeLogItem(
                    text = sanitizedText,
                    endingKey = endingKey,
                    isSimulated = true,
                    statusMessage = "Sanal Tuş Simülasyonu [OK]"
                )
            )
            return@withContext true
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendOverBluetoothHid(
        text: String,
        endingKey: KeyboardEndingKey,
        typingDelayMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val hid = hidDeviceProfile ?: return false
        val dev = connectedHostDevice ?: return false

        try {
            for (char in text) {
                if (char == '\t') {
                    sendKeyStroke(hid, dev, 0x2B.toByte(), 0, typingDelayMs)
                } else if (char == '\n') {
                    sendKeyStroke(hid, dev, 0x28.toByte(), 0, typingDelayMs)
                } else {
                    val keyInfo = charToHidKey(char)
                    if (keyInfo != null) {
                        sendKeyStroke(hid, dev, keyInfo.keyCode, keyInfo.modifier, typingDelayMs)
                    }
                }
            }

            // Send Suffix / Terminator Key
            when (endingKey) {
                KeyboardEndingKey.ENTER -> {
                    sendKeyStroke(hid, dev, 0x28.toByte(), 0, typingDelayMs)
                }
                KeyboardEndingKey.TAB -> {
                    sendKeyStroke(hid, dev, 0x2B.toByte(), 0, typingDelayMs)
                }
                KeyboardEndingKey.TAB_THEN_ENTER -> {
                    sendKeyStroke(hid, dev, 0x2B.toByte(), 0, typingDelayMs)
                    delay(typingDelayMs)
                    sendKeyStroke(hid, dev, 0x28.toByte(), 0, typingDelayMs)
                }
                KeyboardEndingKey.SPACE -> {
                    sendKeyStroke(hid, dev, 0x2C.toByte(), 0, typingDelayMs)
                }
                KeyboardEndingKey.NONE -> {
                    // No suffix
                }
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendKeyStroke(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        keyCode: Byte,
        modifier: Byte,
        delayMs: Long
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val reportDown = byteArrayOf(modifier, 0x00, keyCode, 0x00, 0x00, 0x00, 0x00, 0x00)
        val reportUp = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        hid.sendReport(device, 1, reportDown)
        delay(delayMs.coerceAtLeast(8L))
        hid.sendReport(device, 1, reportUp)
        delay(delayMs.coerceAtLeast(8L))
    }

    private fun addLog(item: KeystrokeLogItem) {
        val current = _recentLogs.value.toMutableList()
        current.add(0, item)
        if (current.size > 20) {
            _recentLogs.value = current.take(20)
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

        fun charToHidKey(c: Char): HidKey? {
            return when (c) {
                in 'a'..'z' -> HidKey((0x04 + (c - 'a')).toByte(), MODIFIER_NONE)
                in 'A'..'Z' -> HidKey((0x04 + (c - 'A')).toByte(), MODIFIER_SHIFT)
                '1' -> HidKey(0x1E.toByte(), MODIFIER_NONE)
                '2' -> HidKey(0x1F.toByte(), MODIFIER_NONE)
                '3' -> HidKey(0x20.toByte(), MODIFIER_NONE)
                '4' -> HidKey(0x21.toByte(), MODIFIER_NONE)
                '5' -> HidKey(0x22.toByte(), MODIFIER_NONE)
                '6' -> HidKey(0x23.toByte(), MODIFIER_NONE)
                '7' -> HidKey(0x24.toByte(), MODIFIER_NONE)
                '8' -> HidKey(0x25.toByte(), MODIFIER_NONE)
                '9' -> HidKey(0x26.toByte(), MODIFIER_NONE)
                '0' -> HidKey(0x27.toByte(), MODIFIER_NONE)
                ' ' -> HidKey(0x2C.toByte(), MODIFIER_NONE)
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
