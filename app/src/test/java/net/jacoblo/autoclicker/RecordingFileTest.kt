package net.jacoblo.autoclicker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a recording survives being written to disk and read back.
 *
 * The codec is two switches over the step types that have to agree, and neither
 * is exhaustive: a type missing from eventToJson is dropped on save, and a tag
 * missing from parseEvent is dropped on load. Both losses are silent, and a
 * script simply comes back shorter than it went in.
 */
class RecordingFileTest {

	@get:Rule
	val temp = TemporaryFolder()

	private fun roundTrip(steps: List<RuntimeStep>, globalRandom: Int = 0): RecordingData {
		val file = File(temp.root, "recording.json")
		RecordingManager.saveRecordingToFile(file, steps, globalRandom)
		return RecordingManager.loadRecording(file)
	}

	/**
	 * Unit tests run against the stub android.jar with returnDefaultValues on,
	 * where org.json would accept every put and hand back an empty object,
	 * making every assertion below pass without testing anything.
	 */
	@Test
	fun theRealJsonImplementationIsOnTheClasspath() {
		assertEquals("""{"a":1}""", JSONObject().put("a", 1).toString())
	}

	/** One of every step type that reaches a file, in one recording. */
	private fun everyRuntimeStep(): List<RuntimeStep> = listOf(
		ClickStep(
			x = 0.25f,
			y = 0.75f,
			duration = 120,
			randomFactor = 3,
			taps = 2,
			anchor = "start_button",
			delayBefore = 500,
			name = "a tap"
		),
		// Anchored by phrase rather than picture, and carrying digitizer samples,
		// so both of the click fields that are omitted when unset are covered.
		ClickStep(
			x = 90f,
			y = 16f,
			duration = 50,
			anchorText = "Continue",
			pressure = 45,
			touchMajor = 130,
			touchMinor = 120,
			delayBefore = 0
		),
		DragStep(
			points = listOf(
				DragPoint(0.5f, 0.7f, 0),
				DragPoint(0.5f, 0.5f, 40, pressure = 50, touchMajor = 100, touchMinor = 90),
				DragPoint(0.5f, 0.3f, 60)
			),
			randomFactorStart = 2,
			randomFactorHighest = 7,
			anchor = "list_row",
			delayBefore = 100,
			name = "a swipe"
		),
		TextStep("hello {name}", delayBefore = 10, name = "typing"),
		KeyEventStep("BACK", delayBefore = 20),
		LaunchAppStep("com.example.app", delayBefore = 30),
		ShellStep("am force-stop com.example.app", delayBefore = 40),
		ToastStep("attempt {count}", delayBefore = 50, enabled = false),
		WaitStep(delayBefore = 1000),
		CommentStep("what the next few steps are for"),
		HttpGetStep(url = "http://192.168.2.2:5553/codes", variable = "response", timeoutMs = 90000, intervalMs = 2000, delayBefore = 60),
		FocusFieldStep("field", delayBefore = 70),
		SetVariableStep("count", "count + 1", delayBefore = 80),
		ForLoopStep(
			repeatCount = 3,
			steps = listOf(
				BreakStep(delayBefore = 5),
				WhileStep(
					condition = "count < 3",
					steps = listOf(ToastStep("inner", delayBefore = 0)),
					delayBefore = 90
				)
			),
			delayBefore = 100,
			name = "a loop"
		),
		IfStep(
			branches = listOf(
				ConditionBranch("a == 1", listOf(TextStep("first", delayBefore = 0))),
				ConditionBranch("a == 2", listOf(TextStep("second", delayBefore = 0)))
			),
			elseBranch = listOf(
				RandomSelectStep(
					steps = listOf(
						ToastStep("one", delayBefore = 0),
						ToastStep("two", delayBefore = 0)
					),
					delayBefore = 0
				)
			),
			delayBefore = 110,
			name = "a branch"
		)
	)

	@Test
	fun everyStepTypeSurvivesTheRoundTrip() {
		val steps = everyRuntimeStep()

		assertEquals(steps, roundTrip(steps).events)
	}

	/**
	 * The count is the part that catches a dropped type. Comparing whole trees
	 * says they differ; this says how many steps went missing.
	 */
	@Test
	fun nothingIsDroppedOnTheWayThroughTheFile() {
		val steps = everyRuntimeStep()

		assertEquals(steps.size, roundTrip(steps).events.size)
	}

	@Test
	fun theGlobalRandomComesBack() {
		assertEquals(250, roundTrip(listOf(WaitStep(delayBefore = 0)), globalRandom = 250).globalRandom)
	}

	/**
	 * Only written when a step is off, so saving a recording that nobody has
	 * switched anything in does not rewrite every step in the file -- and a
	 * recording written before the flag existed loads with everything on.
	 */
	@Test
	fun theEnabledFlagIsWrittenOnlyWhenAStepIsOff() {
		val file = File(temp.root, "flags.json")
		RecordingManager.saveRecordingToFile(
			file,
			listOf(WaitStep(delayBefore = 0), WaitStep(delayBefore = 0, enabled = false))
		)

		val events = JSONObject(file.readText()).getJSONArray("events")
		assertFalse(events.getJSONObject(0).has("enabled"))
		assertFalse(events.getJSONObject(1).getBoolean("enabled"))
	}

	/**
	 * A tag the parser does not know is skipped rather than aborting the load,
	 * so one unreadable step does not cost the rest of the script.
	 */
	@Test
	fun anUnknownStepTypeIsSkippedRatherThanFatal() {
		val file = File(temp.root, "unknown.json")
		file.writeText(
			"""
			{
			  "globalRandom": 0,
			  "events": [
			    {"type": "toast", "message": "before", "delayBefore": 0, "name": ""},
			    {"type": "teleport", "delayBefore": 0, "name": ""},
			    {"type": "toast", "message": "after", "delayBefore": 0, "name": ""}
			  ]
			}
			""".trimIndent()
		)

		val loaded = RecordingManager.loadRecording(file)

		assertEquals(
			listOf(ToastStep("before", delayBefore = 0), ToastStep("after", delayBefore = 0)),
			loaded.events
		)
	}

	/** A file that is not JSON at all leaves the run empty rather than throwing. */
	@Test
	fun anUnreadableFileLoadsAsNothing() {
		val file = File(temp.root, "broken.json")
		file.writeText("this is not json")

		assertTrue(RecordingManager.loadRecording(file).events.isEmpty())
	}

	@Test
	fun aMissingFileLoadsAsNothing() {
		assertTrue(RecordingManager.loadRecording(File(temp.root, "absent.json")).events.isEmpty())
	}
}
