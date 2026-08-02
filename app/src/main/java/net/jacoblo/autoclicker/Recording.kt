package net.jacoblo.autoclicker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

// Coordinates are fractions of screen width/height (0.0..1.0), not pixels, so
// a script survives a different screen size or orientation. Conversion happens
// only at the executor boundary and in the editor's fields.
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

// A hardware/system key, by KeyEvent name: BACK, HOME, APP_SWITCH, VOLUME_UP...
data class KeyEventInteraction(
    val key: String,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

data class LaunchAppInteraction(
    val packageName: String,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

data class ShellInteraction(
    val command: String,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

/** Does nothing but honour its delayBefore, for a pause between actions. */
data class WaitInteraction(
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

/** Assigns the result of an expression to a variable for the rest of the run. */
data class SetVariableInteraction(
    val variable: String,
    val expression: String,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

// New ForLoop interaction. repeatCount <= 0 repeats until stopped or Break.
data class ForLoopInteraction(
    val repeatCount: Int,
    val interactions: List<Interaction>,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

/** One condition and the body that runs when it is the first to hold. */
data class ConditionBranch(val condition: String, val interactions: List<Interaction>)

data class IfInteraction(
    val branches: List<ConditionBranch>,
    val elseBranch: List<Interaction> = emptyList(),
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

data class WhileInteraction(
    val condition: String,
    val interactions: List<Interaction>,
    override val delayBefore: Long,
    override val name: String = ""
) : Interaction()

/** Exits the innermost enclosing Repeat or While. */
data class BreakInteraction(
    override val delayBefore: Long = 0,
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

data class IfStartInteraction(
    val condition: String,
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class ElseIfInteraction(
    val condition: String,
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class ElseInteraction(
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class IfEndInteraction(
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class WhileStartInteraction(
    val condition: String,
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

data class WhileEndInteraction(
    override val delayBefore: Long = 0,
    override val name: String = ""
) : Interaction()

/** At-a-glance description of a recording for the list screen. */
data class RecordingSummary(val actions: Int, val durationMs: Long, val loops: Int) {

    fun describe(): String {
        val parts = mutableListOf("$actions action${if (actions == 1) "" else "s"}")
        if (durationMs > 0) parts.add("~%.1fs".format(durationMs / 1000.0))
        if (loops > 0) parts.add("$loops loop${if (loops == 1) "" else "s"}")
        return parts.joinToString("  ")
    }
}

/**
 * Runtime estimate. Loop bodies are counted repeatCount times; a random-select
 * is counted once through, so anything containing one is approximate.
 */
fun summarize(events: List<Interaction>): RecordingSummary {
    var actions = 0
    var duration = 0L
    var loops = 0

    fun walk(list: List<Interaction>, repeats: Int) {
        list.forEach { event ->
            duration += event.delayBefore * repeats
            when (event) {
                is ClickInteraction -> {
                    actions++
                    duration += event.duration * repeats
                }
                is DragInteraction -> {
                    actions++
                    duration += event.points.sumOf { it.dt } * repeats
                }
                is TextInteraction -> actions++
                is KeyEventInteraction -> actions++
                is LaunchAppInteraction -> actions++
                is ShellInteraction -> actions++
                is SetVariableInteraction -> actions++
                is ForLoopInteraction -> {
                    loops++
                    // An unbounded repeat has no meaningful runtime, so count
                    // its body once rather than reporting zero.
                    walk(event.interactions, repeats * event.repeatCount.coerceAtLeast(1))
                }
                is RandomSelectInteraction -> {
                    loops++
                    walk(event.interactions, repeats)
                }
                is WhileInteraction -> {
                    loops++
                    walk(event.interactions, repeats)
                }
                is IfInteraction -> {
                    // Only one branch runs, so counting them all would overstate
                    // both the action count and the estimate.
                    event.branches.firstOrNull()?.let { walk(it.interactions, repeats) }
                }
                else -> {}
            }
        }
    }

    walk(events, 1)
    return RecordingSummary(actions, duration, loops)
}

object RecordingManager {

    var currentSelectedFile: File? = null

    // Bumped whenever the set of recordings changes, so the list screen updates
    // even while it is already on screen -- recording happens through the
    // bubble without the activity ever pausing.
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val recordingsDir: File
        get() = Storage.recordingsDir

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
        _revision.value++
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
            is KeyEventInteraction -> {
                jsonObj.put("type", "key")
                jsonObj.put("key", event.key)
            }
            is LaunchAppInteraction -> {
                jsonObj.put("type", "launch")
                jsonObj.put("package", event.packageName)
            }
            is ShellInteraction -> {
                jsonObj.put("type", "shell")
                jsonObj.put("command", event.command)
            }
            is WaitInteraction -> {
                jsonObj.put("type", "wait")
            }
            is SetVariableInteraction -> {
                jsonObj.put("type", "set")
                jsonObj.put("variable", event.variable)
                jsonObj.put("expression", event.expression)
            }
            is BreakInteraction -> {
                jsonObj.put("type", "break")
            }
            is IfInteraction -> {
                jsonObj.put("type", "if")
                val branchArray = JSONArray()
                event.branches.forEach { branch ->
                    branchArray.put(JSONObject().apply {
                        put("condition", branch.condition)
                        put("events", eventsToJson(branch.interactions))
                    })
                }
                jsonObj.put("branches", branchArray)
                jsonObj.put("else", eventsToJson(event.elseBranch))
            }
            is WhileInteraction -> {
                jsonObj.put("type", "while")
                jsonObj.put("condition", event.condition)
                jsonObj.put("events", eventsToJson(event.interactions))
            }
            is ForLoopInteraction -> {
                jsonObj.put("type", "loop")
                jsonObj.put("count", event.repeatCount)
                jsonObj.put("events", eventsToJson(event.interactions))
            }
            is RandomSelectInteraction -> {
                jsonObj.put("type", "random_select")
                jsonObj.put("events", eventsToJson(event.interactions))
            }
            else -> return null // Skip editor-only types
        }
        return jsonObj
    }

    private fun eventsToJson(events: List<Interaction>): JSONArray {
        val array = JSONArray()
        events.forEach { child -> eventToJson(child)?.let { array.put(it) } }
        return array
    }

    private fun jsonToEvents(array: JSONArray?): List<Interaction> {
        if (array == null) return emptyList()
        val events = mutableListOf<Interaction>()
        for (i in 0 until array.length()) {
            parseEvent(array.getJSONObject(i))?.let { events.add(it) }
        }
        return events
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
            "key" -> KeyEventInteraction(obj.optString("key", "BACK"), delayBefore, name)
            "launch" -> LaunchAppInteraction(obj.optString("package", ""), delayBefore, name)
            "shell" -> ShellInteraction(obj.optString("command", ""), delayBefore, name)
            "wait" -> WaitInteraction(delayBefore, name)
            "break" -> BreakInteraction(delayBefore, name)
            "set" -> SetVariableInteraction(
                variable = obj.optString("variable", ""),
                expression = obj.optString("expression", "0"),
                delayBefore = delayBefore,
                name = name
            )
            "if" -> {
                val branches = mutableListOf<ConditionBranch>()
                val branchArray = obj.optJSONArray("branches")
                if (branchArray != null) {
                    for (i in 0 until branchArray.length()) {
                        val branchObj = branchArray.getJSONObject(i)
                        branches.add(
                            ConditionBranch(
                                condition = branchObj.optString("condition", "false"),
                                interactions = jsonToEvents(branchObj.optJSONArray("events"))
                            )
                        )
                    }
                }
                IfInteraction(branches, jsonToEvents(obj.optJSONArray("else")), delayBefore, name)
            }
            "while" -> WhileInteraction(
                condition = obj.optString("condition", "false"),
                interactions = jsonToEvents(obj.optJSONArray("events")),
                delayBefore = delayBefore,
                name = name
            )
            "loop" -> ForLoopInteraction(
                repeatCount = obj.getInt("count"),
                interactions = jsonToEvents(obj.optJSONArray("events")),
                delayBefore = delayBefore,
                name = name
            )
            "random_select" -> RandomSelectInteraction(
                interactions = jsonToEvents(obj.optJSONArray("events")),
                delayBefore = delayBefore,
                name = name
            )
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
        if (success) _revision.value++
        return success
    }

    fun deleteRecording(file: File): Boolean {
        if (currentSelectedFile == file) {
            currentSelectedFile = null
        }
        val deleted = file.delete()
        if (deleted) _revision.value++
        return deleted
    }
}
