package net.jacoblo.autoclicker

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Interaction(
    val type: String, // "click" or "drag"
    val x: Int,
    val y: Int,
    val endX: Int = 0, // For drag
    val endY: Int = 0, // For drag
    val duration: Long,
    val delayBefore: Long
)

object RecordingManager {

    fun saveRecording(events: List<Interaction>) {
        val timestamp = System.currentTimeMillis()
        
        val jsonArray = JSONArray()
        events.forEach { event ->
            val jsonObj = JSONObject().apply {
                put("type", event.type)
                put("x", event.x)
                put("y", event.y)
                if (event.type == "drag") {
                    put("endX", event.endX)
                    put("endY", event.endY)
                }
                put("duration", event.duration)
                put("delayBefore", event.delayBefore)
            }
            jsonArray.put(jsonObj)
        }

        val finalJson = JSONObject().apply {
            put("timestamp", timestamp)
            put("events", jsonArray)
        }

        // Use Environment.getExternalStorageDirectory() for correct storage path
        val dir = File(Environment.getExternalStorageDirectory(), "Recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "$timestamp.json")
        file.writeText(finalJson.toString(4)) // Indent 4 for readability
    }
}
