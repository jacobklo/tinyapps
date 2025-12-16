package net.jacoblo.notesoutloud

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

private const val CHANNEL_ID = "Calendar"
private const val NOTIFICATION_ID = 1

class NotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Initial notification state or update
        startForegroundServiceState("Hello world")
        return START_STICKY
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Calendar",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

    }

    private fun startForegroundServiceState(status: String?) {
        val notification: Notification = getNotification(status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getNotification(content: String?): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)

        return builder
            .setContentTitle("UI Monitor Service")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_menu_recent_history)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}
