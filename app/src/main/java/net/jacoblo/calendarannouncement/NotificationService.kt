package net.jacoblo.calendarannouncement

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "calendar_events"
        const val NOTIFICATION_ID = 1
        const val ACTION_SYNC = "net.jacoblo.calendarannouncement.ACTION_SYNC"
        const val ACTION_ANNOUNCE = "net.jacoblo.calendarannouncement.ACTION_ANNOUNCE"
        const val EXTRA_EVENT_TITLE = "event_title"
        private const val TAG = "NotificationService"

        fun start(context: Context) {
            val intent = Intent(context, NotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationService::class.java))
        }

        fun announce(context: Context, eventTitle: String) {
            val intent = Intent(context, NotificationService::class.java).apply {
                action = ACTION_ANNOUNCE
                putExtra(EXTRA_EVENT_TITLE, eventTitle)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingAnnouncement: String? = null
    private lateinit var prefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
        initTts()
        // Trigger an immediate sync on start
        Thread { syncAndUpdate() }.start()
    }

    private fun initTts() {
        val engine = prefs.ttsEngine.ifEmpty { null }
        tts = TextToSpeech(this, this, engine)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            applyTtsSettings()
            pendingAnnouncement?.let {
                speakText(it)
                pendingAnnouncement = null
            }
        } else {
            Log.e(TAG, "TTS init failed")
        }
    }

    private fun applyTtsSettings() {
        val t = tts ?: return
        val lang = prefs.ttsLanguage
        if (lang.isNotEmpty()) {
            val result = t.setLanguage(Locale.forLanguageTag(lang))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language not supported: $lang")
            }
        }

        val voiceName = prefs.ttsVoice
        if (voiceName.isNotEmpty()) {
            t.voices?.find { it.name == voiceName }?.let { t.voice = it }
        }
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(getAudioUsageForStream(prefs.ttsAudioStream))
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        t.setPitch(prefs.ttsPitch)
        t.setSpeechRate(prefs.ttsSpeed)
    }

    private fun getAudioUsageForStream(stream: Int): Int {
        return when (stream) {
            android.media.AudioManager.STREAM_ALARM -> AudioAttributes.USAGE_ALARM
            android.media.AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION_EVENT
            android.media.AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            android.media.AudioManager.STREAM_SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            android.media.AudioManager.STREAM_VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            else -> AudioAttributes.USAGE_MEDIA
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANNOUNCE -> {
                val title = intent.getStringExtra(EXTRA_EVENT_TITLE) ?: return START_STICKY
                val text = "Meeting! $title"
                
                // Re-apply TTS settings in case they changed since service started
                applyTtsSettings()
                
                if (ttsReady) {
                    speakText(text)
                } else {
                    pendingAnnouncement = text
                }
            }
            else -> {
                // Sync (initial start or ACTION_SYNC)
                Thread { syncAndUpdate() }.start()
            }
        }
        return START_STICKY
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announcement")
    }

    private fun syncAndUpdate() {
        val disabled = prefs.disabledCalendarIds
        val allEvents = mutableListOf<CalendarEvent>()

        try {
            allEvents.addAll(DeviceCalendarReader(this).getTodayEvents(disabled))
        } catch (e: Exception) {
            Log.e(TAG, "Device calendar error", e)
        }

        if (prefs.useGoogleCalendar && prefs.isGoogleLoggedIn()) {
            try {
                allEvents.addAll(GoogleCalendarClient(this, prefs).getTodayEvents(disabled))
            } catch (e: Exception) {
                Log.e(TAG, "Google calendar error", e)
            }
        }

        val sorted = allEvents.sortedBy { it.startTime }
        updateNotification(sorted)
        scheduleAnnouncements(sorted)
        scheduleNextSync()
    }

    private fun updateNotification(events: List<CalendarEvent>) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildEventsNotification(events))
    }

    private fun buildPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("Calendar Announcement")
            .setContentText("Syncing...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun buildEventsNotification(events: List<CalendarEvent>): Notification {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("Today's Events (${events.size})")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPi)

        if (events.isEmpty()) {
            builder.setContentText("No events today")
        } else {
            val lines = events.map { "${fmt.format(Date(it.startTime))} ${it.title}" }
            if (lines.size == 1) {
                builder.setContentText(lines[0])
            } else {
                val style = NotificationCompat.InboxStyle()
                lines.forEach { style.addLine(it) }
                builder.setStyle(style).setContentText(lines.first())
            }
        }
        return builder.build()
    }

    private fun scheduleAnnouncements(events: List<CalendarEvent>) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val beforeMs = prefs.announceBeforeMinutes * 60_000L
        val now = System.currentTimeMillis()
        events.forEach { event ->
            val announceAt = event.startTime - beforeMs
            if (announceAt <= now) return@forEach
            val pi = PendingIntent.getBroadcast(
                this,
                (event.id.hashCode() and 0x7FFFFFFF),
                Intent(this, AlarmReceiver::class.java).apply {
                    action = ACTION_ANNOUNCE
                    putExtra(EXTRA_EVENT_TITLE, event.title)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, announceAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, announceAt, pi)
            }
        }
    }

    private fun scheduleNextSync() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextSync = System.currentTimeMillis() + prefs.syncIntervalMinutes * 60_000L
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, AlarmReceiver::class.java).apply { action = ACTION_SYNC },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextSync, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, nextSync, pi)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Calendar Events",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows today's calendar events"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Re-schedule sync alarm so the chain isn't broken if the user swipes the app
        scheduleNextSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
