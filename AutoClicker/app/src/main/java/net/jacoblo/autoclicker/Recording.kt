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

    private val recordingsDir: File
        get() {
            val dir = File(Environment.getExternalStorageDirectory(), "Recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

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

        val file = File(recordingsDir, "$timestamp.json")
        file.writeText(finalJson.toString(4)) // Indent 4 for readability
    }

    fun getRecordings(): List<File> {
        return recordingsDir.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()
    }

    fun renameRecording(file: File, newName: String): Boolean {
        val nameWithExt = if (newName.endsWith(".json")) newName else "$newName.json"
        val newFile = File(recordingsDir, nameWithExt)
        if (newFile.exists()) return false
        return file.renameTo(newFile)
    }

    fun deleteRecording(file: File): Boolean {
        return file.delete()
    }
}
