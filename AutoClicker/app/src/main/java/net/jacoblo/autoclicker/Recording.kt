package net.jacoblo.autoclicker

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 1) Separate data classes for Click and Drag
sealed class Interaction {
    abstract val delayBefore: Long
    abstract val name: String
}

data class ClickInteraction(
    val x: Float,
    val y: Float,
    val duration: Long,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

// 2) Drag data class with multiple coordinates and delta time
data class DragPoint(
    val x: Float,
    val y: Float,
    val dt: Long
)

data class DragInteraction(
    val points: List<DragPoint>,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

// New ForLoop interaction
data class ForLoopInteraction(
    val repeatCount: Int,
    val interactions: List<Interaction>,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

// Editor helper types
data class LoopStartInteraction(
    val repeatCount: Int,
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class LoopEndInteraction(
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

object RecordingManager {

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
        val file = File(recordingsDir, "$timestamp.json")
        saveRecordingToFile(file, events)
    }

    fun saveRecordingToFile(file: File, events: List<Interaction>) {
        val timestamp = System.currentTimeMillis() // Or preserve original timestamp if needed, but updating it is fine for modification time
        
        val jsonArray = JSONArray()
        events.forEach { event ->
            eventToJson(event)?.let { jsonArray.put(it) }
        }

        val finalJson = JSONObject().apply {
            put("timestamp", timestamp)
            put("events", jsonArray)
        }

        file.writeText(finalJson.toString(4))
    }

    private fun eventToJson(event: Interaction): JSONObject? {
        val jsonObj = JSONObject()
        jsonObj.put("delayBefore", event.delayBefore)
        jsonObj.put("name", event.name)

        when (event) {
            is ClickInteraction -> {
                jsonObj.put("type", "click")
                jsonObj.put("x", event.x)
                jsonObj.put("y", event.y)
                jsonObj.put("duration", event.duration)
            }
            is DragInteraction -> {
                jsonObj.put("type", "drag")
                val pointsArray = JSONArray()
                event.points.forEach { point ->
                    val pointObj = JSONObject()
                    pointObj.put("x", point.x)
                    pointObj.put("y", point.y)
                    pointObj.put("dt", point.dt)
                    pointsArray.put(pointObj)
                }
                jsonObj.put("points", pointsArray)
            }
            is ForLoopInteraction -> {
                jsonObj.put("type", "loop")
                jsonObj.put("count", event.repeatCount)
                val eventsArray = JSONArray()
                event.interactions.forEach { child ->
                    eventToJson(child)?.let { eventsArray.put(it) }
                }
                jsonObj.put("events", eventsArray)
            }
            else -> return null // Skip editor-only types
        }
        return jsonObj
    }

    fun getRecordings(): List<File> {
        return recordingsDir.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()
    }
    
    fun loadRecording(file: File): List<Interaction> {
        if (!file.exists()) return emptyList()
        
        val events = mutableListOf<Interaction>()
        try {
            val jsonString = file.readText()
            val jsonObject = JSONObject(jsonString)
            val eventsArray = jsonObject.getJSONArray("events")

            for (i in 0 until eventsArray.length()) {
                val obj = eventsArray.getJSONObject(i)
                parseEvent(obj)?.let { events.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return events
    }

    private fun parseEvent(obj: JSONObject): Interaction? {
        val type = obj.optString("type")
        val delayBefore = obj.optLong("delayBefore", 0L)
        val name = obj.optString("name", "")

        return when (type) {
            "click" -> {
                ClickInteraction(
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat(),
                    duration = obj.getLong("duration"),
                    delayBefore = delayBefore,
                    name = name
                )
            }
            "drag" -> {
                val points = mutableListOf<DragPoint>()
                if (obj.has("points")) {
                    val pointsArray = obj.getJSONArray("points")
                    for (j in 0 until pointsArray.length()) {
                        val pObj = pointsArray.getJSONObject(j)
                        points.add(DragPoint(
                            x = pObj.getDouble("x").toFloat(),
                            y = pObj.getDouble("y").toFloat(),
                            dt = pObj.getLong("dt")
                        ))
                    }
                } else {
                    // Backward compatibility for old format: start (x,y) -> end (endX, endY)
                    val startX = obj.getDouble("x").toFloat()
                    val startY = obj.getDouble("y").toFloat()
                    val endX = obj.optDouble("endX", 0.0).toFloat()
                    val endY = obj.optDouble("endY", 0.0).toFloat()
                    val duration = obj.optLong("duration", 100)
                    
                    points.add(DragPoint(startX, startY, 0))
                    points.add(DragPoint(endX, endY, duration))
                }
                DragInteraction(points, delayBefore, name)
            }
            "loop" -> {
                val count = obj.getInt("count")
                val eventsArray = obj.getJSONArray("events")
                val children = mutableListOf<Interaction>()
                for (i in 0 until eventsArray.length()) {
                    val childObj = eventsArray.getJSONObject(i)
                    parseEvent(childObj)?.let { children.add(it) }
                }
                ForLoopInteraction(count, children, delayBefore, name)
            }
            else -> null
        }
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
