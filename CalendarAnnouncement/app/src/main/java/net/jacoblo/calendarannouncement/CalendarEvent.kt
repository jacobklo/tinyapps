package net.jacoblo.calendarannouncement

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val calendarId: String,
    val calendarName: String,
    val accountName: String
)
