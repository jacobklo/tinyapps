package net.jacoblo.randommp3alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AlarmStorage.loadAlarms(context).forEach { alarm ->
            if (alarm.enabled) AlarmScheduler.scheduleAlarm(context, alarm)
        }
    }
}
