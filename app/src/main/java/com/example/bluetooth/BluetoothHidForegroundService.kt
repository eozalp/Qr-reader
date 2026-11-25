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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceAlive.set(true)
        // Promote immediately on create so even a racing stop never misses Android's FGS deadline
        ensureForeground("Bilgisayar")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME) ?: lastRequestedHostName
        lastRequestedHostName = hostName

        // Always satisfy startForeground() for this start command before evaluating actions
        val started = ensureForeground(hostName)

        when (intent?.action) {
            ACTION_MEDIA_PLAY_PAUSE -> {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching {
                        BluetoothHidManager.getInstance(applicationContext)
                            .sendConsumerControl(BluetoothHidManager.CONSUMER_PLAY_PAUSE)
                    }
                }
                return START_STICKY
            }
            ACTION_MEDIA_MUTE -> {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching {
                        BluetoothHidManager.getInstance(applicationContext)
                            .sendConsumerControl(BluetoothHidManager.CONSUMER_MUTE)
                    }
                }
                return START_STICKY
            }
            ACTION_STOP -> {
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
        isForegroundActive.set(false)
        isServiceAlive.set(false)
        foregroundPromotedInInstance = false
        super.onDestroy()
    }

    private fun ensureForeground(hostName: String): Boolean {
        val notification = buildNotification(hostName)
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
            AppDebugLogger.e("BT-SVC", "HID Foreground Service startForeground hatası ($hostName)", err)
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

    private fun buildNotification(hostName: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth PC Kumanda & Klavye",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kilit ekranından hızlı PC medya & kumanda erişimi sağlar"
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

        private val isServiceAlive = AtomicBoolean(false)
        private val isForegroundActive = AtomicBoolean(false)
        private val stopRequestedBeforeStart = AtomicBoolean(false)
        @Volatile
        private var lastRequestedHostName: String = "Bilgisayar"

        fun startOrUpdate(context: Context, hostName: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBt = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasBt) return
            }
            stopRequestedBeforeStart.set(false)
            lastRequestedHostName = hostName
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, BluetoothHidForegroundService::class.java).apply {
                putExtra(EXTRA_HOST_NAME, hostName)
            }
            runCatching {
                if (isForegroundActive.get() && isServiceAlive.get()) {
                    // Already foregrounded — avoid redundant startForegroundService() churn
                    appCtx.startService(intent)
                } else {
                    ContextCompat.startForegroundService(appCtx, intent)
                }
            }.onFailure { err ->
                AppDebugLogger.e("BT-SVC", "startForegroundService başlatılamadı ($hostName)", err)
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
