package net.jacoblo.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat // Added for better compatibility

private const val CHANNEL_ID = "Calendar"
private const val NOTIFICATION_ID = 1

class NotificationService : Service() {

    private var bubble: Bubble? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bubble = Bubble(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceState("Bubble Active")
        bubble?.show()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        bubble?.remove()
    }

    private fun createNotificationChannel() {
        // Notification channels are only required for API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Calendar",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceState(status: String?) {
        val notification: Notification = getNotification(status)
        // Check for Android 14 (API 34) specific foreground types
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
        // Switched to NotificationCompat.Builder to resolve 'setOnlyAlertOnce' and ensure compatibility
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)

        return builder
            .setContentTitle("AutoClicker Bubble")
            .setContentText(content)
            // Fixed: Use modern Material icon
            .setSmallIcon(R.drawable.ic_stat_bubble)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}