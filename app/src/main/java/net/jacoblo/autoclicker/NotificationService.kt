package net.jacoblo.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat // Added for better compatibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "Calendar"
private const val NOTIFICATION_ID = 1

/**
 * Stops whatever is playing, from the notification.
 *
 * The bubble's own stop button is a touch on the screen the script is driving:
 * replay writes to the touchscreen's evdev node, so a real finger pressed during
 * playback shares a multitouch slot with the injected stream and is easily lost.
 * A notification action never goes near the digitizer, so it works whatever the
 * script is doing.
 */
const val ACTION_STOP_PLAYBACK = "net.jacoblo.autoclicker.STOP_PLAYBACK"

class NotificationService : Service() {

    private var bubble: Bubble? = null
    private var triggers: TriggerRunner? = null
    private var control: ControlServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bubble = Bubble(this)
        // Hosted here so triggers keep watching for as long as the bubble does.
        triggers = TriggerRunner(this).apply { start() }
        // Likewise the control server: playback needs the bubble's backend, so
        // there is nothing to drive when this service is not running.
        control = ControlServer(AppSettings.controlPort, scope).apply { start() }

        scope.launch {
            GestureExecutor.playing.collectLatest { playing ->
                bubble?.setPlaying(playing)
                startForegroundServiceState(if (playing) "Playing a recording" else "Bubble Active")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_PLAYBACK) {
            GestureExecutor.stop()
            return START_STICKY
        }
        startForegroundServiceState(if (GestureExecutor.isPlaying) "Playing a recording" else "Bubble Active")
        bubble?.show()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        triggers?.stop()
        control?.stop()
        scope.cancel()
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

        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, NotificationService::class.java).setAction(ACTION_STOP_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return builder
            .setContentTitle("AutoClicker Bubble")
            .setContentText(content)
            // Fixed: Use modern Material icon
            .setSmallIcon(R.drawable.ic_stat_bubble)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .build()
    }
}
