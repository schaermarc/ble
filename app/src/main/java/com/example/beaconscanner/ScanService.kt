package com.example.beaconscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the scanner and periodic uploader alive while the app is in the background
 * by running as a foreground service with an ongoing notification.
 */
class ScanService : Service() {

    private val app: App get() = application as App

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                app.scheduler.stop()
                app.locationTracker.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        app.locationTracker.start()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val periodic = prefs.getBoolean(PREF_SCAN_PERIODIC, DEFAULT_SCAN_PERIODIC)
        if (periodic) {
            app.scheduler.startPeriodic()
        } else {
            app.scheduler.runOnce(onComplete = {
                // Single-shot is done — drop the foreground notification and
                // let the system tear us down. Location tracker is stopped in
                // onDestroy.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            })
        }

        return START_STICKY
    }

    override fun onDestroy() {
        app.scheduler.stop()
        app.locationTracker.stop()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Beacon-Scan",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "BLE-Scan läuft im Hintergrund" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = Intent(this, ScanService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stop,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_b)
            .setContentTitle("Beacon Scanner aktiv")
            .setContentText("Scannt BLE und sendet Eddystone-UID Daten")
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.example.beaconscanner.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "beacon_scan"
    }
}
