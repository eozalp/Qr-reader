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

/**
 * Keeps the Android BluetoothHidDevice profile proxy registered and L2CAP link prioritized
 * while connected or switching to external apps with ALVETAK QR Klavye.
 */
class BluetoothHidForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME) ?: "Bilgisayar"
        val notification = buildNotification(hostName)

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)

        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun buildNotification(hostName: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Klavye Bağlantısı",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ALVETAK QR kablosuz HID klavye bağlantısını açık tutar"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kb_classic)
            .setContentTitle("ALVETAK QR Bluetooth Klavye")
            .setContentText("Aktif bağlantı: $hostName")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "alvetak_bt_hid_channel"
        private const val NOTIFICATION_ID = 4108
        private const val ACTION_STOP = "com.example.bluetooth.STOP_HID_SERVICE"
        private const val EXTRA_HOST_NAME = "extra_host_name"

        fun startOrUpdate(context: Context, hostName: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBt = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasBt) return
            }
            val intent = Intent(context, BluetoothHidForegroundService::class.java).apply {
                putExtra(EXTRA_HOST_NAME, hostName)
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context, BluetoothHidForegroundService::class.java)
                )
            }
        }
    }
}
