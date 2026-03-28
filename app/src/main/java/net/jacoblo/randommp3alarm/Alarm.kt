package net.jacoblo.randommp3alarm

import org.json.JSONObject

data class Alarm(
    val id: Int,
    val hour: Int = 7,
    val minute: Int = 0,
    val snoozeCount: Int = 3,
    val snoozeDurationSeconds: Int = 300,
    val directoryPath: String = "/storage/emulated/0/Music",
    val recursive: Boolean = false,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("snoozeCount", snoozeCount)
        put("snoozeDurationSeconds", snoozeDurationSeconds)
        put("directoryPath", directoryPath)
        put("recursive", recursive)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(json: JSONObject) = Alarm(
            id = json.getInt("id"),
            hour = json.getInt("hour"),
            minute = json.getInt("minute"),
            snoozeCount = json.getInt("snoozeCount"),
            snoozeDurationSeconds = json.getInt("snoozeDurationSeconds"),
            directoryPath = json.getString("directoryPath"),
            recursive = json.getBoolean("recursive"),
            enabled = json.getBoolean("enabled")
        )
    }
}
