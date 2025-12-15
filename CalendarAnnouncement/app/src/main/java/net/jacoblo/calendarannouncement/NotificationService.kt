package net.jacoblo.calendarannouncement

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.provider.CalendarContract
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.ranges.contains

private const val CHANNEL_ID = "Calendar"
private const val NOTIFICATION_ID = 1
private const val TAG = "NotificationService"

class NotificationService : Service(), TextToSpeech.OnInitListener {

    // Data class to hold event details for both logic (timestamp) and display
    private data class CalendarEvent(val title: String, val begin: Long)

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating NotificationService")
        createNotificationChannel()

        // Initialize TTS engine using the Service context
        Log.d(TAG, "Initializing TTS")
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        Log.d(TAG, "TTS onInit status: $status")
        if (status == TextToSpeech.SUCCESS) {
            // Set audio attributes to use the Notification volume channel
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            tts.setAudioAttributes(audioAttributes)

            // Check if language is supported and log the result
            val result = tts.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported or missing data")
                isTtsReady = false
            } else {
                Log.d(TAG, "TTS Initialized and ready")
                isTtsReady = true
            }
        } else {
            Log.e(TAG, "TTS Initialization failed")
            isTtsReady = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // Initial notification state
        startForegroundServiceState("Loading events...")

        // Start monitoring loop
        startEventMonitoring()

        return START_STICKY
    }


    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Release TTS resources to prevent memory leaks
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
        serviceJob.cancel()
    }

    private fun startEventMonitoring() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                Log.d(TAG, "Checking events cycle...")

                // Retrieve user preference for announcement time, defaulting to 10 minutes
                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val announceMinutes = prefs.getInt("announce_minutes", 10)

                // Get both the formatted text for notification and the raw event objects for logic
                val (notificationText, events) = readTodayEvents()

                val now = System.currentTimeMillis()

                withContext(Dispatchers.Main) {
                    startForegroundServiceState(notificationText)

                    for (event in events) {
                        // Access properties directly from the data object
                        val title = event.title
                        val begin = event.begin

                        // Check if the event starts in the user-defined window (e.g. 9 to 10 minutes)
                        val timeDiff = begin - now
                        val minWindow = (announceMinutes - 1) * 60 * 1000
                        val maxWindow = announceMinutes * 60 * 1000
                        
                        if (timeDiff in minWindow..<maxWindow) {
                            if (isTtsReady) {
                                Log.d(TAG, "Announcing event in $announceMinutes mins: $title")
                                val speechText = "Event: $title"
                                tts.speak(speechText, TextToSpeech.QUEUE_ADD, null, title)
                            } else {
                                Log.w(TAG, "TTS not ready, skipping event: $title")
                            }
                        }
                    }
                }
                // Check every 60 seconds to catch the window
                delay(60 * 1000L)
            }
        }
    }

    // Refactored to return Pair<String, List<CalendarEvent>> to provide both display text and raw data
    private fun readTodayEvents(): Pair<String, List<CalendarEvent>> {
        val events = mutableListOf<CalendarEvent>()
        val displayStrings = mutableListOf<String>()

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

                Log.d(TAG, "Found ${it.count} events")

                while (it.moveToNext()) {
                    val title = it.getString(titleIdx) ?: "No Title"
                    val begin = it.getLong(beginIdx)

                    // Add to object list for logic
                    events.add(CalendarEvent(title, begin))
                    // Add to string list for notification display
                    displayStrings.add("${dateFormat.format(Date(begin))} $title")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading calendar", e)
            return Pair("Permission denied", emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Error reading calendar", e)
            return Pair("Error reading calendar: ${e.localizedMessage}", emptyList())
        }

        if (events.isEmpty()) return Pair("No events today", emptyList())

        return Pair(displayStrings.joinToString("\n"), events)
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
