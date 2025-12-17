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

    // Helper variable to hold the currently selected recording file
    var currentSelectedFile: File? = null

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
    
    // Reads a JSON file and parses it back into a list of Interaction objects
    fun loadRecording(file: File): List<Interaction> {
        if (!file.exists()) return emptyList()
        
        val jsonString = file.readText()
        val jsonObject = JSONObject(jsonString)
        val eventsArray = jsonObject.getJSONArray("events")
        val events = mutableListOf<Interaction>()

        for (i in 0 until eventsArray.length()) {
            val obj = eventsArray.getJSONObject(i)
            val type = obj.getString("type")
            events.add(Interaction(
                type = type,
                x = obj.getInt("x"),
                y = obj.getInt("y"),
                endX = if (obj.has("endX")) obj.getInt("endX") else 0,
                endY = if (obj.has("endY")) obj.getInt("endY") else 0,
                duration = obj.getLong("duration"),
                delayBefore = obj.getLong("delayBefore")
            ))
        }
        return events
    }

    fun renameRecording(file: File, newName: String): Boolean {
        val nameWithExt = if (newName.endsWith(".json")) newName else "$newName.json"
        val newFile = File(recordingsDir, nameWithExt)
        if (newFile.exists()) return false
        val success = file.renameTo(newFile)
        if (success && currentSelectedFile == file) {
            currentSelectedFile = newFile
        }
        return success
    }

    fun deleteRecording(file: File): Boolean {
        if (currentSelectedFile == file) {
            currentSelectedFile = null
        }
        return file.delete()
    }
}
