package net.jacoblo.autoclicker

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data holder for recording and its metadata
data class RecordingData(val events: List<Interaction>, val globalRandom: Int = 0)

// 1) Separate data classes for Click and Drag
sealed class Interaction {
    abstract val delayBefore: Long
    abstract val name: String
}

data class ClickInteraction(
    val x: Float,
    val y: Float,
    val duration: Long,
    val randomFactor: Int = 0, // Added randomFactor
    // Captured from the digitizer when recorded under root; 0 means "not
    // captured", and the evdev injector substitutes a device-typical value.
    val pressure: Int = 0,
    val touchMajor: Int = 0,
    val touchMinor: Int = 0,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

// 2) Drag data class with multiple coordinates and delta time
data class DragPoint(
    val x: Float,
    val y: Float,
    val dt: Long,
    val pressure: Int = 0,
    val touchMajor: Int = 0,
    val touchMinor: Int = 0
)

data class DragInteraction(
    val points: List<DragPoint>,
    val randomFactorStart: Int = 0, // Added randomFactorStart
    val randomFactorHighest: Int = 0, // Added randomFactorHighest
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

// Text entry into whatever field currently holds input focus
data class TextInteraction(
    val text: String,
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

// Random Select interaction
data class RandomSelectInteraction(
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

data class RandomSelectStartInteraction(
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class RandomSelectEndInteraction(
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

    fun saveRecording(events: List<Interaction>, globalRandom: Int = 0) {
        // ':' is rejected by the FUSE layer that apps write external storage
        // through (EPERM), even though root can create such a file directly.
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        // Second resolution can collide where the old millisecond name could
        // not, and overwriting a recording would lose it silently.
        var file = File(recordingsDir, "$stamp.json")
        var suffix = 2
        while (file.exists()) {
            file = File(recordingsDir, "${stamp}_$suffix.json")
            suffix++
        }
        saveRecordingToFile(file, events, globalRandom)
    }

    fun saveRecordingToFile(file: File, events: List<Interaction>, globalRandom: Int = 0) {
        val timestamp = System.currentTimeMillis() // Or preserve original timestamp if needed, but updating it is fine for modification time

        val jsonArray = JSONArray()
        events.forEach { event ->
            eventToJson(event)?.let { jsonArray.put(it) }
        }

        val finalJson = JSONObject().apply {
            put("timestamp", timestamp)
            put("globalRandom", globalRandom)
            put("events", jsonArray)
        }

        file.writeText(finalJson.toString(4))
    }

    // Omitted entirely when nothing was captured, so recordings made on the
    // accessibility backend keep the original compact shape.
    private fun putTouch(target: JSONObject, pressure: Int, touchMajor: Int, touchMinor: Int) {
        if (pressure == 0 && touchMajor == 0 && touchMinor == 0) return
        target.put("p", pressure)
        target.put("maj", touchMajor)
        target.put("min", touchMinor)
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
                jsonObj.put("randomFactor", event.randomFactor) // Save randomFactor
                putTouch(jsonObj, event.pressure, event.touchMajor, event.touchMinor)
            }
            is DragInteraction -> {
                jsonObj.put("type", "drag")
                val pointsArray = JSONArray()
                event.points.forEach { point ->
                    val pointObj = JSONObject()
                    pointObj.put("x", point.x)
                    pointObj.put("y", point.y)
                    pointObj.put("dt", point.dt)
                    putTouch(pointObj, point.pressure, point.touchMajor, point.touchMinor)
                    pointsArray.put(pointObj)
                }
                jsonObj.put("points", pointsArray)
                jsonObj.put("randomFactorStart", event.randomFactorStart) // Save randomFactorStart
                jsonObj.put("randomFactorHighest", event.randomFactorHighest) // Save randomFactorHighest
            }
            is TextInteraction -> {
                jsonObj.put("type", "text")
                jsonObj.put("text", event.text)
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
            is RandomSelectInteraction -> {
                jsonObj.put("type", "random_select")
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

    fun loadRecording(file: File): RecordingData {
        if (!file.exists()) return RecordingData(emptyList())

        val events = mutableListOf<Interaction>()
        var globalRandom = 0
        try {
            val jsonString = file.readText()
            val jsonObject = JSONObject(jsonString)
            globalRandom = jsonObject.optInt("globalRandom", 0)
            val eventsArray = jsonObject.getJSONArray("events")

            for (i in 0 until eventsArray.length()) {
                val obj = eventsArray.getJSONObject(i)
                parseEvent(obj)?.let { events.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return RecordingData(events, globalRandom)
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
                    randomFactor = obj.optInt("randomFactor", 0), // Load randomFactor
                    pressure = obj.optInt("p", 0),
                    touchMajor = obj.optInt("maj", 0),
                    touchMinor = obj.optInt("min", 0),
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
                            dt = pObj.getLong("dt"),
                            pressure = pObj.optInt("p", 0),
                            touchMajor = pObj.optInt("maj", 0),
                            touchMinor = pObj.optInt("min", 0)
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
                DragInteraction(
                    points = points,
                    randomFactorStart = obj.optInt("randomFactorStart", 0), // Load randomFactorStart
                    randomFactorHighest = obj.optInt("randomFactorHighest", 0), // Load randomFactorHighest
                    delayBefore = delayBefore,
                    name = name
                )
            }
            "text" -> {
                TextInteraction(
                    text = obj.optString("text", ""),
                    delayBefore = delayBefore,
                    name = name
                )
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
            "random_select" -> {
                val eventsArray = obj.getJSONArray("events")
                val children = mutableListOf<Interaction>()
                for (i in 0 until eventsArray.length()) {
                    val childObj = eventsArray.getJSONObject(i)
                    parseEvent(childObj)?.let { children.add(it) }
                }
                RandomSelectInteraction(children, delayBefore, name)
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
