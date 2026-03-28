package net.jacoblo.calendarannouncement

import android.content.Context
import android.provider.CalendarContract
import java.util.Calendar

class DeviceCalendarReader(private val context: Context) {

    fun getCalendarAccounts(): List<CalendarAccount> {
        val accounts = mutableListOf<CalendarAccount>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0).toString()
                val name = cursor.getString(1) ?: ""
                val accountName = cursor.getString(2) ?: ""
                val accountType = cursor.getString(3) ?: ""
                val color = cursor.getInt(4)
                accounts.add(CalendarAccount(id, name, accountName, accountType, color))
            }
        }
        return accounts
    }

    fun getTodayEvents(disabledCalendarIds: Set<String>): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startOfDay.timeInMillis.toString())
            .appendPath(endOfDay.timeInMillis.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
//            CalendarContract.Instances.CALENDAR_ACCOUNT_NAME
        )
        context.contentResolver.query(
            uri, projection, null, null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getString(4) ?: continue
                if (disabledCalendarIds.contains(calId)) continue
                val eventId = cursor.getString(0) ?: continue
                val title = cursor.getString(1) ?: "(No title)"
                val startTime = cursor.getLong(2)
                val endTime = cursor.getLong(3)
                val calName = cursor.getString(5) ?: ""
                val accountName = cursor.getString(6) ?: ""
                events.add(CalendarEvent(eventId, title, startTime, endTime, calId, calName, accountName))
            }
        }
        return events
    }
}
