package net.jacoblo.moodlauncher

import java.time.LocalDate

data class DayNote(
    val emoji: String = "",
    val notes: String = ""
)

/** Canonical map key: "YYYY-MM-DD" */
fun LocalDate.toNoteKey(): String = toString() // LocalDate.toString() is ISO-8601
