package net.jacoblo.calendarannouncement

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.CalendarContract
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

private const val CHANNEL_ID = "Calendar"
private const val NOTIFICATION_ID = 1

class NotificationService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Initial notification state
        startForegroundServiceState("Loading events...")
        
        // Start monitoring loop
        startEventMonitoring()
        
        return START_STICKY
    }


    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun startEventMonitoring() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val eventsText = readTodayEvents()
                withContext(Dispatchers.Main) {
                    startForegroundServiceState(eventsText)
                }
                // Refresh every 10 minutes
                delay(10 * 60 * 1000L)
            }
        }
    }

    private fun readTodayEvents(): String {
        val events = mutableListOf<String>()
        try {
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN
            )

            // Calculate start and end of today
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startMillis = calendar.timeInMillis

            calendar.add(Calendar.DAY_OF_YEAR, 1)
            val endMillis = calendar.timeInMillis

            // Query Instances table for range
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMillis)
            ContentUris.appendId(builder, endMillis)

            val cursor = contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                CalendarContract.Instances.BEGIN + " ASC"
            )

            cursor?.use {
                val titleIdx = it.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = it.getColumnIndex(CalendarContract.Instances.BEGIN)
                val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

                while (it.moveToNext()) {
                    val title = it.getString(titleIdx) ?: "No Title"
                    val begin = it.getLong(beginIdx)
                    events.add("${dateFormat.format(Date(begin))} $title")
                }
            }
        } catch (e: SecurityException) {
            return "Permission denied"
        } catch (e: Exception) {
            return "Error reading calendar: ${e.localizedMessage}"
        }

        if (events.isEmpty()) return "No events today"
        return events.joinToString("\n")
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
            .setContentTitle("Today's Events")
            .setContentText(content) // Shows one line
            .setStyle(Notification.BigTextStyle().bigText(content)) // Shows multiline
            .setSmallIcon(R.drawable.ic_menu_recent_history)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }
}
