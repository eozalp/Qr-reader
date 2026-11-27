package com.example.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.debug.AppDebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the Android BluetoothHidDevice profile proxy registered and L2CAP link prioritized
 * while connected or switching to external apps with ALVETAK QR Klavye.
 *
 * Strictly guarantees [startForeground] is invoked immediately in [onCreate] and [onStartCommand]
 * before any stop transition so Android 12–17 never throws
 * `ForegroundServiceDidNotStartInTimeException`.
 */
class BluetoothHidForegroundService : Service() {

    private var foregroundPromotedInInstance = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceAlive.set(true)
        // Promote immediately on create so even a racing stop never misses Android's FGS deadline
        ensureForeground(lastRequestedHostName, lastRequestedConnected)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasConnectedExtra = intent?.hasExtra(EXTRA_IS_CONNECTED) == true
        val isConnected = if (hasConnectedExtra) {
            intent?.getBooleanExtra(EXTRA_IS_CONNECTED, true) ?: true
        } else {
            lastRequestedConnected
        }
        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME) ?: lastRequestedHostName
        lastRequestedHostName = hostName
        lastRequestedConnected = isConnected

        // Always satisfy startForeground() for this start command before evaluating actions
        val started = ensureForeground(hostName, isConnected)

        when (intent?.action) {
            ACTION_MEDIA_PLAY_PAUSE -> {
                serviceScope.launch {
                    runCatching {
                        BluetoothHidManager.getInstance(applicationContext)
                            .sendConsumerControl(BluetoothHidManager.CONSUMER_PLAY_PAUSE)
                    }
                }
                return START_STICKY
            }
            ACTION_MEDIA_MUTE -> {
                serviceScope.launch {
                    runCatching {
                        BluetoothHidManager.getInstance(applicationContext)
                            .sendConsumerControl(BluetoothHidManager.CONSUMER_MUTE)
                    }
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                if (intent.getBooleanExtra(EXTRA_USER_STOP, false)) {
                    runCatching {
                        BluetoothHidManager.getInstance(applicationContext)
                            .onForegroundServiceStoppedByUser()
                    }
                }
                isForegroundActive.set(false)
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (stopRequestedBeforeStart.getAndSet(false) || !started) {
            isForegroundActive.set(false)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        isForegroundActive.set(false)
        isServiceAlive.set(false)
        foregroundPromotedInInstance = false
        super.onDestroy()
    }

    private fun ensureForeground(hostName: String, isConnected: Boolean): Boolean {
        val notification = buildNotification(hostName, isConnected)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundPromotedInInstance = true
            isForegroundActive.set(true)
            true
        }.onFailure { err ->
            AppDebugLogger.e(
                "BT-SVC",
                "HID Foreground Service startForeground hatası (connected=$isConnected, host=$hostName)",
                err
            )
        }.getOrDefault(false)
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }.onFailure { err ->
            AppDebugLogger.w("BT-SVC", "stopForeground hatası", err)
        }
    }

    private fun buildNotification(hostName: String, isConnected: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channelCreated.compareAndSet(false, true)) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth PC Kumanda & Klavye",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PC/Linux HID profilini arka planda aktif tutar ve kilit ekranından hızlı erişim sağlar"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val launchRemoteIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_REMOTE, true)
        }
        val openRemotePi = PendingIntent.getActivity(this, 10, launchRemoteIntent, pendingFlags)

        if (!isConnected) {
            val stopIntent = Intent(this, BluetoothHidForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_USER_STOP, true)
            }
            val stopPi = PendingIntent.getService(this, 13, stopIntent, pendingFlags)

            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_kb_classic)
                .setContentTitle("Bluetooth HID Hazır (Eşleştirme Açık)")
                .setContentText("PC / Linux eşleştirirken HID profili arka planda aktif tutulur")
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openRemotePi)
                .addAction(0, "🎮 Kumanda", openRemotePi)
                .addAction(0, "✕ Kapat", stopPi)
                .build()
        }

        val playPauseIntent = Intent(this, BluetoothHidForegroundService::class.java).apply {
            action = ACTION_MEDIA_PLAY_PAUSE
        }
        val playPausePi = PendingIntent.getService(this, 11, playPauseIntent, pendingFlags)

        val muteIntent = Intent(this, BluetoothHidForegroundService::class.java).apply {
            action = ACTION_MEDIA_MUTE
        }
        val mutePi = PendingIntent.getService(this, 12, muteIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kb_classic)
            .setContentTitle("PC Kumanda Bağlı: $hostName")
            .setContentText("Kilit ekranından dokunup Kumanda / Mouse / Oyun açın")
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openRemotePi)
            .addAction(0, "⏯ Oynat", playPausePi)
            .addAction(0, "🔇 Sessiz", mutePi)
            .addAction(0, "🎮 Kumanda", openRemotePi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "alvetak_bt_hid_channel"
        private const val NOTIFICATION_ID = 4108
        private const val ACTION_STOP = "com.example.bluetooth.STOP_HID_SERVICE"
        private const val ACTION_MEDIA_PLAY_PAUSE = "com.example.bluetooth.MEDIA_PLAY_PAUSE"
        private const val ACTION_MEDIA_MUTE = "com.example.bluetooth.MEDIA_MUTE"
        private const val EXTRA_HOST_NAME = "extra_host_name"
        private const val EXTRA_IS_CONNECTED = "extra_is_connected"
        private const val EXTRA_USER_STOP = "extra_user_stop"

        private val isServiceAlive = AtomicBoolean(false)
        private val isForegroundActive = AtomicBoolean(false)
        private val stopRequestedBeforeStart = AtomicBoolean(false)
        private val channelCreated = AtomicBoolean(false)
        @Volatile
        private var lastRequestedHostName: String = "Bilgisayar"
        @Volatile
        private var lastRequestedConnected: Boolean = false

        fun isRunning(): Boolean = isForegroundActive.get() && isServiceAlive.get()

        /**
         * Promotes the service in Standby/Pairing-Ready mode so Android's `HidDeviceService`
         * keeps our UID at `IMPORTANCE_FOREGROUND_SERVICE` and NEVER unregisters the `0x1124`
         * Bluetooth HID SDP record when the user switches to Bluetooth Settings or pairs from Linux/Windows.
         */
        fun startStandby(context: Context) {
            startOrUpdate(context = context, hostName = "", isConnected = false)
        }

        fun startOrUpdate(context: Context, hostName: String, isConnected: Boolean = true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBt = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasBt) return
            }
            stopRequestedBeforeStart.set(false)
            lastRequestedHostName = hostName.ifBlank { "Bilgisayar" }
            lastRequestedConnected = isConnected
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, BluetoothHidForegroundService::class.java).apply {
                putExtra(EXTRA_HOST_NAME, lastRequestedHostName)
                putExtra(EXTRA_IS_CONNECTED, isConnected)
            }
            runCatching {
                if (isForegroundActive.get() && isServiceAlive.get()) {
                    // Already foregrounded — avoid redundant startForegroundService() churn
                    appCtx.startService(intent)
                } else {
                    ContextCompat.startForegroundService(appCtx, intent)
                }
            }.onFailure { err ->
                AppDebugLogger.e("BT-SVC", "startForegroundService başlatılamadı (connected=$isConnected, host=$hostName)", err)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            if (!isServiceAlive.get() && !isForegroundActive.get()) {
                // Mark pending stop in case startForegroundService() was just dispatched asynchronously
                stopRequestedBeforeStart.set(true)
                return
            }
            if (!isForegroundActive.get()) {
                // Service is starting up but hasn't finished startForeground() yet; let onStartCommand stop safely after promoting
                stopRequestedBeforeStart.set(true)
                return
            }
            runCatching {
                val stopIntent = Intent(appCtx, BluetoothHidForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                appCtx.startService(stopIntent)
            }.onFailure { err ->
                AppDebugLogger.w("BT-SVC", "stop(ACTION_STOP) hatası, stopService deneniyor", err)
                runCatching {
                    appCtx.stopService(Intent(appCtx, BluetoothHidForegroundService::class.java))
                }
            }
        }
    }
}
