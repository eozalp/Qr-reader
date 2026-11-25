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
import com.example.debug.AppDebugLogger
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class HidConnectionStatus(val label: String) {
    DISCONNECTED("Bağlantı Yok"),
    CONNECTING("Bağlanıyor..."),
    CONNECTED("Bilgisayara Bağlı"),
    BLUETOOTH_OFF("Bluetooth Kapalı"),
    PERMISSION_REQUIRED("Bluetooth İzni Gerekli"),
    NOT_SUPPORTED("HID Desteklenmiyor (Sanal Test Modu)")
}

enum class HidProfileMode(val label: String) {
    COMBO("Kombo (Klavye + Mouse)"),
    SIMPLE_KEYBOARD("Saf Klavye (Linux / Uyumluluk)")
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
    val isConnected: Boolean,
    val isAndroidConnected: Boolean = false,
    val isComputer: Boolean = false
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

data class HidProtocolTelemetry(
    val isBootMode: Boolean = false,
    val qosLabel: String = "Henüz kayıtlı değil",
    val keyboardReportsSent: Long = 0L,
    val keyboardReportsFailed: Long = 0L,
    val mouseReportsSent: Long = 0L,
    val mouseReportsFailed: Long = 0L,
    val hostAckCount: Long = 0L,
    val lastFailureReason: String? = null
)

/**
 * Production-grade Bluetooth Classic HID Combo Keyboard & Mouse manager (`BluetoothHidDevice`).
 *
 * Key architectural fixes for zero stuck/repeating keys & reliable PC mouse/air-mouse:
 * 1. Registers with low-latency HID QoS (`latency = 11250us`, 11.25ms) with automatic fallback tiers
 *    to prevent Bluetooth Classic controllers from dropping into 500–750ms Sniff Mode stalls.
 * 2. Preserves Report ID 1 (Keyboard) and Report ID 2 (Mouse: 4-byte Report / 3-byte Boot) across
 *    `onSetProtocol`, preventing Boot Mode negotiation from muting mouse input or breaking Report IDs.
 * 3. Wakes idle ACL links (>300ms) with a clean zero-report before Key-Down and paces Key-Down /
 *    Key-Up across distinct HID slots (12–50ms hold, 10–45ms gap, +10ms on identical keys).
 * 4. Bounds failed mouse TX retries and surfaces live keyboard/mouse protocol telemetry diagnostics.
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
    private var isIntentionalUnregister: Boolean = false
    @Volatile
    private var qosTierIndex: Int = 0
    @Volatile
    private var protocolNegotiatedForConnection: Boolean = false
    @Volatile
    private var isBootProtocolMode: Boolean = false
    @Volatile
    private var activeReportId: Int = REPORT_ID_KEYBOARD
    @Volatile
    private var activeMouseButtons: Int = 0
    private val liveHeldKeysLock = Any()
    private val liveHeldKeys = LinkedHashSet<Byte>()
    private var liveHeldModifiers: Byte = 0
    @Volatile
    private var pendingConnectAddress: String? = null
    @Volatile
    private var connectingTargetAddress: String? = null
    @Volatile
    private var connectingStartedAtMs: Long = 0L
    @Volatile
    private var autoReconnectAttempted: Boolean = false
    private val autoRegisterRetryPending = AtomicBoolean(false)
    private val connectAttemptSeq = AtomicLong(0L)
    private val registerAttemptSeq = AtomicLong(0L)
    private val aclConnectedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val compatFallbackTriedForAddress = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var activeConnectWatchdog: ScheduledFuture<*>? = null
    @Volatile
    private var activeRegisterWatchdog: ScheduledFuture<*>? = null
    @Volatile
    private var connectedAtMs: Long = 0L
    @Volatile
    private var lastReportSentAtMs: Long = 0L
    @Volatile
    private var lastMouseReportAtMs: Long = 0L
    @Volatile
    var lastSendWasSimulated: Boolean = false
        private set

    private val _hidProfileMode = MutableStateFlow(loadSavedProfileMode())
    val hidProfileMode: StateFlow<HidProfileMode> = _hidProfileMode.asStateFlow()

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

    private val _telemetry = MutableStateFlow(HidProtocolTelemetry())
    val telemetry: StateFlow<HidProtocolTelemetry> = _telemetry.asStateFlow()

    private fun loadSavedProfileMode(): HidProfileMode {
        val saved = prefs?.getString(KEY_HID_PROFILE_MODE, null)
        return HidProfileMode.entries.firstOrNull { it.name == saved } ?: HidProfileMode.COMBO
    }

    private fun currentKeyboardReportId(): Int =
        if (_hidProfileMode.value == HidProfileMode.SIMPLE_KEYBOARD) 0 else REPORT_ID_KEYBOARD

    // Dedicated NON-BLOCKING executor strictly for Android BluetoothHidDevice.Callback binder callbacks!
    // Never sleep or run slow operations here or onAppStatusChanged/onConnectionStateChanged will deadlock.
    private val callbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AlvetakHidCallback").apply { isDaemon = true }
    }
    // Dedicated scheduler for registration, retries, connection polling, and timeout watchdogs.
    private val controlExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AlvetakHidControl").apply { isDaemon = true }
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
    private val hidSendLock = Any()

    private val pendingMouseDx = AtomicInteger(0)
    private val pendingMouseDy = AtomicInteger(0)
    private val pendingMouseWheel = AtomicInteger(0)
    private val mousePumpScheduled = AtomicBoolean(false)

    // Track barcodes currently present in camera frame / pending confirmation to prevent frame-by-frame spam
    private val activeInFrameBarcodes = mutableSetOf<String>()
    private val lastSeenInFrameMs = mutableMapOf<String, Long>()
    private val dismissedUntilLeftFrame = mutableSetOf<String>()
    private var lastSentTimestamp: Long = 0L
    private var lastSentRawBarcode: String = ""

    private val btStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    AppDebugLogger.i("BT-HID", "ACTION_STATE_CHANGED state=$state")
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        cancelConnectWatchdog()
                        cancelRegisterWatchdog()
                        aclConnectedAddresses.clear()
                        clearConnectionState(stopService = true)
                        isAppRegistered = false
                        isRegistering = false
                        isProxyRequested = false
                        hidDeviceProfile = null
                    }
                    refreshState()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev = extractBluetoothDevice(intent)
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    AppDebugLogger.i("BT-HID", "ACTION_BOND_STATE_CHANGED dev=${dev?.address} bond=$bond")
                    refreshPairedDevices()
                    if (bond == BluetoothDevice.BOND_BONDED && dev != null) {
                        scheduleHidSyncBurst("bond_bonded_${dev.address}")
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev = extractBluetoothDevice(intent)
                    if (dev != null) {
                        aclConnectedAddresses.add(dev.address)
                        AppDebugLogger.i("BT-HID", "ACTION_ACL_CONNECTED dev=${dev.address} (${getDeviceDisplayName(dev)})")
                        refreshPairedDevices()
                        scheduleHidSyncBurst("acl_connected_${dev.address}")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev = extractBluetoothDevice(intent)
                    if (dev != null) {
                        aclConnectedAddresses.remove(dev.address)
                        AppDebugLogger.i("BT-HID", "ACTION_ACL_DISCONNECTED dev=${dev.address}")
                        if (connectedHostDevice?.address == dev.address) {
                            clearConnectionState(stopService = true)
                            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                            syncHidConnectionFromProxy("acl_disconnected")
                        }
                        refreshPairedDevices()
                    }
                }
                BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED -> {
                    val dev = extractBluetoothDevice(intent)
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    AppDebugLogger.i("BT-HID", "BROADCAST HID_CONNECTION_STATE_CHANGED dev=${dev?.address} state=$state")
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            if (dev != null) onHostConnected(dev)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (dev == null || connectedHostDevice?.address == dev.address) {
                                syncHidConnectionFromProxy("hid_broadcast_disconnected")
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        registerBluetoothReceiver()
        refreshState()
    }

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun registerBluetoothReceiver() {
        val ctx = context?.applicationContext ?: return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    addAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED)
                }
            }
            ContextCompat.registerReceiver(ctx, btStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { err ->
            AppDebugLogger.e("BT-HID", "Bluetooth receiver kaydedilemedi", err)
        }
    }

    /**
     * When ACL connects or bonding finishes, Android/Windows may establish the HID channel
     * slightly later without firing a fresh callback; probe proxy state briefly to catch it.
     */
    private fun scheduleHidSyncBurst(reason: String) {
        for (delayMs in longArrayOf(250L, 1000L, 2200L)) {
            controlExecutor.schedule({
                syncHidConnectionFromProxy("$reason+${delayMs}ms")
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Queries Android's hidden `BluetoothDevice.isConnected()` (the same signal Android Settings uses
     * to show "Bağlı" under a paired device). Returns:
     * - `true`: active physical ACL/LE link right now
     * - `false`: definitely NO physical Bluetooth link right now (prevents ghost "connected" states)
     * - `null`: reflection blocked/unavailable on this OS build
     */
    private fun querySystemAclConnected(device: BluetoothDevice): Boolean? {
        return runCatching {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            method.invoke(device) as? Boolean
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceSystemConnected(device: BluetoothDevice): Boolean {
        val sysAcl = querySystemAclConnected(device)
        if (sysAcl != null) return sysAcl
        if (device.address in aclConnectedAddresses) return true
        if (hasBluetoothPermissions()) {
            val gattConnected = runCatching {
                bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                    ?.any { it.address == device.address } == true
            }.getOrDefault(false)
            if (gattConnected) return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun syncHidConnectionFromProxy(reason: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasBluetoothPermissions()) return false
        val hid = hidDeviceProfile ?: return false
        val activeHosts = runCatching {
            hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
        }.onFailure {
            AppDebugLogger.w("BT-HID", "getDevicesMatchingConnectionStates($reason) hatası", it)
        }.getOrNull().orEmpty()

        for (candidate in activeHosts) {
            val hidState = runCatching { hid.getConnectionState(candidate) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
            val sysAcl = querySystemAclConnected(candidate)
            if (hidState == BluetoothProfile.STATE_CONNECTED && sysAcl != false) {
                if (connectedHostDevice?.address != candidate.address || _connectionStatus.value != HidConnectionStatus.CONNECTED) {
                    AppDebugLogger.i(
                        "BT-HID",
                        "Proxy senkronu bağlı host yakaladı ($reason): ${getDeviceDisplayName(candidate)} (${candidate.address}) sysAcl=$sysAcl"
                    )
                    onHostConnected(candidate)
                }
                return true
            } else {
                AppDebugLogger.w(
                    "BT-HID",
                    "Hayalet HID host yok sayıldı ($reason): ${candidate.address} hidState=$hidState sysAcl=$sysAcl"
                )
            }
        }

        // Also explicitly check connectingTargetAddress via getConnectionState(device)
        val targetAddr = connectingTargetAddress ?: pendingConnectAddress
        val adapter = bluetoothAdapter
        if (!targetAddr.isNullOrBlank() && adapter != null) {
            val dev = runCatching { adapter.getRemoteDevice(targetAddr) }.getOrNull()
            if (dev != null) {
                val state = runCatching { hid.getConnectionState(dev) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                val sysAcl = querySystemAclConnected(dev)
                if (state == BluetoothProfile.STATE_CONNECTED && sysAcl != false) {
                    AppDebugLogger.i("BT-HID", "getConnectionState($reason) STATE_CONNECTED yakaladı: ${dev.address} sysAcl=$sysAcl")
                    onHostConnected(dev)
                    return true
                }
            }
        }
        return false
    }

    private fun cancelConnectWatchdog() {
        activeConnectWatchdog?.cancel(false)
        activeConnectWatchdog = null
        connectingTargetAddress = null
        connectingStartedAtMs = 0L
    }

    private fun cancelRegisterWatchdog() {
        activeRegisterWatchdog?.cancel(false)
        activeRegisterWatchdog = null
    }

    @SuppressLint("MissingPermission")
    fun refreshState() {
        if (bluetoothAdapter == null) {
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            cancelConnectWatchdog()
            cancelRegisterWatchdog()
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
            if (syncHidConnectionFromProxy("refreshState")) {
                refreshPairedDevices()
                return
            } else if (connectedHostDevice != null) {
                // Clear stale host reference if system dropped link silently
                clearConnectionState(stopService = true)
            }

            // Never stay permanently trapped in CONNECTING when user taps Refresh
            val connectingList = runCatching {
                hidDeviceProfile?.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTING))
            }.getOrNull()
            val connectingAgeMs = if (connectingStartedAtMs > 0L) System.currentTimeMillis() - connectingStartedAtMs else Long.MAX_VALUE
            val isActivelyConnecting = !connectingList.isNullOrEmpty() || (connectingAgeMs < 9500L && connectingTargetAddress != null)
            if (_connectionStatus.value == HidConnectionStatus.CONNECTING && !isActivelyConnecting && !isRegistering) {
                AppDebugLogger.w("BT-HID", "Takılı kalmış CONNECTING durumu refreshState ile temizlendi")
                cancelConnectWatchdog()
                pendingConnectAddress = null
                _connectionStatus.value = HidConnectionStatus.DISCONNECTED
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
        controlExecutor.execute {
            try {
                val deviceMap = LinkedHashMap<String, BluetoothDevice>()
                bluetoothAdapter.bondedDevices?.forEach { dev ->
                    deviceMap[dev.address] = dev
                }
                // Also include any GATT-connected devices so Dual-mode hosts are never missed
                runCatching {
                    bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { dev ->
                        deviceMap.putIfAbsent(dev.address, dev)
                    }
                }

                val activeAddr = connectedHostDevice?.address
                val enriched = deviceMap.values.map { dev ->
                    val displayName = getDeviceDisplayName(dev)
                    val androidConnected = isDeviceSystemConnected(dev)
                    val isComputer = runCatching {
                        dev.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
                    }.getOrDefault(false)
                    PairedBluetoothDevice(
                        name = displayName,
                        address = dev.address,
                        isConnected = activeAddr == dev.address,
                        isAndroidConnected = androidConnected || (activeAddr == dev.address),
                        isComputer = isComputer
                    )
                }.sortedWith(
                    compareByDescending<PairedBluetoothDevice> { it.isConnected }
                        .thenByDescending { it.isAndroidConnected }
                        .thenByDescending { it.isComputer }
                        .thenBy { it.name.lowercase() }
                )

                _pairedDevices.value = enriched
                val androidConnectedNames = enriched.filter { it.isAndroidConnected }.joinToString { "${it.name}(${it.address})" }
                AppDebugLogger.d(
                    "BT-HID",
                    "Eşleşmiş cihazlar yenilendi: toplam=${enriched.size}, Android bağlı=[$androidConnectedNames]"
                )
            } catch (e: Exception) {
                AppDebugLogger.e("BT-HID", "Eşleşmiş cihaz listesi okunamadı", e)
            }
        }
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
                        AppDebugLogger.i("BT-HID", "HID ProfileProxy bağlandı (profile=$profile)")
                        if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                            hidDeviceProfile = proxy
                            registerHidApp(proxy)
                            refreshState()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        isProxyRequested = false
                        AppDebugLogger.w("BT-HID", "HID ProfileProxy kesildi (profile=$profile, intentional=$isIntentionalUnregister)")
                        if (profile == BluetoothProfile.HID_DEVICE) {
                            hidDeviceProfile = null
                            isAppRegistered = false
                            isRegistering = false
                            if (!isIntentionalUnregister) {
                                clearConnectionState(stopService = true)
                                _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                            }
                        }
                    }
                },
                BluetoothProfile.HID_DEVICE
            )
            if (!ok) {
                isProxyRequested = false
                AppDebugLogger.w("BT-HID", "getProfileProxy(HID_DEVICE) false döndü")
                _connectionDiagnostic.value = "Android HID servisi şu an meşgul; yenileyip tekrar deneyin."
            }
        } catch (e: Exception) {
            isProxyRequested = false
            AppDebugLogger.e("BT-HID", "initHidProfile istisnası", e)
            _connectionStatus.value = HidConnectionStatus.NOT_SUPPORTED
            _connectionDiagnostic.value = "HID servisi başlatılamadı: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp(hid: BluetoothHidDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasBluetoothPermissions()) return
        if (isAppRegistered || isRegistering) return
        isRegistering = true
        val regSeq = registerAttemptSeq.incrementAndGet()

        controlExecutor.execute {
            try {
                val mode = _hidProfileMode.value
                val isSimpleKb = mode == HidProfileMode.SIMPLE_KEYBOARD
                activeReportId = currentKeyboardReportId()
                val sdp = if (isSimpleKb) {
                    BluetoothHidDeviceAppSdpSettings(
                        "ALVETAK QR Klavye",
                        "ALVETAK Bluetooth Klavye (Uyumluluk)",
                        "ALVETAK",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        HID_SIMPLE_KEYBOARD_DESCRIPTOR
                    )
                } else {
                    BluetoothHidDeviceAppSdpSettings(
                        "ALVETAK QR Klavye & Mouse",
                        "ALVETAK Kablosuz Klavye, Mouse & Air Mouse",
                        "ALVETAK",
                        BluetoothHidDevice.SUBCLASS1_COMBO,
                        HID_COMBO_REPORT_DESCRIPTOR
                    )
                }

                var started = false
                // Simple Keyboard mode always uses Compatibility QoS (null, null) for maximum Linux/BlueZ/macOS compatibility
                val tierUsed = if (isSimpleKb) 2 else qosTierIndex.coerceIn(0, 2)
                for (tier in tierUsed..2) {
                    val (inQos, outQos, tierLabel) = buildQosTier(tier)
                    qosTierIndex = tier
                    val modeTag = if (isSimpleKb) "Saf Klavye · $tierLabel" else tierLabel
                    _telemetry.value = _telemetry.value.copy(qosLabel = modeTag)
                    var attemptOk = hid.registerApp(sdp, inQos, outQos, callbackExecutor, hidCallback)
                    AppDebugLogger.i("BT-HID", "registerApp mode=$mode tier=$tier ($modeTag) sonuç=$attemptOk")
                    if (!attemptOk && tier == tierUsed) {
                        isIntentionalUnregister = true
                        runCatching { hid.unregisterApp() }
                            .onFailure { AppDebugLogger.w("BT-HID", "unregisterApp ön temizleme hatası", it) }
                        sleepQuietly(380L)
                        isIntentionalUnregister = false
                        attemptOk = hid.registerApp(sdp, inQos, outQos, callbackExecutor, hidCallback)
                        AppDebugLogger.i("BT-HID", "registerApp tekrar deneme tier=$tier sonuç=$attemptOk")
                    }
                    if (attemptOk) {
                        started = true
                        break
                    }
                    if (tier < 2) sleepQuietly(150L)
                }

                if (started) {
                    autoRegisterRetryPending.set(false)
                    // 3.5s registration watchdog: if onAppStatusChanged never fires, unblock and fall back
                    cancelRegisterWatchdog()
                    activeRegisterWatchdog = controlExecutor.schedule({
                        if (registerAttemptSeq.get() == regSeq && isRegistering && !isAppRegistered) {
                            AppDebugLogger.w("BT-HID", "registerApp 3.5s içinde onAppStatusChanged dönmedi (tier=$qosTierIndex); fallback deneniyor")
                            isRegistering = false
                            if (qosTierIndex < 2 && hidDeviceProfile != null) {
                                qosTierIndex += 1
                                registerHidApp(hidDeviceProfile!!)
                            } else {
                                if (_connectionStatus.value == HidConnectionStatus.CONNECTING) {
                                    cancelConnectWatchdog()
                                    pendingConnectAddress = null
                                    _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                                }
                                _connectionDiagnostic.value =
                                    "HID profili kayıt yanıtı zaman aşımına uğradı. 'HID Sıfırla'ya basın veya Bluetooth'u kapatıp açın."
                            }
                        }
                    }, 3500L, TimeUnit.MILLISECONDS)
                } else {
                    isRegistering = false
                    cancelRegisterWatchdog()
                    AppDebugLogger.e("BT-HID", "HID profili hiçbir QoS katmanında kaydedilemedi (1.8s sonra proxy yenilenecek)")
                    if (_connectionStatus.value == HidConnectionStatus.CONNECTING) {
                        cancelConnectWatchdog()
                        pendingConnectAddress = null
                        _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                    }
                    _connectionDiagnostic.value =
                        "HID profili kaydedilemedi. Başka bir BT klavye uygulaması açıksa kapatın veya 'HID Sıfırla'ya basın."
                    if (!autoRegisterRetryPending.getAndSet(true)) {
                        controlExecutor.schedule({
                            autoRegisterRetryPending.set(false)
                            if (!isAppRegistered && !isRegistering && bluetoothAdapter?.isEnabled == true) {
                                qosTierIndex = 0
                                rebindHidProxyInternal()
                            }
                        }, 1800L, TimeUnit.MILLISECONDS)
                    }
                }
            } catch (e: Exception) {
                isRegistering = false
                cancelRegisterWatchdog()
                if (_connectionStatus.value == HidConnectionStatus.CONNECTING) {
                    cancelConnectWatchdog()
                    pendingConnectAddress = null
                    _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                }
                AppDebugLogger.e("BT-HID", "HID registerApp istisnası", e)
                _connectionDiagnostic.value = "HID kayıt hatası: ${e.message}"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun rebindHidProxyInternal(targetTier: Int? = null, keepPendingAddress: String? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasBluetoothPermissions()) return
        val oldProxy = hidDeviceProfile
        isIntentionalUnregister = true
        pendingConnectAddress = keepPendingAddress
        if (oldProxy != null) {
            // Abort any stuck STATE_CONNECTING or stale STATE_CONNECTED links before unregistering!
            runCatching {
                oldProxy.getDevicesMatchingConnectionStates(
                    intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
                )?.forEach { dev ->
                    AppDebugLogger.d("BT-HID", "Rebind öncesi takılı HID soketi kapatılıyor: ${dev.address}")
                    runCatching { oldProxy.disconnect(dev) }
                }
            }
            sleepQuietly(120L)
            runCatching { oldProxy.unregisterApp() }
            runCatching { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, oldProxy) }
        }
        hidDeviceProfile = null
        isAppRegistered = false
        isRegistering = false
        isProxyRequested = false
        qosTierIndex = targetTier ?: 0
        sleepQuietly(360L)
        isIntentionalUnregister = false
        initHidProfile()
    }

    @SuppressLint("MissingPermission")
    fun setHidProfileMode(mode: HidProfileMode) {
        if (_hidProfileMode.value == mode) return
        val previousHostAddress = connectedHostDevice?.address
        _hidProfileMode.value = mode
        prefs?.edit()?.putString(KEY_HID_PROFILE_MODE, mode.name)?.apply()
        compatFallbackTriedForAddress.clear()
        AppDebugLogger.i(
            "BT-HID",
            "HID profil modu değiştirildi: ${mode.label} (autoReconnect=${previousHostAddress ?: "yok"})"
        )
        reregisterHidProfile(reconnectAddress = previousHostAddress)
    }

    @SuppressLint("MissingPermission")
    fun reregisterHidProfile(reconnectAddress: String? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !hasBluetoothPermissions()) return
        AppDebugLogger.i(
            "BT-HID",
            "Kullanıcı tarafından tam HID Proxy + Profil Sıfırlama tetiklendi (mode=${_hidProfileMode.value}, reconnect=${reconnectAddress ?: "yok"})"
        )
        connectAttemptSeq.incrementAndGet()
        cancelConnectWatchdog()
        cancelRegisterWatchdog()
        pendingConnectAddress = reconnectAddress
        compatFallbackTriedForAddress.clear()
        _connectionStatus.value = if (reconnectAddress != null) HidConnectionStatus.CONNECTING else HidConnectionStatus.DISCONNECTED
        _connectionDiagnostic.value = if (reconnectAddress != null) {
            "HID profili '${_hidProfileMode.value.label}' olarak yenilenip tekrar bağlanılıyor..."
        } else {
            "Bluetooth HID servisi sıfırlanıyor..."
        }
        controlExecutor.execute {
            rebindHidProxyInternal(keepPendingAddress = reconnectAddress)
        }
    }

    fun cancelConnecting() {
        connectAttemptSeq.incrementAndGet()
        val prevTarget = connectingTargetAddress ?: pendingConnectAddress
        cancelConnectWatchdog()
        pendingConnectAddress = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && prevTarget != null && hasBluetoothPermissions()) {
            controlExecutor.execute {
                runCatching {
                    val dev = bluetoothAdapter?.getRemoteDevice(prevTarget)
                    if (dev != null) hidDeviceProfile?.disconnect(dev)
                }
            }
        }
        if (_connectionStatus.value == HidConnectionStatus.CONNECTING) {
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            _connectionDiagnostic.value = "Bağlantı denemesi iptal edildi."
        }
        AppDebugLogger.i("BT-HID", "Kullanıcı bağlantı denemesini iptal etti (target=$prevTarget)")
    }

    private fun buildQosTier(tier: Int): Triple<BluetoothHidDeviceAppQosSettings?, BluetoothHidDeviceAppQosSettings?, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Triple(null, null, "Desteklenmiyor")
        return when (tier) {
            // Default to null/null (AOSP standard best-effort QoS) so Windows, Linux BlueZ, macOS,
            // and Samsung Android 17 never stall 10s on L2CAP Flow Spec QoS negotiation!
            0 -> Triple(null, null, "Standart Uyumlu QoS")
            1 -> Triple(
                null,
                BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                    800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
                ),
                "Düşük Gecikme Out-QoS (11.2ms)"
            )
            else -> Triple(null, null, "Uyumluluk Modu (Varsayılan QoS)")
        }
    }

    private val hidCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        object : BluetoothHidDevice.Callback() {
            @SuppressLint("MissingPermission")
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                cancelRegisterWatchdog()
                isRegistering = false
                isAppRegistered = registered
                AppDebugLogger.i(
                    "BT-HID",
                    "onAppStatusChanged registered=$registered tier=$qosTierIndex plugged=${pluggedDevice?.address ?: "null"}"
                )
                if (registered) {
                    _connectionDiagnostic.value = null
                    // CRITICAL: In Android BluetoothHidDevice.Callback.onAppStatusChanged, `pluggedDevice`
                    // is the LAST "virtually cabled" host remembered in NVRAM — NOT necessarily connected!
                    // Always verify live STATE_CONNECTED + physical ACL link before marking connected.
                    val pluggedHidState = if (pluggedDevice != null) {
                        runCatching { hidDeviceProfile?.getConnectionState(pluggedDevice) }
                            .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                    } else BluetoothProfile.STATE_DISCONNECTED
                    val pluggedSysAcl = if (pluggedDevice != null) querySystemAclConnected(pluggedDevice) else null

                    if (pluggedDevice != null && pluggedHidState == BluetoothProfile.STATE_CONNECTED && pluggedSysAcl != false) {
                        AppDebugLogger.i(
                            "BT-HID",
                            "pluggedDevice aktif bağlı doğrulandı: ${getDeviceDisplayName(pluggedDevice)} (${pluggedDevice.address})"
                        )
                        onHostConnected(pluggedDevice)
                    } else {
                        if (pluggedDevice != null) {
                            AppDebugLogger.i(
                                "BT-HID",
                                "pluggedDevice=${getDeviceDisplayName(pluggedDevice)} (${pluggedDevice.address}) sanal kablo kaydı var ancak şu an bağlı değil (hidState=$pluggedHidState, sysAcl=$pluggedSysAcl)"
                            )
                        }
                        if (!syncHidConnectionFromProxy("onAppStatusChanged")) {
                            val queued = pendingConnectAddress
                            if (queued != null) {
                                pendingConnectAddress = null
                                connectToDevice(queued)
                            } else if (!autoReconnectAttempted && connectedHostDevice == null) {
                                autoReconnectAttempted = true
                                val lastAddr = prefs?.getString(KEY_LAST_HOST_ADDRESS, null)
                                val lastDev = if (!lastAddr.isNullOrBlank()) {
                                    runCatching { bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == lastAddr } }.getOrNull()
                                } else null
                                // Only auto-reconnect if that host is ACTUALLY reachable/ACL-connected right now!
                                // Otherwise don't hijack the UI into CONNECTING for an absent PC.
                                if (lastDev != null && isDeviceSystemConnected(lastDev)) {
                                    AppDebugLogger.i("BT-HID", "Önceki host Android'de aktif bağlı; otomatik HID bağlanılıyor: ${lastDev.address}")
                                    connectToDevice(lastDev.address, isAutoReconnect = true)
                                } else {
                                    refreshPairedDevices()
                                }
                            }
                        }
                    }
                } else {
                    clearConnectionState(stopService = true)
                    if (isIntentionalUnregister) {
                        // Intentional rebind/fallback in progress — DO NOT wipe pendingConnectAddress!
                        AppDebugLogger.d("BT-HID", "Bilinçli unregister tamamlandı (pending=${pendingConnectAddress ?: "yok"})")
                    } else if (qosTierIndex < 2 && hidDeviceProfile != null) {
                        AppDebugLogger.w("BT-HID", "QoS tier=$qosTierIndex reddedildi; alt QoS katmanına geçiliyor")
                        qosTierIndex += 1
                        hidDeviceProfile?.let { registerHidApp(it) }
                    } else {
                        cancelConnectWatchdog()
                        pendingConnectAddress = null
                        _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                AppDebugLogger.i("BT-HID", "onConnectionStateChanged dev=${device?.address} state=$state")
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (device != null) {
                            onHostConnected(device)
                        }
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionStatus.value = HidConnectionStatus.CONNECTING
                        if (connectingStartedAtMs == 0L) connectingStartedAtMs = System.currentTimeMillis()
                        if (device != null) connectingTargetAddress = device.address
                        _connectionDiagnostic.value = "Bilgisayarla HID bağlantısı kuruluyor..."
                    }
                    BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                        val wasConnectedToThis = device != null && connectedHostDevice?.address == device.address
                        val wasConnectingToThis = device != null && (
                            connectingTargetAddress == device.address ||
                                (_connectionStatus.value == HidConnectionStatus.CONNECTING && connectedHostDevice == null)
                            )
                        if (wasConnectedToThis && device != null && hidDeviceProfile != null) {
                            // Only send zero release reports when an active HID link is dropping
                            hidDeviceProfile?.let { hid ->
                                sendRawHidReportImmediate(hid, device, activeReportId, newZeroReport())
                                if (_hidProfileMode.value == HidProfileMode.COMBO) {
                                    sendRawHidReportImmediate(
                                        hid,
                                        device,
                                        REPORT_ID_MOUSE,
                                        if (isBootProtocolMode) buildBootMouseReport(0, 0, 0) else buildMouseReport(0, 0, 0, 0)
                                    )
                                }
                            }
                        }

                        // AUTO-FALLBACK: Only fall back if currently on custom non-null QoS (tier == 1)
                        if (state == BluetoothProfile.STATE_DISCONNECTED &&
                            wasConnectingToThis &&
                            device != null &&
                            qosTierIndex == 1 &&
                            compatFallbackTriedForAddress.add(device.address)
                        ) {
                            val targetAddr = device.address
                            AppDebugLogger.w(
                                "BT-HID",
                                "Host ($targetAddr) özel QoS bağlantısını reddetti -> Standart Uyumlu QoS (null,null) ile yeniden deneniyor"
                            )
                            cancelConnectWatchdog()
                            _connectionStatus.value = HidConnectionStatus.CONNECTING
                            _connectionDiagnostic.value = "Özel QoS reddedildi — Standart Uyumlu QoS ile tekrar deneniyor..."
                            controlExecutor.execute {
                                rebindHidProxyInternal(targetTier = 0, keepPendingAddress = targetAddr)
                            }
                            return
                        }

                        val matchesActiveOrTarget = wasConnectedToThis || wasConnectingToThis || connectedHostDevice == null
                        if (matchesActiveOrTarget) {
                            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                cancelConnectWatchdog()
                            }
                            clearConnectionState(stopService = true)
                            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                val devName = getDeviceDisplayName(device)
                                val sysAcl = if (device != null) isDeviceSystemConnected(device) else false
                                _connectionDiagnostic.value = if (wasConnectingToThis && sysAcl) {
                                    "Android '$devName' ile bağlı ama bilgisayar HID (Klavye/Fare) kanalını reddetti. 'Saf Klavye (Linux)' modunu deneyin veya PC Bluetooth'tan telefonu kaldırıp ALVETAK açıkken yeniden eşleştirin."
                                } else if (wasConnectingToThis) {
                                    "'$devName' Bluetooth yanıtı vermedi (ACL kapalı). PC Bluetooth'unun açık olduğundan emin olun; gerekirse PC'den telefona 'Bağlan' deyin veya kaldırıp ALVETAK açıkken yeniden eşleştirin."
                                } else {
                                    "Bağlantı kesildi ($devName)."
                                }
                            }
                            refreshPairedDevices()
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val baseSize = when {
                        type == BluetoothHidDevice.REPORT_TYPE_OUTPUT -> 1
                        id == REPORT_ID_MOUSE.toByte() -> if (isBootProtocolMode) 3 else 4
                        id == REPORT_ID_CONSUMER.toByte() -> 2
                        else -> 8
                    }
                    val safeSize = if (bufferSize > 0) minOf(baseSize, bufferSize) else baseSize
                    _telemetry.value = _telemetry.value.copy(hostAckCount = _telemetry.value.hostAckCount + 1L)
                    AppDebugLogger.i("BT-HID", "onGetReport type=$type id=$id buf=$bufferSize replySize=$safeSize boot=$isBootProtocolMode")
                    runCatching {
                        hidDeviceProfile?.replyReport(device, type, id, ByteArray(safeSize))
                    }.onFailure { err ->
                        AppDebugLogger.e("BT-HID", "replyReport hatası (id=$id)", err)
                    }
                }
            }

            override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    protocolNegotiatedForConnection = true
                    isBootProtocolMode = (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE)
                    activeReportId = currentKeyboardReportId()
                    _telemetry.value = _telemetry.value.copy(
                        isBootMode = isBootProtocolMode,
                        hostAckCount = _telemetry.value.hostAckCount + 1L
                    )
                    AppDebugLogger.i("BT-HID", "onSetProtocol protocol=$protocol (isBootMode=$isBootProtocolMode, reportId=$activeReportId)")
                }
            }

            @SuppressLint("MissingPermission")
            override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
                val payloadHex = data?.joinToString(" ") { String.format("%02X", it) } ?: ""
                _telemetry.value = _telemetry.value.copy(hostAckCount = _telemetry.value.hostAckCount + 1L)
                AppDebugLogger.i("BT-HID", "onSetReport type=$type id=$id len=${data?.size ?: 0} data=[$payloadHex]")
                if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // HID 1.1 Control Channel requires HANDSHAKE(SUCCESSFUL) for SET_REPORT (e.g., Windows LED sync)
                    runCatching {
                        hidDeviceProfile?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
                    }.onFailure { err ->
                        AppDebugLogger.e("BT-HID", "onSetReport ACK (reportError SUCCESS) hatası", err)
                    }
                }
            }

            override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
                val payloadHex = data?.joinToString(" ") { String.format("%02X", it) } ?: ""
                _telemetry.value = _telemetry.value.copy(hostAckCount = _telemetry.value.hostAckCount + 1L)
                AppDebugLogger.i("BT-HID", "onInterruptData id=$reportId len=${data?.size ?: 0} data=[$payloadHex]")
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice?) {
                AppDebugLogger.w("BT-HID", "onVirtualCableUnplug dev=${device?.address}")
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
        if (connectedHostDevice?.address == device.address && _connectionStatus.value == HidConnectionStatus.CONNECTED) {
            // Avoid duplicate log/service/state churn when both Callback and Broadcast fire for the same connection
            return
        }
        cancelConnectWatchdog()
        pendingConnectAddress = null
        compatFallbackTriedForAddress.remove(device.address)
        connectedHostDevice = device
        connectedAtMs = System.currentTimeMillis()
        if (!protocolNegotiatedForConnection) {
            isBootProtocolMode = false
            activeReportId = currentKeyboardReportId()
        }
        val name = getDeviceDisplayName(device)
        AppDebugLogger.i(
            "BT-HID",
            "Host bağlandı: $name (${device.address}) · ${_telemetry.value.qosLabel} · mode=${_hidProfileMode.value} · boot=$isBootProtocolMode · reportId=$activeReportId"
        )
        _connectedDeviceName.value = name
        _connectionStatus.value = HidConnectionStatus.CONNECTED
        _connectionDiagnostic.value = null
        _telemetry.value = _telemetry.value.copy(
            isBootMode = isBootProtocolMode,
            keyboardReportsSent = 0L,
            keyboardReportsFailed = 0L,
            mouseReportsSent = 0L,
            mouseReportsFailed = 0L,
            hostAckCount = 0L,
            lastFailureReason = null
        )
        prefs?.edit()?.putString(KEY_LAST_HOST_ADDRESS, device.address)?.apply()
        context?.let { BluetoothHidForegroundService.startOrUpdate(it, name) }
        refreshPairedDevices()
    }

    private fun clearConnectionState(stopService: Boolean) {
        connectedHostDevice = null
        connectedAtMs = 0L
        protocolNegotiatedForConnection = false
        isBootProtocolMode = false
        activeReportId = currentKeyboardReportId()
        activeMouseButtons = 0
        pendingMouseDx.set(0)
        pendingMouseDy.set(0)
        pendingMouseWheel.set(0)
        _connectedDeviceName.value = null
        _telemetry.value = _telemetry.value.copy(
            isBootMode = false,
            hostAckCount = 0L
        )
        if (stopService) {
            context?.let { BluetoothHidForegroundService.stop(it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String, isAutoReconnect: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!hasBluetoothPermissions()) {
            _connectionStatus.value = HidConnectionStatus.PERMISSION_REQUIRED
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionStatus.value = HidConnectionStatus.BLUETOOTH_OFF
            return
        }

        val attemptId = connectAttemptSeq.incrementAndGet()
        cancelConnectWatchdog()
        connectingTargetAddress = address
        connectingStartedAtMs = System.currentTimeMillis()

        val hid = hidDeviceProfile
        if (hid == null || !isAppRegistered) {
            pendingConnectAddress = address
            _connectionStatus.value = HidConnectionStatus.CONNECTING
            _connectionDiagnostic.value = "HID profili hazırlanıyor, biraz bekleyin..."
            // Guard against hanging in CONNECTING if profile/registration never finishes
            activeConnectWatchdog = controlExecutor.schedule({
                if (connectAttemptSeq.get() == attemptId && _connectionStatus.value == HidConnectionStatus.CONNECTING) {
                    AppDebugLogger.w("BT-HID", "HID hazırlama zaman aşımı (10s) target=$address reg=$isAppRegistered proxy=${hidDeviceProfile != null}")
                    cancelConnectWatchdog()
                    pendingConnectAddress = null
                    _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                    _connectionDiagnostic.value = if (isAutoReconnect) {
                        null
                    } else {
                        "HID servisi hazırlanamadı. 'HID Sıfırla'ya basın veya Bluetooth'u kapatıp açın."
                    }
                }
            }, 10000L, TimeUnit.MILLISECONDS)

            if (hid == null) {
                initHidProfile()
            } else {
                registerHidApp(hid)
            }
            return
        }

        controlExecutor.execute {
            try {
                val device = bluetoothAdapter.getRemoteDevice(address) ?: return@execute
                val name = getDeviceDisplayName(device)
                val bondState = runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
                val deviceType = runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN)
                val deviceTypeLabel = when (deviceType) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                    BluetoothDevice.DEVICE_TYPE_LE -> "LE_ONLY"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                    else -> "UNKNOWN($deviceType)"
                }
                val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
                val uuidsCount = runCatching { device.uuids?.size ?: 0 }.getOrDefault(0)
                val preHidState = runCatching { hid.getConnectionState(device) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                val alreadyConnectedList = runCatching {
                    hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                }.getOrNull().orEmpty()

                val sysAclNow = isDeviceSystemConnected(device)
                AppDebugLogger.i(
                    "BT-HID",
                    "connectToDevice($address / $name) type=$deviceTypeLabel class=$majorClass uuids=$uuidsCount bond=$bondState preHidState=$preHidState activeHidHosts=${alreadyConnectedList.size} sysAcl=$sysAclNow mode=${_hidProfileMode.value} tier=$qosTierIndex auto=$isAutoReconnect"
                )

                // Warn clearly if paired exclusively as BLE (BluetoothHidDevice requires Classic BR/EDR!)
                if (deviceType == BluetoothDevice.DEVICE_TYPE_LE && !isAutoReconnect) {
                    AppDebugLogger.w("BT-HID", "$name ($address) yalnızca BLE (DEVICE_TYPE_LE) eşleşmiş; Classic HID reddedilebilir")
                }

                // Fast-path: if Android already has this host connected via HID AND physical link is alive
                if ((preHidState == BluetoothProfile.STATE_CONNECTED || alreadyConnectedList.any { it.address == address }) && querySystemAclConnected(device) != false) {
                    AppDebugLogger.i("BT-HID", "Cihaz zaten HID bağlı ($address); anında CONNECTED durumuna geçiliyor")
                    onHostConnected(device)
                    return@execute
                }

                _connectionStatus.value = HidConnectionStatus.CONNECTING
                if (!isAutoReconnect) {
                    _connectionDiagnostic.value = "$name cihazına HID bağlantısı kuruluyor..."
                }

                // If this exact device is already stuck in STATE_CONNECTING (e.g. lingering L2CAP attempt),
                // abort the stale pending attempt first so hid.connect() starts a clean handshake!
                if (preHidState == BluetoothProfile.STATE_CONNECTING) {
                    AppDebugLogger.w("BT-HID", "Cihaz zaten takılı STATE_CONNECTING ($address); önce temizleniyor")
                    runCatching { hid.disconnect(device) }
                    sleepQuietly(350L)
                }

                // Disconnect any OTHER previous host first and give Bluetooth stack a short settle gap
                val otherConnected = alreadyConnectedList.filter { it.address != address }
                if (otherConnected.isNotEmpty() || (connectedHostDevice != null && connectedHostDevice?.address != address)) {
                    otherConnected.forEach { runCatching { hid.disconnect(it) } }
                    connectedHostDevice?.takeIf { it.address != address }?.let { runCatching { hid.disconnect(it) } }
                    sleepQuietly(250L)
                }

                if (connectAttemptSeq.get() != attemptId) return@execute

                val initiated = hid.connect(device)
                val postHidState = runCatching { hid.getConnectionState(device) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                AppDebugLogger.i(
                    "BT-HID",
                    "hid.connect(${device.address}) initiated=$initiated postHidState=$postHidState tier=$qosTierIndex mode=${_hidProfileMode.value} auto=$isAutoReconnect"
                )

                if (postHidState == BluetoothProfile.STATE_CONNECTED) {
                    onHostConnected(device)
                    return@execute
                }

                if (!initiated) {
                    if (!isAutoReconnect && qosTierIndex == 1 && compatFallbackTriedForAddress.add(address)) {
                        AppDebugLogger.w("BT-HID", "hid.connect($address) false döndü (tier=$qosTierIndex); Standart Uyumlu QoS ile yeniden deneniyor")
                        cancelConnectWatchdog()
                        _connectionStatus.value = HidConnectionStatus.CONNECTING
                        _connectionDiagnostic.value = "Özel QoS kabul edilmedi — Standart Uyumlu QoS ile yeniden deneniyor..."
                        rebindHidProxyInternal(targetTier = 0, keepPendingAddress = address)
                        return@execute
                    }
                    cancelConnectWatchdog()
                    _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                    _connectionDiagnostic.value = if (isAutoReconnect) {
                        null
                    } else if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                        "'$name' yalnızca BLE (Low Energy) olarak eşleşmiş. HID Klavye için Classic Bluetooth gerekir — cihazı kaldırıp yeniden eşleştirin."
                    } else {
                        "Bağlantı başlatılamadı (hid.connect=false). 'Saf Klavye (Linux/Uyumluluk)' modunu seçip PC/Linux Bluetooth'tan telefonu kaldırıp ALVETAK açıkken yeniden eşleştirin."
                    }
                    return@execute
                }

                // Active Connection Poller + 4s Auto-Retry + 10s Hard Timeout Watchdog
                // Fixes Samsung/Android 15-17 silently dropping or swallowing onConnectionStateChanged!
                var retryTriggered = false
                val startMs = System.currentTimeMillis()
                activeConnectWatchdog = controlExecutor.scheduleAtFixedRate({
                    if (connectAttemptSeq.get() != attemptId || _connectionStatus.value != HidConnectionStatus.CONNECTING) {
                        activeConnectWatchdog?.cancel(false)
                        return@scheduleAtFixedRate
                    }
                    val currentProxy = hidDeviceProfile
                    if (currentProxy == null) {
                        cancelConnectWatchdog()
                        _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                        return@scheduleAtFixedRate
                    }

                    // 1) Check BOTH getConnectionState and getDevicesMatchingConnectionStates
                    if (syncHidConnectionFromProxy("connect_poll")) {
                        return@scheduleAtFixedRate
                    }
                    val polledState = runCatching { currentProxy.getConnectionState(device) }
                        .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                    if (polledState == BluetoothProfile.STATE_CONNECTED) {
                        onHostConnected(device)
                        return@scheduleAtFixedRate
                    }

                    val elapsedMs = System.currentTimeMillis() - startMs

                    // 2) At ~4s, if Android silently dropped back to DISCONNECTED without firing callback, retry once!
                    if (!retryTriggered && elapsedMs >= 3800L && polledState == BluetoothProfile.STATE_DISCONNECTED) {
                        retryTriggered = true
                        AppDebugLogger.w("BT-HID", "4s içinde HID yanıtı gelmedi (state=DISCONNECTED); hid.connect($address) tekrar deneniyor")
                        val retryOk = runCatching { currentProxy.connect(device) }.getOrDefault(false)
                        AppDebugLogger.i("BT-HID", "hid.connect tekrar deneme ($address) sonuç=$retryOk")
                        if (!isAutoReconnect) {
                            _connectionDiagnostic.value = "$name ile HID el sıkışması tekrar deneniyor..."
                        }
                        return@scheduleAtFixedRate
                    }

                    // 3) Hard 10s timeout -> auto-fallback to Compatibility QoS once if on custom QoS, else cleanly exit!
                    if (elapsedMs >= 10000L) {
                        val isAclConnected = isDeviceSystemConnected(device)
                        if (!isAutoReconnect && qosTierIndex == 1 && compatFallbackTriedForAddress.add(address)) {
                            AppDebugLogger.w(
                                "BT-HID",
                                "10s zaman aşımı (tier=$qosTierIndex, dev=$address); Standart Uyumlu QoS ile otomatik yeniden deneniyor"
                            )
                            runCatching { currentProxy.disconnect(device) }
                            cancelConnectWatchdog()
                            _connectionStatus.value = HidConnectionStatus.CONNECTING
                            _connectionDiagnostic.value = "Özel QoS yanıt vermedi — Standart Uyumlu QoS ile tekrar deneniyor..."
                            rebindHidProxyInternal(targetTier = 0, keepPendingAddress = address)
                            return@scheduleAtFixedRate
                        }
                        AppDebugLogger.e(
                            "BT-HID",
                            "Bağlantı zaman aşımı (10s): dev=$address ($name) type=$deviceTypeLabel polledHidState=$polledState bond=$bondState aclConnected=$isAclConnected reg=$isAppRegistered mode=${_hidProfileMode.value} tier=$qosTierIndex"
                        )
                        runCatching { currentProxy.disconnect(device) }
                        cancelConnectWatchdog()
                        _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                        _connectionDiagnostic.value = if (isAutoReconnect) {
                            null
                        } else if (deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                            "'$name' yalnızca BLE (Low Energy) eşleşmiş. HID Klavye için Classic Bluetooth gerekir — cihazı kaldırıp yeniden eşleştirin."
                        } else if (isAclConnected) {
                            "Android '$name' cihazına bağlı ancak bilgisayar HID (Klavye/Fare) kanalını açmadı. 'Saf Klavye (Linux)' modunu deneyin veya PC/Linux Bluetooth'tan telefonu 'Kaldır' yapıp ALVETAK açıkken yeniden eşleştirin."
                        } else {
                            "'$name' cihazı 10 sn içinde HID bağlantısını kabul etmedi. PC Bluetooth açıkken tekrar deneyin veya 'HID Sıfırla'ya basın."
                        }
                    }
                }, 450L, 450L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                cancelConnectWatchdog()
                AppDebugLogger.e("BT-HID", "connectToDevice($address) istisnası", e)
                _connectionStatus.value = HidConnectionStatus.DISCONNECTED
                _connectionDiagnostic.value = if (isAutoReconnect) null else "Bağlantı hatası: ${e.message}"
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice() {
        connectAttemptSeq.incrementAndGet()
        autoReconnectAttempted = true
        prefs?.edit()?.remove(KEY_LAST_HOST_ADDRESS)?.apply()
        cancelConnectWatchdog()
        pendingConnectAddress = null
        pendingMouseDx.set(0)
        pendingMouseDy.set(0)
        pendingMouseWheel.set(0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || hidDeviceProfile == null) {
            clearConnectionState(stopService = true)
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            refreshPairedDevices()
            return
        }
        if (!hasBluetoothPermissions()) return

        try {
            hidDeviceProfile?.let { hid ->
                val toDisconnect = LinkedHashMap<String, BluetoothDevice>()
                connectedHostDevice?.let { toDisconnect[it.address] = it }
                runCatching {
                    hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING))
                        ?.forEach { toDisconnect[it.address] = it }
                }
                toDisconnect.values.forEach { dev ->
                    sendRawHidReportImmediate(hid, dev, activeReportId, newZeroReport())
                    sendRawHidReportImmediate(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(0, 0, 0, 0))
                    runCatching { hid.disconnect(dev) }
                }
            }
            clearConnectionState(stopService = true)
            _connectionStatus.value = HidConnectionStatus.DISCONNECTED
            _connectionDiagnostic.value = null
            refreshPairedDevices()
        } catch (e: Exception) {
            AppDebugLogger.e("BT-HID", "disconnectDevice hatası", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun shutdown() {
        connectAttemptSeq.incrementAndGet()
        cancelConnectWatchdog()
        cancelRegisterWatchdog()
        pendingConnectAddress = null
        pendingMouseDx.set(0)
        pendingMouseDy.set(0)
        pendingMouseWheel.set(0)
        context?.applicationContext?.let { ctx ->
            runCatching { ctx.unregisterReceiver(btStateReceiver) }
                .onFailure { AppDebugLogger.w("BT-HID", "unregisterReceiver uyarısı", it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hidDeviceProfile?.let { hid ->
                runCatching {
                    connectedHostDevice?.let { dev ->
                        sendRawHidReportImmediate(hid, dev, activeReportId, newZeroReport())
                        sendRawHidReportImmediate(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(0, 0, 0, 0))
                    }
                    hid.unregisterApp()
                    bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                }.onFailure { err ->
                    AppDebugLogger.w("BT-HID", "shutdown temizleme hatası", err)
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
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { device.alias }.getOrNull()?.takeIf { it.isNotBlank() }
            } else null
            alias ?: device.name?.takeIf { it.isNotBlank() } ?: device.address
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
            raw !in visibleRawBarcodes && (nowMs - prevSeen) > 1200L
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
        typingDelayMs: Long = 10L,
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
                    lastSendWasSimulated = false
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
                    lastSendWasSimulated = true
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

    @SuppressLint("MissingPermission")
    private fun sendRawHidReport(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        reportId: Int,
        report: ByteArray
    ): Boolean = synchronized(hidSendLock) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@synchronized false
        if (connectedHostDevice?.address != device.address) return@synchronized false
        runCatching { hid.sendReport(device, reportId, report) }
            .onFailure { err ->
                AppDebugLogger.e("BT-HID", "sendReport istisnası (id=$reportId len=${report.size})", err)
            }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun sendRawHidReportImmediate(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        reportId: Int,
        report: ByteArray
    ): Boolean = synchronized(hidSendLock) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@synchronized false
        runCatching { hid.sendReport(device, reportId, report) }
            .onFailure { err ->
                AppDebugLogger.w("BT-HID", "sendRawHidReportImmediate istisnası (id=$reportId)", err)
            }
            .getOrDefault(false)
    }

    /**
     * Sends a single classic-keyboard HID stroke (Backspace, Tab, Enter, or typed char)
     * strictly serialized on the dedicated HID TX thread with guaranteed Key-Up release.
     */
    suspend fun sendSingleHidKey(
        keyCode: Byte,
        modifier: Byte = MODIFIER_NONE,
        typingDelayMs: Long = 10L
    ): Boolean = sendMutex.withLock {
        withContext(hidTxDispatcher) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext false
            val hid = hidDeviceProfile ?: return@withContext false
            val dev = connectedHostDevice ?: return@withContext false

            ensureHostReadyForReports(hid, dev)
            val timing = computeKeyTiming(typingDelayMs, isSameKeyCodeAsPrev = false)
            var keyReleasedCleanly = false
            try {
                // Single interactive taps don't need a full inter-character string gap after Key-Up
                val ok = sendKeyStrokeBlocking(hid, dev, keyCode, modifier, timing.holdMs, gapMs = 2L)
                keyReleasedCleanly = ok
                ok
            } finally {
                if (!keyReleasedCleanly) {
                    sendZeroReportWithRetry(hid, dev, maxAttempts = 12)
                }
            }
        }
    }

    suspend fun sendSingleCharacter(
        ch: Char,
        layout: KeyboardLayoutType = KeyboardLayoutType.TR_Q,
        typingDelayMs: Long = 10L,
        extraModifiers: Byte = MODIFIER_NONE
    ): Boolean {
        val normalized = if (layout == KeyboardLayoutType.US_Q) {
            transliterateTurkish(ch.toString()).firstOrNull() ?: ch
        } else {
            ch
        }
        if (normalized == '₺') {
            return sendToComputer(
                textToSend = "TL",
                rawBarcodeKey = "₺",
                endingKey = KeyboardEndingKey.NONE,
                typingDelayMs = typingDelayMs,
                convertTurkishChars = false,
                keyboardLayout = layout
            )
        }
        val hidKey = charToHidKey(normalized, layout) ?: return false
        val combinedMod = (hidKey.modifier.toInt() or extraModifiers.toInt()).toByte()
        return sendSingleHidKey(hidKey.keyCode, combinedMod, typingDelayMs)
    }

    suspend fun sendHidKeyCombo(
        keyCode: Byte,
        modifiers: Byte = MODIFIER_NONE,
        typingDelayMs: Long = 10L
    ): Boolean = sendSingleHidKey(keyCode, modifiers, typingDelayMs)

    @SuppressLint("MissingPermission")
    suspend fun sendConsumerControl(usageCode: Int): Boolean = sendMutex.withLock {
        withContext(hidTxDispatcher) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext false
            if (isBootProtocolMode) return@withContext false
            val hid = hidDeviceProfile ?: return@withContext false
            val dev = connectedHostDevice ?: return@withContext false
            val press = buildConsumerReport(usageCode)
            val release = buildConsumerReport(0)
            val sent = sendRawHidReport(hid, dev, REPORT_ID_CONSUMER, press)
            sleepQuietly(16L)
            sendRawHidReport(hid, dev, REPORT_ID_CONSUMER, release)
            if (sent) lastReportSentAtMs = System.currentTimeMillis()
            sent
        }
    }

    /**
     * Sends a Ctrl + Mouse Wheel burst for universal pinch-to-zoom on PC apps (browsers, Excel, PDFs).
     */
    @SuppressLint("MissingPermission")
    suspend fun sendZoomWheel(wheelSteps: Int): Boolean = sendMutex.withLock {
        withContext(hidTxDispatcher) {
            if (wheelSteps == 0) return@withContext true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || isBootProtocolMode) return@withContext false
            val hid = hidDeviceProfile ?: return@withContext false
            val dev = connectedHostDevice ?: return@withContext false

            val ctrlDown = byteArrayOf(MODIFIER_LEFT_CTRL, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            var sentOk = false
            try {
                if (!sendReportWithRetryBlocking(hid, dev, ctrlDown, maxAttempts = 4, backoffMs = 4L)) {
                    return@withContext false
                }
                sleepQuietly(10L)
                val mouseReport = buildEffectiveMousePayload(activeMouseButtons, 0, 0, wheelSteps.coerceIn(-8, 8))
                sentOk = sendRawHidReport(hid, dev, REPORT_ID_MOUSE, mouseReport)
                recordMouseButtonResult(sentOk)
                sleepQuietly(10L)
                sentOk
            } finally {
                sendZeroReportWithRetry(hid, dev, maxAttempts = 10)
            }
        }
    }

    /**
     * Real-time 6-Key Rollover (6KRO) live keyboard state for PC gaming (simultaneous WASD + Shift + Space).
     * Dispatches atomically on [hidTxExecutor] without blocking delays.
     */
    @SuppressLint("MissingPermission")
    fun updateLiveKeyboardState(keys: Set<Byte>, modifiers: Byte) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return
        val report = synchronized(liveHeldKeysLock) {
            if (liveHeldKeys == keys && liveHeldModifiers == modifiers) return
            liveHeldKeys.clear()
            liveHeldKeys.addAll(keys)
            liveHeldModifiers = modifiers
            buildSixKeyReport(liveHeldKeys, liveHeldModifiers)
        }
        hidTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            if (sendRawHidReport(hid, dev, activeReportId, report)) {
                lastReportSentAtMs = System.currentTimeMillis()
                val prev = _telemetry.value
                _telemetry.value = prev.copy(keyboardReportsSent = prev.keyboardReportsSent + 1L)
            }
        }
    }

    /**
     * Releases all live-held gaming keys cleanly with an emergency zero keyboard report.
     */
    @SuppressLint("MissingPermission")
    fun releaseAllLiveKeys() {
        val hadAny = synchronized(liveHeldKeysLock) {
            val nonEmpty = liveHeldKeys.isNotEmpty() || liveHeldModifiers != 0.toByte()
            liveHeldKeys.clear()
            liveHeldModifiers = 0
            nonEmpty
        }
        if (!hadAny || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return
        hidTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            sendRawHidReport(hid, dev, activeReportId, newZeroReport())
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseMove(dx: Int, dy: Int, wheel: Int = 0) {
        if (dx == 0 && dy == 0 && wheel == 0) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return

        // Coalesce high-frequency sensor/touch deltas atomically so L2CAP never queues stale lag
        pendingMouseDx.updateAndGet { cur -> (cur + dx).coerceIn(-1600, 1600) }
        pendingMouseDy.updateAndGet { cur -> (cur + dy).coerceIn(-1600, 1600) }
        pendingMouseWheel.updateAndGet { cur -> (cur + wheel).coerceIn(-256, 256) }
        scheduleMousePumpIfNeeded()
    }

    private fun scheduleMousePumpIfNeeded() {
        if (mousePumpScheduled.compareAndSet(false, true)) {
            mouseTxExecutor.execute { drainPendingMouseDeltas() }
        }
    }

    private fun buildEffectiveMousePayload(buttonsMask: Int, dx: Int, dy: Int, wheel: Int): ByteArray {
        return if (isBootProtocolMode) {
            buildBootMouseReport(buttonsMask, dx, dy)
        } else {
            buildMouseReport(buttonsMask, dx, dy, wheel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainPendingMouseDeltas() {
        var consecutiveFailures = 0
        try {
            while (isHidHostConnected()) {
                val now = System.currentTimeMillis()
                val sinceLastMouse = now - lastMouseReportAtMs
                if (sinceLastMouse in 0L until 8L) {
                    // Pace to ~125Hz BT ACL poll cadence so high-rate touch/gyro deltas coalesce cleanly
                    sleepQuietly(8L - sinceLastMouse)
                }

                val curX = pendingMouseDx.getAndSet(0)
                val curY = pendingMouseDy.getAndSet(0)
                val curW = pendingMouseWheel.getAndSet(0)
                if (curX == 0 && curY == 0 && curW == 0) break

                val hid = hidDeviceProfile ?: break
                val dev = connectedHostDevice ?: break
                val step = coalesceMouseDeltaStep(curX, curY, curW)

                val report = buildEffectiveMousePayload(activeMouseButtons, step.stepX, step.stepY, step.stepW)
                if (sendRawHidReport(hid, dev, REPORT_ID_MOUSE, report)) {
                    consecutiveFailures = 0
                    if (step.nextRemX != 0) pendingMouseDx.addAndGet(step.nextRemX)
                    if (step.nextRemY != 0) pendingMouseDy.addAndGet(step.nextRemY)
                    if (step.nextRemW != 0) pendingMouseWheel.addAndGet(step.nextRemW)
                    lastMouseReportAtMs = System.currentTimeMillis()
                    lastReportSentAtMs = lastMouseReportAtMs
                    val prev = _telemetry.value
                    if (prev.mouseReportsSent == 0L) {
                        AppDebugLogger.i("BT-MOUSE", "İlk Mouse hareket raporu iletildi (id=$REPORT_ID_MOUSE len=${report.size} boot=$isBootProtocolMode)")
                    }
                    _telemetry.value = prev.copy(
                        mouseReportsSent = prev.mouseReportsSent + 1L,
                        lastFailureReason = if (prev.lastFailureReason?.contains("Mouse") == true) null else prev.lastFailureReason
                    )
                } else {
                    consecutiveFailures++
                    val prev = _telemetry.value
                    if (consecutiveFailures == 1) {
                        AppDebugLogger.e(
                            "BT-MOUSE",
                            "Mouse raporu reddedildi (sendReport=false, id=$REPORT_ID_MOUSE len=${report.size} boot=$isBootProtocolMode host=${dev.address})"
                        )
                    }
                    _telemetry.value = prev.copy(
                        mouseReportsFailed = prev.mouseReportsFailed + 1L,
                        lastFailureReason = "Mouse raporu reddedildi — PC Bluetooth'tan cihazı kaldırıp yeniden eşleştirin"
                    )
                    if (consecutiveFailures >= 3) {
                        // Drop stale backlog rather than spinning endlessly on a rejected Report ID 2
                        break
                    }
                    pendingMouseDx.addAndGet(curX)
                    pendingMouseDy.addAndGet(curY)
                    pendingMouseWheel.addAndGet(curW)
                    sleepQuietly(12L)
                }
            }
        } finally {
            mousePumpScheduled.set(false)
            if (consecutiveFailures == 0 && isHidHostConnected() &&
                (pendingMouseDx.get() != 0 || pendingMouseDy.get() != 0 || pendingMouseWheel.get() != 0)
            ) {
                scheduleMousePumpIfNeeded()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun setMouseButton(buttonMask: Int, isDown: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return
        mouseTxExecutor.execute {
            activeMouseButtons = if (isDown) {
                activeMouseButtons or buttonMask
            } else {
                activeMouseButtons and buttonMask.inv()
            }
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            val report = buildEffectiveMousePayload(activeMouseButtons, 0, 0, 0)
            val ok = sendRawHidReport(hid, dev, REPORT_ID_MOUSE, report)
            recordMouseButtonResult(ok)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseClick(buttonMask: Int = MOUSE_BTN_LEFT) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            val downMask = activeMouseButtons or buttonMask
            val downOk = sendRawHidReport(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(downMask, 0, 0, 0))
            sleepQuietly(16L)
            activeMouseButtons = activeMouseButtons and buttonMask.inv()
            val upOk = sendRawHidReport(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(activeMouseButtons, 0, 0, 0))
            recordMouseButtonResult(downOk && upOk)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendMouseDoubleClick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            var allOk = true
            repeat(2) { idx ->
                val downMask = activeMouseButtons or MOUSE_BTN_LEFT
                allOk = sendRawHidReport(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(downMask, 0, 0, 0)) && allOk
                sleepQuietly(16L)
                activeMouseButtons = activeMouseButtons and MOUSE_BTN_LEFT.inv()
                allOk = sendRawHidReport(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(activeMouseButtons, 0, 0, 0)) && allOk
                if (idx == 0) sleepQuietly(38L)
            }
            recordMouseButtonResult(allOk)
        }
    }

    private fun recordMouseButtonResult(ok: Boolean) {
        val prev = _telemetry.value
        if (ok) {
            lastMouseReportAtMs = System.currentTimeMillis()
            lastReportSentAtMs = lastMouseReportAtMs
            _telemetry.value = prev.copy(
                mouseReportsSent = prev.mouseReportsSent + 1L,
                lastFailureReason = if (prev.lastFailureReason?.contains("Mouse") == true) null else prev.lastFailureReason
            )
        } else {
            AppDebugLogger.e("BT-MOUSE", "Mouse tıklama raporu reddedildi (id=$REPORT_ID_MOUSE boot=$isBootProtocolMode)")
            _telemetry.value = prev.copy(
                mouseReportsFailed = prev.mouseReportsFailed + 1L,
                lastFailureReason = "Mouse tıklama reddedildi — PC'den cihazı kaldırıp yeniden eşleştirin"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun releaseAllMouseButtons() {
        activeMouseButtons = 0
        pendingMouseDx.set(0)
        pendingMouseDy.set(0)
        pendingMouseWheel.set(0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isHidHostConnected()) return
        mouseTxExecutor.execute {
            val hid = hidDeviceProfile ?: return@execute
            val dev = connectedHostDevice ?: return@execute
            sendRawHidReport(hid, dev, REPORT_ID_MOUSE, buildEffectiveMousePayload(0, 0, 0, 0))
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureHostReadyForReports(hid: BluetoothHidDevice, dev: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val now = System.currentTimeMillis()
        // Wait briefly right after initial connection enumeration so Windows binds kbdhid/mouhid
        val sinceConnect = now - connectedAtMs
        if (connectedAtMs > 0L && sinceConnect in 0L..299L) {
            sleepQuietly(300L - sinceConnect)
        }
        // Wake Bluetooth Classic ACL out of Sniff Mode BEFORE sending Key-Down so Key-Up is never
        // stranded across a 500ms+ sniff anchor causing OS auto-repeat!
        if ((System.currentTimeMillis() - lastReportSentAtMs) > 300L) {
            sendZeroReportWithRetry(hid, dev, maxAttempts = 4)
            sleepQuietly(16L)
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
        var needsEmergencyZeroFlush = true

        val startMs = System.currentTimeMillis()
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
                        AppDebugLogger.e("BT-HID", "Karakter gönderimi kesildi (len=${text.length}, elapsed=${System.currentTimeMillis() - startMs}ms)")
                        return false
                    }
                    prevKeyCode = keyInfo.keyCode
                }
            }

            when (endingKey) {
                KeyboardEndingKey.ENTER -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_ENTER, MODIFIER_NONE, baseTiming.holdMs, gapMs = 8L)) return false
                }
                KeyboardEndingKey.TAB -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_TAB, MODIFIER_NONE, baseTiming.holdMs, gapMs = 8L)) return false
                }
                KeyboardEndingKey.TAB_THEN_ENTER -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_TAB, MODIFIER_NONE, baseTiming.holdMs, baseTiming.gapMs)) return false
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_ENTER, MODIFIER_NONE, baseTiming.holdMs, gapMs = 8L)) return false
                }
                KeyboardEndingKey.SPACE -> {
                    if (!sendKeyStrokeBlocking(hid, dev, KEY_SPACE, MODIFIER_NONE, baseTiming.holdMs, gapMs = 8L)) return false
                }
                KeyboardEndingKey.NONE -> {
                    // Last stroke already released Key-Up cleanly
                }
            }

            needsEmergencyZeroFlush = false
            val elapsedMs = System.currentTimeMillis() - startMs
            AppDebugLogger.d(
                "BT-HID",
                "BT klavye TX OK: ${text.length} krk (${endingKey.name}) ${elapsedMs}ms · hold=${baseTiming.holdMs}ms · boot=$isBootProtocolMode"
            )
            return true
        } catch (e: Exception) {
            AppDebugLogger.e("BT-HID", "sendOverBluetoothHidBlocking istisnası", e)
            return false
        } finally {
            // Emergency release flush only if stream aborted mid-stroke
            if (needsEmergencyZeroFlush) {
                val released = runCatching { sendZeroReportWithRetry(hid, dev, maxAttempts = 12) }.getOrDefault(false)
                if (!released) {
                    sleepQuietly(16L)
                    sendRawHidReport(hid, dev, activeReportId, newZeroReport())
                }
            }
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
            if (sendRawHidReport(hid, device, reportId, report)) {
                lastReportSentAtMs = System.currentTimeMillis()
                val prev = _telemetry.value
                _telemetry.value = prev.copy(
                    keyboardReportsSent = prev.keyboardReportsSent + 1L,
                    lastFailureReason = if (prev.lastFailureReason?.contains("Klavye") == true) null else prev.lastFailureReason
                )
                return true
            }
            sleepQuietly(backoffMs * (attempt + 1))
        }
        AppDebugLogger.e("BT-HID", "Klavye HID raporu $maxAttempts denemede iletilemedi (id=$reportId boot=$isBootProtocolMode)")
        val prev = _telemetry.value
        _telemetry.value = prev.copy(
            keyboardReportsFailed = prev.keyboardReportsFailed + 1L,
            lastFailureReason = "Klavye HID raporu iletilemedi (L2CAP meşgul/kopuk)"
        )
        return false
    }

    private fun sendZeroReportWithRetry(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        maxAttempts: Int = 12
    ): Boolean {
        return sendReportWithRetryBlocking(
            hid = hid,
            device = device,
            report = newZeroReport(),
            maxAttempts = maxAttempts,
            backoffMs = 5L
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

        if (!sendReportWithRetryBlocking(hid, device, reportDown, maxAttempts = 5, backoffMs = 4L)) {
            sendZeroReportWithRetry(hid, device, maxAttempts = 6)
            return false
        }
        sleepQuietly(holdMs)
        if (!sendZeroReportWithRetry(hid, device, maxAttempts = 12)) {
            sleepQuietly(16L)
            sendRawHidReport(hid, device, activeReportId, newZeroReport())
            return false
        }
        if (gapMs > 0L) {
            sleepQuietly(gapMs)
        }
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
        private const val KEY_HID_PROFILE_MODE = "hid_profile_mode"

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
        const val KEY_B: Byte = 0x05
        const val KEY_C: Byte = 0x06
        const val KEY_D: Byte = 0x07
        const val KEY_E: Byte = 0x08
        const val KEY_F: Byte = 0x09
        const val KEY_G: Byte = 0x0A
        const val KEY_H: Byte = 0x0B
        const val KEY_I: Byte = 0x0C
        const val KEY_J: Byte = 0x0D
        const val KEY_K: Byte = 0x0E
        const val KEY_L: Byte = 0x0F
        const val KEY_M: Byte = 0x10
        const val KEY_N: Byte = 0x11
        const val KEY_O: Byte = 0x12
        const val KEY_P: Byte = 0x13
        const val KEY_Q: Byte = 0x14
        const val KEY_R: Byte = 0x15
        const val KEY_S: Byte = 0x16
        const val KEY_T: Byte = 0x17
        const val KEY_U: Byte = 0x18
        const val KEY_V: Byte = 0x19
        const val KEY_W: Byte = 0x1A
        const val KEY_X: Byte = 0x1B
        const val KEY_Y: Byte = 0x1C
        const val KEY_Z: Byte = 0x1D

        const val KEY_1: Byte = 0x1E
        const val KEY_2: Byte = 0x1F
        const val KEY_3: Byte = 0x20
        const val KEY_4: Byte = 0x21

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
        const val KEY_NUM_LOCK: Byte = 0x53
        const val KEY_KP_DIVIDE: Byte = 0x54
        const val KEY_KP_MULTIPLY: Byte = 0x55
        const val KEY_KP_MINUS: Byte = 0x56
        const val KEY_KP_PLUS: Byte = 0x57
        const val KEY_KP_ENTER: Byte = 0x58

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

        data class MouseDeltaStep(
            val stepX: Int,
            val stepY: Int,
            val stepW: Int,
            val nextRemX: Int,
            val nextRemY: Int,
            val nextRemW: Int
        )

        fun coalesceMouseDeltaStep(remX: Int, remY: Int, remW: Int = 0): MouseDeltaStep {
            val stepX = remX.coerceIn(-127, 127)
            val stepY = remY.coerceIn(-127, 127)
            val stepW = remW.coerceIn(-127, 127)
            return MouseDeltaStep(
                stepX = stepX,
                stepY = stepY,
                stepW = stepW,
                nextRemX = remX - stepX,
                nextRemY = remY - stepY,
                nextRemW = remW - stepW
            )
        }

        fun buildMouseReport(buttonsMask: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray {
            return byteArrayOf(
                (buttonsMask and 0x07).toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
        }

        fun buildBootMouseReport(buttonsMask: Int, dx: Int, dy: Int): ByteArray {
            return byteArrayOf(
                (buttonsMask and 0x07).toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte()
            )
        }

        fun buildConsumerReport(usageCode: Int): ByteArray {
            return byteArrayOf(
                (usageCode and 0xFF).toByte(),
                ((usageCode shr 8) and 0xFF).toByte()
            )
        }

        /**
         * Builds a standard 8-byte 6-Key Rollover (6KRO) USB HID keyboard report for live multi-key gaming.
         */
        fun buildSixKeyReport(pressedKeys: Collection<Byte>, modifiers: Byte): ByteArray {
            val report = ByteArray(8)
            report[0] = modifiers
            report[1] = 0x00
            var slot = 2
            for (code in pressedKeys) {
                if (code == 0x00.toByte()) continue
                if (slot >= 8) break
                report[slot++] = code
            }
            return report
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
         * Bluetooth Classic HID slot-aligned timing (12–50ms hold, 10–45ms gap)
         * so Key-Down and Key-Up span distinct 11.25ms HID slots without coalescing or stalling
         * long enough to trigger host OS auto-repeat (>=250ms). Consecutive identical keycodes
         * (e.g. '0'->'0' in "00326") receive +10ms extra separation.
         */
        fun computeKeyTiming(requestedDelayMs: Long, isSameKeyCodeAsPrev: Boolean): KeyTiming {
            val holdMs = requestedDelayMs.coerceIn(12L, 50L)
            val baseGap = ((requestedDelayMs * 8L) / 10L).coerceIn(10L, 45L)
            val gapMs = if (isSameKeyCodeAsPrev) baseGap + 10L else baseGap
            return KeyTiming(holdMs = holdMs, gapMs = gapMs)
        }

        fun extractReportIdsFromDescriptor(descriptor: ByteArray): List<Int> {
            val ids = mutableListOf<Int>()
            var i = 0
            while (i < descriptor.size) {
                val b = descriptor[i].toInt() and 0xFF
                if (b == 0xFE) {
                    if (i + 1 >= descriptor.size) break
                    val dataLen = descriptor[i + 1].toInt() and 0xFF
                    i += 3 + dataLen
                    continue
                }
                val sizeCode = b and 0x03
                val payloadLen = if (sizeCode == 3) 4 else sizeCode
                if (b == 0x85 && i + 1 < descriptor.size) {
                    ids.add(descriptor[i + 1].toInt() and 0xFF)
                }
                i += 1 + payloadLen
            }
            return ids
        }

        fun checkDescriptorCollectionBalance(descriptor: ByteArray): Boolean {
            var depth = 0
            var i = 0
            while (i < descriptor.size) {
                val b = descriptor[i].toInt() and 0xFF
                if (b == 0xFE) {
                    if (i + 1 >= descriptor.size) return false
                    val dataLen = descriptor[i + 1].toInt() and 0xFF
                    i += 3 + dataLen
                    continue
                }
                val sizeCode = b and 0x03
                val payloadLen = if (sizeCode == 3) 4 else sizeCode
                val itemTag = b and 0xFC
                if (itemTag == 0xA0) depth++
                else if (itemTag == 0xC0) {
                    depth--
                    if (depth < 0) return false
                }
                i += 1 + payloadLen
            }
            return depth == 0 && i == descriptor.size
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
                    '#' -> HidKey(0x20.toByte(), MODIFIER_ALT_GR)
                    '$' -> HidKey(0x21.toByte(), MODIFIER_ALT_GR)
                    '{' -> HidKey(0x24.toByte(), MODIFIER_ALT_GR)
                    '[' -> HidKey(0x25.toByte(), MODIFIER_ALT_GR)
                    ']' -> HidKey(0x26.toByte(), MODIFIER_ALT_GR)
                    '}' -> HidKey(0x27.toByte(), MODIFIER_ALT_GR)
                    '\\' -> HidKey(0x2D.toByte(), MODIFIER_ALT_GR)
                    '|' -> HidKey(0x2E.toByte(), MODIFIER_ALT_GR)
                    '<' -> HidKey(0x64.toByte(), MODIFIER_NONE)
                    '>' -> HidKey(0x64.toByte(), MODIFIER_SHIFT)
                    '~' -> HidKey(0x30.toByte(), MODIFIER_ALT_GR)
                    '`' -> HidKey(0x31.toByte(), MODIFIER_ALT_GR)
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
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x02.toByte(), //     Report Count (2)
            0x81.toByte(), 0x06.toByte(), //     Input (Data, Variable, Relative)
            0x09.toByte(), 0x38.toByte(), //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), //     Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //     Report Size (8)
            0x95.toByte(), 0x01.toByte(), //     Report Count (1)
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

        // Pure 63-byte Standard Boot Keyboard Descriptor (NO Report ID, SUBCLASS1_KEYBOARD).
        // Universally accepted by Linux BlueZ, macOS, BIOS, and strict Windows Bluetooth stacks.
        val HID_SIMPLE_KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
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
            0xC0.toByte()                 // End Collection
        )
    }
}
