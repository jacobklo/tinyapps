package net.jacoblo.randommp3alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

class AlarmService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var currentAlarm: Alarm? = null
    private var currentAlarmId: Int = -1
    private var currentSnoozeRemaining: Int = 0

    companion object {
        const val ACTION_DISMISS = "net.jacoblo.randommp3alarm.DISMISS"
        const val ACTION_STOP_ALL = "net.jacoblo.randommp3alarm.STOP_ALL"
        const val CHANNEL_ID = "alarm_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                // Snooze countdown starts NOW after user silences
                if (currentSnoozeRemaining > 0 && currentAlarm != null) {
                    scheduleSnooze(currentAlarmId, currentAlarm!!.snoozeDurationSeconds, currentSnoozeRemaining - 1)
                }
                stopAudio()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALL -> {
                stopAll(intent.getIntExtra("alarm_id", currentAlarmId))
                return START_NOT_STICKY
            }
        }

        val alarmId = intent?.getIntExtra("alarm_id", -1) ?: -1
        val snoozeRemaining = intent?.getIntExtra("snooze_remaining", 0) ?: 0
        if (alarmId == -1) {
            stopSelf()
            return START_NOT_STICKY
        }

        val alarm = AlarmStorage.loadAlarms(this).find { it.id == alarmId }
        if (alarm == null || !alarm.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentAlarm = alarm
        currentAlarmId = alarmId
        currentSnoozeRemaining = snoozeRemaining

        val notification = buildNotification(alarm, snoozeRemaining)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        // Launch AlarmActivity directly so it shows whether screen is on or off
        val activityIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("alarm_id", alarm.id)
            putExtra("snooze_remaining", snoozeRemaining)
        }
        startActivity(activityIntent)

        playRandomMp3(alarm)

        // Reschedule for next day
        AlarmScheduler.scheduleAlarm(this, alarm)

        return START_NOT_STICKY
    }

    private fun playRandomMp3(alarm: Alarm) {
        val files = findAudioFiles(alarm.directoryPath, alarm.recursive)
        if (files.isEmpty()) return
        val file = files.random()
        val stream = AlarmStorage.loadAudioStream(this)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(streamToUsage(stream))
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            try {
                setDataSource(file.absolutePath)
                isLooping = false
                setOnCompletionListener {
                    // Audio finished naturally - snooze countdown starts now
                    if (currentSnoozeRemaining > 0) {
                        scheduleSnooze(currentAlarmId, alarm.snoozeDurationSeconds, currentSnoozeRemaining - 1)
                    }
                    stopAudio()
                }
                prepare()
                start()
            } catch (e: Exception) {
                release()
                mediaPlayer = null
            }
        }
    }

    private fun findAudioFiles(path: String, recursive: Boolean): List<File> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val exts = setOf("mp3", "m4a", "ogg", "wav", "flac", "aac")
        return if (recursive) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in exts }
                .toList()
        } else {
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts } ?: emptyList()
        }
    }

    private fun streamToUsage(stream: Int): Int = when (stream) {
        AudioManager.STREAM_ALARM -> AudioAttributes.USAGE_ALARM
        AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
        AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        else -> AudioAttributes.USAGE_MEDIA
    }

    private fun scheduleSnooze(alarmId: Int, durationSeconds: Int, snoozeRemaining: Int) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) return
        val pi = PendingIntent.getBroadcast(
            this, alarmId + 10000,
            Intent(this, SnoozeReceiver::class.java).apply {
                putExtra("alarm_id", alarmId)
                putExtra("snooze_remaining", snoozeRemaining)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + durationSeconds * 1000L,
            pi
        )
    }

    private fun cancelSnooze(alarmId: Int) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, alarmId + 10000,
            Intent(this, SnoozeReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { am.cancel(it) }
    }

    private fun stopAudio() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopAll(alarmId: Int) {
        cancelSnooze(alarmId)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(alarm: Alarm, snoozeRemaining: Int): Notification {
        val alarmActivityIntent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra("alarm_id", alarm.id)
            putExtra("snooze_remaining", snoozeRemaining)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, alarm.id + 50000, alarmActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPi = PendingIntent.getService(
            this, alarm.id + 20000,
            Intent(this, AlarmService::class.java).apply {
                action = ACTION_DISMISS
                putExtra("alarm_id", alarm.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAllPi = PendingIntent.getService(
            this, alarm.id + 30000,
            Intent(this, AlarmService::class.java).apply {
                action = ACTION_STOP_ALL
                putExtra("alarm_id", alarm.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm - ${String.format("%02d:%02d", alarm.hour, alarm.minute)}")
            .setContentText(if (snoozeRemaining > 0) "Tap to view - $snoozeRemaining snooze(s) remaining" else "Tap to dismiss")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_pause, "Dismiss", dismissPi)
            .addAction(android.R.drawable.ic_delete, "Stop All", stopAllPi)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Alarm Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}