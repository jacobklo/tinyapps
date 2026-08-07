package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "autoclicker.recording"

// Data holder for recording and its metadata
data class RecordingData(val events: List<RuntimeStep>, val globalRandom: Int = 0)

// Two minutes covers a code that was requested moments ago while still ruling
// out the one from the previous login.
const val DEFAULT_CODE_MAX_AGE_S = 120L

// The service polls Gmail every 50 seconds, so anything shorter than that can
// time out before a code that has already arrived is even visible.
const val DEFAULT_CODE_TIMEOUT_MS = 90000L

/**
 * Anything the editor can hold in its list of rows.
 *
 * Two kinds, and the difference is what can happen to them. A [RuntimeStep] is
 * a step in the ordinary sense: it is executed, and it is written to and read
 * back from a file. An [EditorMarker] exists only while a block is being
 * edited as a flat list, and is folded back into the tree on save.
 *
 * They are separated because everything that touches only one of them used to
 * have to handle both and quietly did nothing for the other half. The
 * serialiser skipped markers by returning null, the executor by an else arm
 * commented "never reach playback" -- so a *runtime* step missing from either
 * was indistinguishable from a marker, and went equally unmentioned. Splitting
 * the type turns both of those into cases the compiler counts.
 */
sealed interface Step {
	val delayBefore: Long
	val name: String
}

/** A step that runs, and the only kind that reaches a file. */
sealed class RuntimeStep : Step

/** A row that only exists while a block is flattened for editing. */
sealed class EditorMarker : Step

/**
 * Where a gesture's coordinates are measured from.
 *
 * Blank means the screen corner, and x/y are fractions of screen width/height
 * (0.0..1.0) so the script survives a different screen size. Otherwise it names
 * a saved area, x/y are *pixels* from wherever that area is found at playback
 * time, and the gesture is skipped when it is not on screen.
 *
 * The unit differs on purpose. A saved area only matches at the resolution it
 * was captured at, so an offset from it expressed as a fraction of some other
 * screen would be wrong precisely when the anchor was right.
 */
typealias AnchorImage = String

// Conversion between fractions and pixels happens only at the executor boundary
// and in the editor's fields.
data class ClickStep(
	val x: Float,
	val y: Float,
	val duration: Long,
	val randomFactor: Int = 0, // Added randomFactor
	// Two taps in quick succession read as a double tap; anything above one
	// repeats the same press.
	val taps: Int = 1,
	val anchor: AnchorImage = "",
	// Read off the screen instead of matched as a picture. Takes precedence
	// over [anchor] when both are set.
	val anchorText: String = "",
	// Captured from the digitizer when recorded under root; 0 means "not
	// captured", and the evdev injector substitutes a device-typical value.
	val pressure: Int = 0,
	val touchMajor: Int = 0,
	val touchMinor: Int = 0,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

// 2) Drag data class with multiple coordinates and delta time
data class DragPoint(
	val x: Float,
	val y: Float,
	val dt: Long,
	val pressure: Int = 0,
	val touchMajor: Int = 0,
	val touchMinor: Int = 0
)

data class DragStep(
	val points: List<DragPoint>,
	val randomFactorStart: Int = 0, // Added randomFactorStart
	val randomFactorHighest: Int = 0, // Added randomFactorHighest
	// Anchoring moves the whole path with the image, so a swipe that starts on
	// a list item keeps starting on it wherever the list has scrolled to.
	val anchor: AnchorImage = "",
	val anchorText: String = "",
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

// Text entry into whatever field currently holds input focus
data class TextStep(
	val text: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

// A hardware/system key, by KeyEvent name: BACK, HOME, APP_SWITCH, VOLUME_UP...
data class KeyEventStep(
	val key: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

data class LaunchAppStep(
	val packageName: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

data class ShellStep(
	val command: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/**
 * Shows a toast. [message] is plain text, except that anything in braces is
 * evaluated as an expression, so a script can report what it is doing:
 * "attempt {count} of 5".
 */
data class ToastStep(
	val message: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/** Does nothing but honour its delayBefore, for a pause between actions. */
data class WaitStep(
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/**
 * Waits for six-digit codes from the gmail-six-digit service and stores them,
 * ranked best-first, as a list in [variable].
 *
 * [maxAgeSeconds] is what makes this a wait rather than a read: the service
 * keeps ten minutes of history, so without it the step would hand back the code
 * from the previous login the instant it was asked.
 */
data class WaitCodeStep(
	val variable: String,
	val maxAgeSeconds: Long,
	val timeoutMs: Long,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/**
 * Puts the cursor in the editable field on screen, wherever it is, and stores
 * how many characters it already holds in [variable].
 *
 * Nothing is touched when the field already has focus, which is the common case
 * on a screen that opens ready to type. The character count is what makes
 * clearing exact: backspace that many times rather than a number picked to be
 * large enough for anything.
 */
data class FocusFieldStep(
	val variable: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/** Assigns the result of an expression to a variable for the rest of the run. */
data class SetVariableStep(
	val variable: String,
	val expression: String,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

// New ForLoop step. repeatCount <= 0 repeats until stopped or Break.
data class ForLoopStep(
	val repeatCount: Int,
	val steps: List<RuntimeStep>,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/** One condition and the body that runs when it is the first to hold. */
data class ConditionBranch(val condition: String, val steps: List<RuntimeStep>)

data class IfStep(
	val branches: List<ConditionBranch>,
	val elseBranch: List<RuntimeStep> = emptyList(),
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

data class WhileStep(
	val condition: String,
	val steps: List<RuntimeStep>,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

/** Exits the innermost enclosing Repeat or While. */
data class BreakStep(
	override val delayBefore: Long = 0,
	override val name: String = ""
) : RuntimeStep()

// Random Select step
data class RandomSelectStep(
	val steps: List<RuntimeStep>,
	override val delayBefore: Long,
	override val name: String = ""
) : RuntimeStep()

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
fun summarize(events: List<RuntimeStep>): RecordingSummary {
	var actions = 0
	var duration = 0L
	var loops = 0

	fun walk(list: List<RuntimeStep>, repeats: Int) {
		list.forEach { event ->
			duration += event.delayBefore * repeats
			when (event) {
				is ClickStep -> {
					actions++
					duration += event.duration * event.taps.coerceAtLeast(1) * repeats
				}
				is DragStep -> {
					actions++
					duration += event.points.sumOf { it.dt } * repeats
				}
				is TextStep -> actions++
				is KeyEventStep -> actions++
				is ToastStep -> actions++
				is LaunchAppStep -> actions++
				is ShellStep -> actions++
				is SetVariableStep -> actions++
				is FocusFieldStep -> actions++
				is WaitCodeStep -> {
					actions++
					// Its real cost is however long the code takes to arrive;
					// the timeout is the only bound the editor can show.
					duration += event.timeoutMs * repeats
				}
				is ForLoopStep -> {
					loops++
					// An unbounded repeat has no meaningful runtime, so count
					// its body once rather than reporting zero.
					walk(event.steps, repeats * event.repeatCount.coerceAtLeast(1))
				}
				is RandomSelectStep -> {
					loops++
					walk(event.steps, repeats)
				}
				is WhileStep -> {
					loops++
					walk(event.steps, repeats)
				}
				is IfStep -> {
					// Only one branch runs, so counting them all would overstate
					// both the action count and the estimate.
					event.branches.firstOrNull()?.let { walk(it.steps, repeats) }
				}
				// Neither is an action, and the delay above is already counted.
				is WaitStep -> {}
				is BreakStep -> {}
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

	fun saveRecording(events: List<RuntimeStep>, globalRandom: Int = 0) {
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

	fun saveRecordingToFile(file: File, events: List<RuntimeStep>, globalRandom: Int = 0) {
		val jsonArray = JSONArray()
		events.forEach { event ->
			jsonArray.put(eventToJson(event))
		}

		val finalJson = JSONObject().apply {
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

	// Absent means absolute, which is what every recorded gesture is.
	private fun putAnchor(target: JSONObject, anchor: AnchorImage, anchorText: String) {
		if (anchor.isNotBlank()) target.put("anchor", anchor)
		if (anchorText.isNotBlank()) target.put("anchorText", anchorText)
	}

	private fun eventToJson(event: RuntimeStep): JSONObject {
		val jsonObj = JSONObject()
		jsonObj.put("delayBefore", event.delayBefore)
		jsonObj.put("name", event.name)

		when (event) {
			is ClickStep -> {
				jsonObj.put("type", "click")
				jsonObj.put("x", event.x)
				jsonObj.put("y", event.y)
				jsonObj.put("duration", event.duration)
				jsonObj.put("randomFactor", event.randomFactor) // Save randomFactor
				if (event.taps > 1) jsonObj.put("taps", event.taps)
				putAnchor(jsonObj, event.anchor, event.anchorText)
				putTouch(jsonObj, event.pressure, event.touchMajor, event.touchMinor)
			}
			is DragStep -> {
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
				putAnchor(jsonObj, event.anchor, event.anchorText)
			}
			is TextStep -> {
				jsonObj.put("type", "text")
				jsonObj.put("text", event.text)
			}
			is KeyEventStep -> {
				jsonObj.put("type", "key")
				jsonObj.put("key", event.key)
			}
			is LaunchAppStep -> {
				jsonObj.put("type", "launch")
				jsonObj.put("package", event.packageName)
			}
			is ShellStep -> {
				jsonObj.put("type", "shell")
				jsonObj.put("command", event.command)
			}
			is WaitStep -> {
				jsonObj.put("type", "wait")
			}
			is ToastStep -> {
				jsonObj.put("type", "toast")
				jsonObj.put("message", event.message)
			}
			is FocusFieldStep -> {
				jsonObj.put("type", "focus_field")
				jsonObj.put("variable", event.variable)
			}
			is SetVariableStep -> {
				jsonObj.put("type", "set")
				jsonObj.put("variable", event.variable)
				jsonObj.put("expression", event.expression)
			}
			is WaitCodeStep -> {
				jsonObj.put("type", "wait_code")
				jsonObj.put("variable", event.variable)
				jsonObj.put("maxAgeSeconds", event.maxAgeSeconds)
				jsonObj.put("timeoutMs", event.timeoutMs)
			}
			is BreakStep -> {
				jsonObj.put("type", "break")
			}
			is IfStep -> {
				jsonObj.put("type", "if")
				val branchArray = JSONArray()
				event.branches.forEach { branch ->
					branchArray.put(JSONObject().apply {
						put("condition", branch.condition)
						put("events", eventsToJson(branch.steps))
					})
				}
				jsonObj.put("branches", branchArray)
				jsonObj.put("else", eventsToJson(event.elseBranch))
			}
			is WhileStep -> {
				jsonObj.put("type", "while")
				jsonObj.put("condition", event.condition)
				jsonObj.put("events", eventsToJson(event.steps))
			}
			is ForLoopStep -> {
				jsonObj.put("type", "loop")
				jsonObj.put("count", event.repeatCount)
				jsonObj.put("events", eventsToJson(event.steps))
			}
			is RandomSelectStep -> {
				jsonObj.put("type", "random_select")
				jsonObj.put("events", eventsToJson(event.steps))
			}
		}
		return jsonObj
	}

	private fun eventsToJson(events: List<RuntimeStep>): JSONArray {
		val array = JSONArray()
		events.forEach { child -> array.put(eventToJson(child)) }
		return array
	}

	private fun jsonToEvents(array: JSONArray?): List<RuntimeStep> {
		if (array == null) return emptyList()
		val events = mutableListOf<RuntimeStep>()
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

		val events = mutableListOf<RuntimeStep>()
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
			Log.w(TAG, "cannot read recording ${file.name}", e)
		}
		return RecordingData(events, globalRandom)
	}

	private fun parseEvent(obj: JSONObject): RuntimeStep? {
		val type = obj.optString("type")
		val delayBefore = obj.optLong("delayBefore", 0L)
		val name = obj.optString("name", "")

		return when (type) {
			"click" -> {
				ClickStep(
					x = obj.getDouble("x").toFloat(),
					y = obj.getDouble("y").toFloat(),
					duration = obj.getLong("duration"),
					randomFactor = obj.optInt("randomFactor", 0), // Load randomFactor
					taps = obj.optInt("taps", 1),
					anchor = obj.optString("anchor", ""),
					anchorText = obj.optString("anchorText", ""),
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
				DragStep(
					points = points,
					randomFactorStart = obj.optInt("randomFactorStart", 0), // Load randomFactorStart
					randomFactorHighest = obj.optInt("randomFactorHighest", 0), // Load randomFactorHighest
					anchor = obj.optString("anchor", ""),
					anchorText = obj.optString("anchorText", ""),
					delayBefore = delayBefore,
					name = name
				)
			}
			"text" -> {
				TextStep(
					text = obj.optString("text", ""),
					delayBefore = delayBefore,
					name = name
				)
			}
			"key" -> KeyEventStep(obj.optString("key", "BACK"), delayBefore, name)
			"launch" -> LaunchAppStep(obj.optString("package", ""), delayBefore, name)
			"shell" -> ShellStep(obj.optString("command", ""), delayBefore, name)
			"wait" -> WaitStep(delayBefore, name)
			"toast" -> ToastStep(obj.optString("message", ""), delayBefore, name)
			"break" -> BreakStep(delayBefore, name)
			"focus_field" -> FocusFieldStep(
				variable = obj.optString("variable", "field"),
				delayBefore = delayBefore,
				name = name
			)
			"set" -> SetVariableStep(
				variable = obj.optString("variable", ""),
				expression = obj.optString("expression", "0"),
				delayBefore = delayBefore,
				name = name
			)
			"wait_code" -> WaitCodeStep(
				variable = obj.optString("variable", "codes"),
				maxAgeSeconds = obj.optLong("maxAgeSeconds", DEFAULT_CODE_MAX_AGE_S),
				timeoutMs = obj.optLong("timeoutMs", DEFAULT_CODE_TIMEOUT_MS),
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
								steps = jsonToEvents(branchObj.optJSONArray("events"))
							)
						)
					}
				}
				IfStep(branches, jsonToEvents(obj.optJSONArray("else")), delayBefore, name)
			}
			"while" -> WhileStep(
				condition = obj.optString("condition", "false"),
				steps = jsonToEvents(obj.optJSONArray("events")),
				delayBefore = delayBefore,
				name = name
			)
			"loop" -> ForLoopStep(
				repeatCount = obj.getInt("count"),
				steps = jsonToEvents(obj.optJSONArray("events")),
				delayBefore = delayBefore,
				name = name
			)
			"random_select" -> RandomSelectStep(
				steps = jsonToEvents(obj.optJSONArray("events")),
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
