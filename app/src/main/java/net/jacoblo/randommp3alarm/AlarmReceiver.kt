package net.jacoblo.randommp3alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("alarm_id", -1)
        if (alarmId == -1) return
        val alarm = AlarmStorage.loadAlarms(context).find { it.id == alarmId } ?: return
        if (!alarm.enabled) return
        context.startForegroundService(
            Intent(context, AlarmService::class.java).apply {
                putExtra("alarm_id", alarmId)
                putExtra("snooze_remaining", alarm.snoozeCount)
            }
        )
    }
}
