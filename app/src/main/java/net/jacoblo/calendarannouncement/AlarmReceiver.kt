package net.jacoblo.calendarannouncement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NotificationService.ACTION_SYNC -> {
                NotificationService.start(context)
            }
            NotificationService.ACTION_ANNOUNCE -> {
                val title = intent.getStringExtra(NotificationService.EXTRA_EVENT_TITLE) ?: return
                NotificationService.announce(context, title)
            }
        }
    }
}
