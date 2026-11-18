package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Production-grade Bluetooth Classic HID Keyboard manager (`BluetoothHidDevice`).
 *
 * Key architectural fixes for connection reliability & zero stuck/repeating keys on Windows/macOS:
 * 1. Passes `null, null` QoS settings in `registerApp()` to eliminate L2CAP token-bucket throttling
 *    and negotiation rejections across Windows/Samsung chipsets.
 * 2. Queues `pendingConnectAddress` so `connect()` always runs after `onAppStatusChanged(registered = true)`.
 * 3. Runs HID connection inside `BluetoothHidForegroundService` (`connectedDevice`) so Samsung/Android
 *    never unregisters the HID profile proxy in background or IME switches.
 * 4. Executes all `sendReport` down/up frames strictly sequentially on a single high-priority TX thread
 *    with `Thread.sleep` (24–50ms hold, 22–45ms gap, +15ms extra gap for consecutive identical keycodes)
 *    using fresh 8-byte arrays per report and guaranteed all-zero Key-Up flush on completion or drop.
 */
class BluetoothHidManager(private val context: Context? = null) {

    private val bluetoothManager: BluetoothManager? =
        context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var hidDeviceProfile: BluetoothHidDevice? = null
    @Volatile
    private var connectedHostDevice: BluetoothDevice? = null
    @Volatile
    private var isAppRegistered: Boolean = false
    @Volatile
    private var isRegistering: Boolean = false
    @Volatile
    private var isProxyRequested: Boolean = false
    @Volatile
    private var activeReportId: Int = 1
    @Volatile
    private var activeMouseButtons: Int = 0
    @Volatile
    private var pendingConnectAddress: String? = null
    @Volatile
    private var autoReconnectAttempted: Boolean = false
    @Volatile
    private var connectedAtMs: Long = 0L
    @Volatile
    private var lastReportSentAtMs: Long = 0L
    @Volatile
    private var lastMouseReportAtMs: Long = 0L

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

    private val callbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlvetakHidCallback").apply { isDaemon = true }
    }
    private val hidTxExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlvetakHidTx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }
    private val mouseTxExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlvetakMouseTx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }
    }
    private val hidTxDispatcher: CoroutineDispatcher = hidTxExecutor.asCoroutineDispatcher()
    private val sendMutex = Mutex()

    // Track barcodes currently present in camera frame / pending confirmation to prevent frame-by-frame spam
    private val activeInFrameBarcodes = mutableSetOf<String>()
    private val lastSeenInFrameMs = mutableMapOf<String, Long>()
    private val dismissedUntilLeftFrame = mutableSetOf<String>()
    private var lastSentTimestamp: Long = 0L
    private var lastSentRawBarcode: String = ""

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        clearConnectionState(stopService = true)
                        isAppRegistered = false
                        isRegistering = false
                        isProxyRequested = false
                        hidDeviceProfile = null
                    }
                    refreshState()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshPairedDevices()
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()
        refreshState()
    }

    private fun registerBluetoothReceiver() {
        val ctx = context?.applicationContext ?: return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(ctx, btStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshState() {
        if (bluetoothAdapter == null) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            clearConnectionState(stopService = true)
            _connectionStatus.value = HidConnectionStatus.BLUETOOTH_OFF
            _connectionDiagnostic.value = "Bluetooth kapalı. Ayarlardan Bluetooth'u açın."
            return
        }
        if (!hasBluetoothPermissions()) {
            _connectionStatus.value = HidConnectionStatus.PERMISSION_REQUIRED
            _connectionDiagnostic.value = "Bluetooth bağlantı izni verilmedi."
            return
        }

        // Sync active HID connections directly with Android BluetoothHidDevice proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hidDeviceProfile != null) {
            val activeHosts = runCatching {
                hidDeviceProfile?.getDevicesMatchingConnectionStates(
                    intArrayOf(BluetoothProfile.STATE_CONNECTED)
                )
            }.getOrNull()

            val firstConnected = activeHosts?.firstOrNull()
            if (firstConnected != null) {
                connectedHostDevice = firstConnected
                if (connectedAtMs == 0L) connectedAtMs = System.currentTimeMillis()
                val displayName = getDeviceDisplayName(firstConnected)
                _connectedDeviceName.value = displayName
                _connectionStatus.value = HidConnectionStatus.CONNECTED
                _connectionDiagnostic.value = null
                context?.let { BluetoothHidForegroundService.startOrUpdate(it, displayName) }
                refreshPairedDevices()
                return
            } else if (connectedHostDevice != null) {
                // Clear stale host reference if system dropped link silently
                clearConnectionState(stopService = true)
            }
        }

        if (_connectionStatus.value != HidConnectionStatus.CONNECTING) {
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
        }

        if (hidDeviceProfile == null) {
            initHidProfile()
        } else if (!isAppRegistered && !isRegistering && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerHidApp(hidDeviceProfile!!)
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
        val ctx = context?.applicationContext
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || bluetoothAdapter == null) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            return
        }
        if (isProxyRequested || hidDeviceProfile != null) return
        isProxyRequested = true

        try {
            val ok = bluetoothAdapter.getProfileProxy(
                ctx,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        isProxyRequested = false
                        if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                            hidDeviceProfile = proxy
                            registerHidApp(proxy)
                            refreshState()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        isProxyRequested = false
                        if (profile == BluetoothProfile.HID_DEVICE) {
                            hidDeviceProfile = null
                            isAppRegistered = false
                            isRegistering = false
                            clearConnectionState(stopService = true)
                            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                        }
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
            if (!ok) {
                isProxyRequested = false
                _connectionDiagnostic.value = "Android HID servisi şu an meşgul; yenileyip tekrar deneyin."
            }
        } catch (e: Exception) {
            isProxyRequested = false
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            _connectionDiagnostic.value = "HID servisi başlatılamadı: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp(hid: BluetoothHidDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasBluetoothPermissions()) return
        if (isAppRegistered || isRegistering) return
        isRegistering = true

        callbackExecutor.execute {
            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "ALVETAK QR Klavye & Mouse",
                    "ALVETAK Kablosuz Klavye, Mouse & Air Mouse",
                    "ALVETAK",
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    HID_COMBO_REPORT_DESCRIPTOR
                )

                // Passing null for inQos and outQos lets Android & Windows L2CAP negotiate
                // default interrupt channel parameters without token-bucket throttling or QoS drops.
                var started = hid.registerApp(sdp, null, null, callbackExecutor, hidCallback)
                if (!started) {
                    // Clear any leftover system HID registration from a prior session and retry once.
                    runCatching { hid.unregisterApp() }
                    try {
                        Thread.sleep(180L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    started = hid.registerApp(sdp, null, null, callbackExecutor, hidCallback)
                }

                if (!started) {
                    isRegistering = false
                    _connectionDiagnostic.value =
                        "HID profili kaydedilemedi. Başka bir BT klavye uygulaması açıksa kapatın veya Bluetooth'u kapatıp açın."
                }
            } catch (e: Exception) {
                isRegistering = false
                _connectionDiagnostic.value = "HID kayıt hatası: ${e.message}"
            }
        }
    }

    private val hidCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        object : BluetoothHidDevice.Callback() {
            @SuppressLint("MissingPermission")
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                isRegistering = false
                isAppRegistered = registered
                if (registered) {
                    _connectionDiagnostic.value = null
                    if (pluggedDevice != null) {
                        onHostConnected(pluggedDevice)
                    } else {
                        val queued = pendingConnectAddress
                        if (queued != null) {
                            pendingConnectAddress = null
                            connectToDevice(queued)
                        } else if (!autoReconnectAttempted && connectedHostDevice == null) {
                            autoReconnectAttempted = true
                            val lastAddr = prefs?.getString(KEY_LAST_HOST_ADDRESS, null)
                            val isStillBonded = !lastAddr.isNullOrBlank() && runCatching {
                                bluetoothAdapter?.bondedDevices?.any { it.address == lastAddr } == true
                            }.getOrDefault(false)
                            if (isStillBonded && lastAddr != null) {
                                connectToDevice(lastAddr)
                            }
                        }
                    }
                } else {
                    clearConnectionState(stopService = true)
                    _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (device != null) {
                            onHostConnected(device)
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionStatus.value = HidConnectionStatus.CONNECTING
                        _connectionDiagnostic.value = "Bilgisayarla HID bağlantısı kuruluyor..."
                    }
                    BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                        if (device != null && hidDeviceProfile != null) {
                            // Ensure host keyboard & mouse buffers have zero held inputs before dropping
                            runCatching {
                                hidDeviceProfile?.sendReport(device, activeReportId, newZeroReport())
                                hidDeviceProfile?.sendReport(device, REPORT_ID_MOUSE, byteArrayOf(0, 0, 0, 0))
                            }
                        }
                        if (connectedHostDevice?.address == device?.address || connectedHostDevice == null) {
                            clearConnectionState(stopService = true)
                            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                            if (state == BluetoothProfile.STATE_DISCONNECTED && _connectionDiagnostic.value == null) {
                                _connectionDiagnostic.value =
                                    "Bağlantı kesildi. Sorun sürerse PC Bluetooth ayarlarından cihazı kaldırıp yeniden eşleştirin."
                            }
                            refreshPairedDevices()
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val replySize = when {
                        type == BluetoothHidDevice.REPORT_TYPE_OUTPUT -> 1
                        id == REPORT_ID_MOUSE.toByte() -> 4
                        id == REPORT_ID_CONSUMER.toByte() -> 2
                        else -> 8
                    }
                    runCatching {
                        hidDeviceProfile?.replyReport(device, type, id, ByteArray(replySize))
                    }
                }
            }

            override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activeReportId = if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) 0 else 1
                }
            }

            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
                // Host keyboard LED state acknowledged cleanly
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice?) {
                if (connectedHostDevice?.address == device?.address) {
                    clearConnectionState(stopService = true)
                    _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                    _connectionDiagnostic.value = "Bilgisayar kablosuz klavye bağlantısını sonlandırdı."
                    refreshPairedDevices()
                }
            }
        }
    } else null

    @SuppressLint("MissingPermission")
    private fun onHostConnected(device: BluetoothDevice) {
        connectedHostDevice = device
        connectedAtMs = System.currentTimeMillis()
        activeReportId = 1
        val name = getDeviceDisplayName(device)
        _connectedDeviceName.value = name
        _connectionStatus.value = HidConnectionStatus.CONNECTED
        _connectionDiagnostic.value = null
        prefs?.edit()?.putString(KEY_LAST_HOST_ADDRESS, device.address)?.apply()
        context?.let { BluetoothHidForegroundService.startOrUpdate(it, name) }
        refreshPairedDevices()
    }

    private fun clearConnectionState(stopService: Boolean) {
        connectedHostDevice = null
        connectedAtMs = 0L
        activeReportId = 1
        activeMouseButtons = 0
        _connectedDeviceName.value = null
        if (stopService) {
            context?.let { BluetoothHidForegroundService.stop(it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!hasBluetoothPermissions()) {
            _connectionStatus.value = HidConnectionStatus.PERMISSION_REQUIRED
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionStatus.value = HidConnectionStatus.BLUETOOTH_OFF
            return
        }

        val hid = hidDeviceProfile
        if (hid == null || !isAppRegistered) {
            pendingConnectAddress = address
            _connectionStatus.value = HidConnectionStatus.CONNECTING
            _connectionDiagnostic.value = "HID profili hazırlanıyor, biraz bekleyin..."
            if (hid == null) {
                initHidProfile()
            } else {
                registerHidApp(hid)
            }
            return
        }

        try {
            val device = bluetoothAdapter.getRemoteDevice(address) ?: return
            val name = getDeviceDisplayName(device)
            _connectionStatus.value = HidConnectionStatus.CONNECTING
            _connectionDiagnostic.value = "$name cihazına bağlanılıyor..."
            context?.let { BluetoothHidForegroundService.startOrUpdate(it, name) }

            // Disconnect any previous host first
            connectedHostDevice?.let { prev ->
                if (prev.address != address) {
                    runCatching { hid.disconnect(prev) }
                }
            }

            val initiated = hid.connect(device)
            if (!initiated) {
                context?.let { BluetoothHidForegroundService.stop(it) }
                _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                _connectionDiagnostic.value =
                    "Bağlantı başlatılamadı. PC Bluetooth ayarlarından telefonu 'Kaldır' deyip yeniden eşleştirin veya PC'den 'Bağlan' deyin."
            }
        } catch (e: Exception) {
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            _connectionDiagnostic.value = "Bağlantı hatası: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice() {
        pendingConnectAddress = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || hidDeviceProfile == null) {
            clearConnectionState(stopService = true)
            return
        }
        if (!hasBluetoothPermissions()) return

        try {
            connectedHostDevice?.let { dev ->
                runCatching {
                    hidDeviceProfile?.sendReport(dev, activeReportId, newZeroReport())
                    hidDeviceProfile?.sendReport(dev, REPORT_ID_MOUSE, byteArrayOf(0, 0, 0, 0))
                }
                hidDeviceProfile?.disconnect(dev)
            }
            clearConnectionState(stopService = true)
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            _connectionDiagnostic.value = null
            refreshPairedDevices()
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        pendingConnectAddress = null
        context?.applicationContext?.let { ctx ->
            runCatching { ctx.unregisterReceiver(btStateReceiver) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hidDeviceProfile?.let { hid ->
                runCatching {
                    connectedHostDevice?.let { dev ->
                        hid.sendReport(dev, activeReportId, newZeroReport())
                        hid.sendReport(dev, REPORT_ID_MOUSE, byteArrayOf(0, 0, 0, 0))
                    }
                    hid.unregisterApp()
                    bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                }
            }
        }
        clearConnectionState(stopService = true)
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
     * - Only asks for confirmation on accidental consecutive back-to-back repeats
     *   (`lastSentRawBarcode == rawBarcode` within [CONSECUTIVE_DUPLICATE_WINDOW_MS]).
     * - Same barcodes separated by another scan in between or spaced apart in time send immediately.
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
            if ((nowMs - existingEntry.lastSentTimestamp) < config.sendCooldownMs) {
                return AutoSendDecision.SkipAlreadyActive
            }
            val isConsecutiveRepeat = isAccidentalConsecutiveDuplicate(
                rawBarcode = rawBarcode,
                lastSentRawBarcode = lastSentRawBarcode,
                lastSentTimestampMs = lastSentTimestamp,
                nowMs = nowMs
            )
            return if (config.requireDuplicateConfirmation && isConsecutiveRepeat) {
                AutoSendDecision.RequireConfirmation(existingEntry)
            } else {
                AutoSendDecision.SendNow
            }
        }

        return AutoSendDecision.SendNow
    }

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
     * Transmits the payload sequentially on a dedicated high-priority HID TX thread under Mutex lock.
     * Supports Turkish-Q and US-Q keyboard scancodes and updates batch ACK counter upon confirmed delivery.
     */
    suspend fun sendToComputer(
        textToSend: String,
        rawBarcodeKey: String = textToSend,
        endingKey: KeyboardEndingKey = KeyboardEndingKey.ENTER,
        typingDelayMs: Long = 26L,
        convertTurkishChars: Boolean = false,
        keyboardLayout: KeyboardLayoutType = KeyboardLayoutType.TR_Q
    ): Boolean = sendMutex.withLock {
        withContext(hidTxDispatcher) {
            _isTransmitting.value = true
            try {
                markBarcodeInFrame(rawBarcodeKey)

                val effectiveConvert = convertTurkishChars || keyboardLayout == KeyboardLayoutType.US_Q
                val sanitizedText = if (effectiveConvert) {
                    transliterateTurkish(textToSend)
                } else {
                    textToSend.replace("₺", "TL")
                }

                if (isHidHostConnected() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val success = sendOverBluetoothHidBlocking(
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
                    sleepQuietly(20L)
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

    fun isHidHostConnected(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            hidDeviceProfile != null &&
            connectedHostDevice != null
    }

    /**
     * Sends a single classic-keyboard HID stroke (Backspace, Tab, Enter, or typed char)
     * strictly serialized on the dedicated HID TX thread with guaranteed Key-Up release.
     */
    suspend fun sendSingleHidKey(
        keyCode: Byte,
        modifier: Byte = MODIFIER_NONE,
        typingDelayMs: Long = 26L
    ): Boolean = sendMutex.withLock {
        withContext(hidTxDispatcher) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext false
            val hid = hidDeviceProfile ?: return@withContext false
            val dev = connectedHostDevice ?: return@withContext false

            ensureHostReadyForReports(hid, dev)
            val timing = computeKeyTiming(typingDelayMs, isSameKeyCodeAsPrev = false)
            try {
                sendKeyStrokeBlocking(hid, dev, keyCode, modifier, timing.holdMs, timing.gapMs)
            } finally {
                sendZeroReportWithRetry(hid, dev, maxAttempts = 8)
            }
        }
    }

    suspend fun sendSingleCharacter(
        ch: Char,
        layout: KeyboardLayoutType = KeyboardLayoutType.TR_Q,
        typingDelayMs: Long = 26L,
        extraModifiers: Byte = MODIFIER_NONE
    ): Boolean {
        val normalized = if (layout == KeyboardLayoutType.US_Q) {
            transliterateTurkish(ch.toString()).firstOrNull() ?: ch
        } else {
            ch
        }
        val hidKey = charToHidKey(normalized, layout) ?: return false
        val combinedMod = (hidKey.modifier.toInt() or extraModifiers.toInt()).toByte()
        return sendSingleHidKey(hidKey.keyCode, combinedMod, typingDelayMs)
    }

    suspend fun sendHidKeyCombo(
        keyCode: Byte,
        modifiers: Byte = MODIFIER_NONE,
        typingDelayMs: Long = 26L
    ): Boolean = sendSingleHidKey(keyCode, modifiers, typingDelayMs)

    @SuppressLint("MissingPermission")
    suspend fun sendConsumerControl(usageCode: Int): Boolean = sendMutex.withLock {
        withContext(hidTxDispatcher) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext false
            val hid = hidDeviceProfile ?: return@withContext false
            val dev = connectedHostDevice ?: return@withContext false
            val press = buildConsumerReport(usageCode)
            val release = buildConsumerReport(0)
            val sent = runCatching { hid.sendReport(dev, REPORT_ID_CONSUMER, press) }.getOrDefault(false)
            sleepQuietly(26L)
            runCatching { hid.sendReport(dev, REPORT_ID_CONSUMER, release) }
            if (sent) lastReportSentAtMs = System.currentTimeMillis()
            sent
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseMove(dx: Int, dy: Int, wheel: Int = 0) {
        if (dx == 0 && dy == 0 && wheel == 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            var remX = dx
            var remY = dy
            var remW = wheel
            while (remX != 0 || remY != 0 || remW != 0) {
                val stepX = remX.coerceIn(-127, 127)
                val stepY = remY.coerceIn(-127, 127)
                val stepW = remW.coerceIn(-127, 127)
                remX -= stepX
                remY -= stepY
                remW -= stepW
                val report = buildMouseReport(activeMouseButtons, stepX, stepY, stepW)
                val ok = runCatching { hid.sendReport(dev, REPORT_ID_MOUSE, report) }.getOrDefault(false)
                if (ok) {
                    lastMouseReportAtMs = System.currentTimeMillis()
                    lastReportSentAtMs = lastMouseReportAtMs
                }
                if (remX != 0 || remY != 0 || remW != 0) {
                    sleepQuietly(8L)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun setMouseButton(buttonMask: Int, isDown: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        mouseTxExecutor.execute {
            activeMouseButtons = if (isDown) {
                activeMouseButtons or buttonMask
            } else {
                activeMouseButtons and buttonMask.inv()
            }
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            val report = buildMouseReport(activeMouseButtons, 0, 0, 0)
            runCatching { hid.sendReport(dev, REPORT_ID_MOUSE, report) }
            lastMouseReportAtMs = System.currentTimeMillis()
            lastReportSentAtMs = lastMouseReportAtMs
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseClick(buttonMask: Int = MOUSE_BTN_LEFT) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            val downMask = activeMouseButtons or buttonMask
            runCatching {
                hid.sendReport(dev, REPORT_ID_MOUSE, buildMouseReport(downMask, 0, 0, 0))
            }
            sleepQuietly(18L)
            activeMouseButtons = activeMouseButtons and buttonMask.inv()
            runCatching {
                hid.sendReport(dev, REPORT_ID_MOUSE, buildMouseReport(activeMouseButtons, 0, 0, 0))
            }
            lastMouseReportAtMs = System.currentTimeMillis()
            lastReportSentAtMs = lastMouseReportAtMs
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseDoubleClick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            repeat(2) { idx ->
                val downMask = activeMouseButtons or MOUSE_BTN_LEFT
                runCatching {
                    hid.sendReport(dev, REPORT_ID_MOUSE, buildMouseReport(downMask, 0, 0, 0))
                }
                sleepQuietly(16L)
                activeMouseButtons = activeMouseButtons and MOUSE_BTN_LEFT.inv()
                runCatching {
                    hid.sendReport(dev, REPORT_ID_MOUSE, buildMouseReport(activeMouseButtons, 0, 0, 0))
                }
                if (idx == 0) sleepQuietly(55L)
            }
            lastMouseReportAtMs = System.currentTimeMillis()
            lastReportSentAtMs = lastMouseReportAtMs
        }
    }

    @SuppressLint("MissingPermission")
    fun releaseAllMouseButtons() {
        activeMouseButtons = 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            runCatching {
                hid.sendReport(dev, REPORT_ID_MOUSE, buildMouseReport(0, 0, 0, 0))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureHostReadyForReports(hid: BluetoothHidDevice, dev: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val now = System.currentTimeMillis()
        // Wait briefly after initial connection so Windows HID class driver finishes enumeration
        val sinceConnect = now - connectedAtMs
        if (connectedAtMs > 0L && sinceConnect in 0L..349L) {
            sleepQuietly(350L - sinceConnect)
        }
        // Wake idle ACL link out of Sniff Mode with an empty zero-report before pressing any real key
        if ((System.currentTimeMillis() - lastReportSentAtMs) > 400L) {
            sendZeroReportWithRetry(hid, dev, maxAttempts = 4)
            sleepQuietly(60L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendOverBluetoothHidBlocking(
        text: String,
        endingKey: KeyboardEndingKey,
        typingDelayMs: Long,
        layout: KeyboardLayoutType
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val hid = hidDeviceProfile ?: return false
        val dev = connectedHostDevice ?: return false

        val baseTiming = computeKeyTiming(typingDelayMs, isSameKeyCodeAsPrev = false)

        try {
            ensureHostReadyForReports(hid, dev)

            var prevKeyCode: Byte? = null
            for (char in text) {
                val keyInfo = when (char) {
                    '\t' -> HidKey(KEY_TAB, MODIFIER_NONE)
                    '\n' -> HidKey(KEY_ENTER, MODIFIER_NONE)
                    else -> charToHidKey(char, layout)
                }
                if (keyInfo != null) {
                    val isSameAsPrev = (prevKeyCode == keyInfo.keyCode)
                    val timing = computeKeyTiming(typingDelayMs, isSameKeyCodeAsPrev = isSameAsPrev)
                    if (!sendKeyStrokeBlocking(hid, dev, keyInfo.keyCode, keyInfo.modifier, timing.holdMs, timing.gapMs)) {
                        return false
                    }
                    prevKeyCode = keyInfo.keyCode
                }
            }

            when (endingKey) {
                KeyboardEndingKey.ENTER -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_ENTER, MODIFIER_NONE, baseTiming.holdMs, baseTiming.gapMs)) return false
                }
                KeyboardEndingKey.TAB -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_TAB, MODIFIER_NONE, baseTiming.holdMs, baseTiming.gapMs)) return false
                }
                KeyboardEndingKey.TAB_THEN_ENTER -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_TAB, MODIFIER_NONE, baseTiming.holdMs, baseTiming.gapMs)) return false
                    sleepQuietly(20L)
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_ENTER, MODIFIER_NONE, baseTiming.holdMs, baseTiming.gapMs)) return false
                }
                KeyboardEndingKey.SPACE -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_SPACE, MODIFIER_NONE, baseTiming.holdMs, baseTiming.gapMs)) return false
                }
                KeyboardEndingKey.NONE -> {
                    // No suffix
                }
            }

            return true
        } catch (_: Exception) {
            return false
        } finally {
            // Guaranteed all-zero release flush so a key can never stay held repeating on Windows/macOS
            runCatching { sendZeroReportWithRetry(hid, dev, maxAttempts = 10) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendReportWithRetryBlocking(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        report: ByteArray,
        maxAttempts: Int,
        backoffMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val reportId = activeReportId
        repeat(maxAttempts) { attempt ->
            if (connectedHostDevice?.address != device.address) return false
            if (hid.sendReport(device, reportId, report)) {
                lastReportSentAtMs = System.currentTimeMillis()
                return true
            }
            sleepQuietly(backoffMs * (attempt + 1))
        }
        return false
    }

    private fun sendZeroReportWithRetry(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        maxAttempts: Int = 10
    ): Boolean {
        return sendReportWithRetryBlocking(
            hid = hid,
            device = device,
            report = newZeroReport(),
            maxAttempts = maxAttempts,
            backoffMs = 10L
        )
    }

    @SuppressLint("MissingPermission")
    private fun sendKeyStrokeBlocking(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        keyCode: Byte,
        modifier: Byte,
        holdMs: Long,
        gapMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val reportDown = byteArrayOf(modifier, 0x00, keyCode, 0x00, 0x00, 0x00, 0x00, 0x00)

        if (!sendReportWithRetryBlocking(hid, device, reportDown, maxAttempts = 5, backoffMs = 12L)) {
            sendZeroReportWithRetry(hid, device, maxAttempts = 6)
            return false
        }
        sleepQuietly(holdMs)
        if (!sendZeroReportWithRetry(hid, device, maxAttempts = 10)) {
            return false
        }
        sleepQuietly(gapMs)
        return true
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun newZeroReport(): ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

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
        private const val PREFS_NAME = "alvetak_bt_prefs"
        private const val KEY_LAST_HOST_ADDRESS = "last_host_address"

        const val REPORT_ID_KEYBOARD = 1
        const val REPORT_ID_MOUSE = 2
        const val REPORT_ID_CONSUMER = 3

        const val MODIFIER_NONE: Byte = 0x00
        const val MODIFIER_LEFT_CTRL: Byte = 0x01
        const val MODIFIER_SHIFT: Byte = 0x02
        const val MODIFIER_LEFT_ALT: Byte = 0x04
        const val MODIFIER_LEFT_GUI: Byte = 0x08
        const val MODIFIER_ALT_GR: Byte = 0x40

        const val KEY_A: Byte = 0x04
        const val KEY_C: Byte = 0x06
        const val KEY_D: Byte = 0x07
        const val KEY_S: Byte = 0x16
        const val KEY_V: Byte = 0x19
        const val KEY_X: Byte = 0x1B
        const val KEY_Z: Byte = 0x1D

        const val KEY_ENTER: Byte = 0x28
        const val KEY_ESCAPE: Byte = 0x29
        const val KEY_BACKSPACE: Byte = 0x2A
        const val KEY_TAB: Byte = 0x2B
        const val KEY_SPACE: Byte = 0x2C
        const val KEY_CAPS_LOCK: Byte = 0x39
        const val KEY_F1: Byte = 0x3A
        const val KEY_F2: Byte = 0x3B
        const val KEY_F3: Byte = 0x3C
        const val KEY_F4: Byte = 0x3D
        const val KEY_F5: Byte = 0x3E
        const val KEY_F6: Byte = 0x3F
        const val KEY_F7: Byte = 0x40
        const val KEY_F8: Byte = 0x41
        const val KEY_F9: Byte = 0x42
        const val KEY_F10: Byte = 0x43
        const val KEY_F11: Byte = 0x44
        const val KEY_F12: Byte = 0x45
        const val KEY_PRINT_SCREEN: Byte = 0x46
        const val KEY_INSERT: Byte = 0x49
        const val KEY_HOME: Byte = 0x4A
        const val KEY_PAGE_UP: Byte = 0x4B
        const val KEY_DELETE: Byte = 0x4C
        const val KEY_END: Byte = 0x4D
        const val KEY_PAGE_DOWN: Byte = 0x4E
        const val KEY_RIGHT: Byte = 0x4F
        const val KEY_LEFT: Byte = 0x50
        const val KEY_DOWN: Byte = 0x51
        const val KEY_UP: Byte = 0x52

        const val MOUSE_BTN_LEFT: Int = 0x01
        const val MOUSE_BTN_RIGHT: Int = 0x02
        const val MOUSE_BTN_MIDDLE: Int = 0x04

        const val CONSUMER_NEXT: Int = 0x00B5
        const val CONSUMER_PREV: Int = 0x00B6
        const val CONSUMER_PLAY_PAUSE: Int = 0x00CD
        const val CONSUMER_MUTE: Int = 0x00E2
        const val CONSUMER_VOL_UP: Int = 0x00E9
        const val CONSUMER_VOL_DOWN: Int = 0x00EA

        const val CONSECUTIVE_DUPLICATE_WINDOW_MS: Long = 15_000L

        fun buildMouseReport(buttonsMask: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray {
            return byteArrayOf(
                (buttonsMask and 0x07).toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
        }

        fun buildConsumerReport(usageCode: Int): ByteArray {
            return byteArrayOf(
                (usageCode and 0xFF).toByte(),
                ((usageCode shr 8) and 0xFF).toByte()
            )
        }

        /**
         * Returns true only when [rawBarcode] is the exact same barcode that was just sent
         * and is being scanned back-to-back within [CONSECUTIVE_DUPLICATE_WINDOW_MS] (accidental repeat).
         */
        fun isAccidentalConsecutiveDuplicate(
            rawBarcode: String,
            lastSentRawBarcode: String,
            lastSentTimestampMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            if (lastSentRawBarcode.isEmpty() || lastSentRawBarcode != rawBarcode) return false
            if (lastSentTimestampMs <= 0L) return false
            return (nowMs - lastSentTimestampMs) < CONSECUTIVE_DUPLICATE_WINDOW_MS
        }

        @Volatile
        private var instance: BluetoothHidManager? = null

        fun getInstance(context: Context): BluetoothHidManager {
            return instance ?: synchronized(this) {
                instance ?: BluetoothHidManager(context.applicationContext).also { instance = it }
            }
        }

        data class KeyTiming(val holdMs: Long, val gapMs: Long)

        /**
         * Enforces real Bluetooth Classic HID hardware polling intervals (24–50ms hold, 22–60ms gap)
         * so Down and Up reports always land on separate ACL intervals without triggering OS key repeat.
         * Consecutive identical keycodes (e.g. '0'->'0' in "00326") receive +15ms extra separation.
         */
        fun computeKeyTiming(requestedDelayMs: Long, isSameKeyCodeAsPrev: Boolean): KeyTiming {
            val holdMs = requestedDelayMs.coerceIn(24L, 50L)
            val baseGap = requestedDelayMs.coerceIn(22L, 45L)
            val gapMs = if (isSameKeyCodeAsPrev) baseGap + 15L else baseGap
            return KeyTiming(holdMs = holdMs, gapMs = gapMs)
        }

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
                    in 'a'..'z' -> HidKey((0x04 + (c - 'a')).toByte(), MODIFIER_NONE)
                    in 'A'..'Z' -> HidKey((0x04 + (c - 'A')).toByte(), MODIFIER_SHIFT)
                    '-' -> HidKey(0x2E.toByte(), MODIFIER_NONE)
                    '_' -> HidKey(0x2E.toByte(), MODIFIER_SHIFT)
                    '*' -> HidKey(0x2D.toByte(), MODIFIER_NONE)
                    '?' -> HidKey(0x2D.toByte(), MODIFIER_SHIFT)
                    ',' -> HidKey(0x31.toByte(), MODIFIER_NONE)
                    ';' -> HidKey(0x31.toByte(), MODIFIER_SHIFT)
                    '.' -> HidKey(0x38.toByte(), MODIFIER_NONE)
                    ':' -> HidKey(0x38.toByte(), MODIFIER_SHIFT)
                    '"' -> HidKey(0x35.toByte(), MODIFIER_NONE)
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
                    '@' -> HidKey(0x14.toByte(), MODIFIER_ALT_GR)
                    else -> null
                }
            }

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

        // Composite USB HID Descriptor:
        // - Report ID 1: Boot-compatible Keyboard (8-byte Input + 1-byte LED Output)
        // - Report ID 2: 3-Button Relative Mouse + Vertical Scroll Wheel (4-byte Input)
        // - Report ID 3: Consumer Media/Volume Keys (2-byte Input)
        val HID_COMBO_REPORT_DESCRIPTOR = byteArrayOf(
            // ================= KEYBOARD (Report ID 1) =================
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
            0xC0.toByte(),                // End Collection

            // ================= MOUSE (Report ID 2) =================
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x02.toByte(), //   Report ID (2)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(), //     Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //     Usage Minimum (Button 1 - Left)
            0x29.toByte(), 0x03.toByte(), //     Usage Maximum (Button 3 - Middle)
            0x15.toByte(), 0x00.toByte(), //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x75.toByte(), 0x01.toByte(), //     Report Size (1)
            0x81.toByte(), 0x02.toByte(), //     Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
            0x75.toByte(), 0x05.toByte(), //     Report Size (5)
            0x81.toByte(), 0x01.toByte(), //     Input (Constant) - 5 padding bits
            0x05.toByte(), 0x01.toByte(), //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //     Usage (X)
            0x09.toByte(), 0x31.toByte(), //     Usage (Y)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x03.toByte(), //     Report Count (3)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0xC0.toByte(),                //   End Collection (Physical)
            0xC0.toByte(),                // End Collection (Application)

            // ================= CONSUMER MEDIA (Report ID 3) =================
            0x05.toByte(), 0x0C.toByte(), // Usage Page (Consumer Devices)
            0x09.toByte(), 0x01.toByte(), // Usage (Consumer Control)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x03.toByte(), //   Report ID (3)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x26.toByte(), 0xFF.toByte(), 0x03.toByte(), // Logical Maximum (1023)
            0x19.toByte(), 0x00.toByte(), //   Usage Minimum (0)
            0x2A.toByte(), 0xFF.toByte(), 0x03.toByte(), // Usage Maximum (1023)
            0x75.toByte(), 0x10.toByte(), //   Report Size (16)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x81.toByte(), 0x00.toByte(), //   Input (Data, Array, Absolute)
            0xC0.toByte()                 // End Collection
        )
    }
}
