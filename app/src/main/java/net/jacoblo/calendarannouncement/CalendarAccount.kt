package net.jacoblo.calendarannouncement

data class CalendarAccount(
    val id: String,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val isEnabled: Boolean = true
)
