package net.jacoblo.randommp3alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("alarm_id", -1)
        val snoozeRemaining = intent.getIntExtra("snooze_remaining", 0)
        if (alarmId == -1) return
        context.startForegroundService(
            Intent(context, AlarmService::class.java).apply {
                putExtra("alarm_id", alarmId)
                putExtra("snooze_remaining", snoozeRemaining)
            }
        )
    }
}
