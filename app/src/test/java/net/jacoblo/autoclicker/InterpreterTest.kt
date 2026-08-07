package net.jacoblo.autoclicker

import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each kind of step actually does.
 *
 * Everything here used to be claimed only by a comment, because the executor
 * reached the touchscreen, the root shell and the screen reader directly and so
 * could only be exercised on a rooted phone. It takes a Backend and a Finder
 * now, and these are the fakes.
 *
 * Every step is built with delayBefore 0 and no random factor, so the real
 * delay() and Random never run and a whole script finishes in no time.
 */

/** Records what was asked of the device, in order. */
private class FakeBackend : Backend {
	val calls = mutableListOf<String>()
	var ready = true
	var geometry = ScreenGeometry(1000, 2000, 0)

	override val isReady: Boolean get() = ready
	override val screen: ScreenGeometry get() = geometry

	override suspend fun click(x: Float, y: Float, duration: Long, sample: TouchSample) {
		calls.add("click ${x.toInt()},${y.toInt()} ${duration}ms")
	}

	override suspend fun drag(points: List<DragPoint>) {
		val first = points.first()
		val last = points.last()
		calls.add("drag ${first.x.toInt()},${first.y.toInt()} -> ${last.x.toInt()},${last.y.toInt()}")
	}

	override suspend fun text(text: String) = calls.add("text $text").let {}
	override suspend fun keyEvent(key: String) = calls.add("key $key").let {}
	override suspend fun launchApp(packageName: String) = calls.add("launch $packageName").let {}
	override suspend fun shell(command: String) = calls.add("shell $command").let {}
	override suspend fun toast(message: String) = calls.add("toast $message").let {}
}

/** Answers lookups with whatever the test has planted. */
private class FakeFinder : Finder {
	var area: AreaSearch = AreaSearch.Missing("no saved area")
	var text: TextSearch = TextSearch.Missing("not on screen")
	var field: FieldSearch = FieldSearch.Missing("no text field on screen")
	var codes: CodeServer.Result = CodeServer.Result.Failed("no code arrived in time")

	override suspend fun findArea(name: String) = area
	override suspend fun findText(phrase: String) = text
	override suspend fun findField() = field
	override suspend fun awaitCodes(maxAgeSeconds: Long, timeoutMs: Long) = codes
}

class InterpreterTest {

	private val backend = FakeBackend()
	private val finder = FakeFinder()
	private val context = ScriptContext()

	private fun play(vararg steps: RuntimeStep): Interpreter {
		val interpreter = Interpreter(backend, finder, context)
		runBlocking { interpreter.run(steps.toList()) }
		return interpreter
	}

	private fun tap(x: Float, y: Float, anchor: String = "", anchorText: String = "") =
		ClickStep(x, y, duration = 50, anchor = anchor, anchorText = anchorText, delayBefore = 0)

	// -----------------------------------------------------------------------
	// Where a gesture lands
	// -----------------------------------------------------------------------

	/** Absolute coordinates are fractions of the screen, so the middle is the middle. */
	@Test
	fun anUnanchoredTapIsAFractionOfTheScreen() {
		play(tap(0.5f, 0.5f))

		assertEquals(listOf("click 500,1000 50ms"), backend.calls)
	}

	/**
	 * The unit rule from the step model, which nothing enforced: absolute
	 * coordinates are fractions, anchored ones are pixels from what was found.
	 * A saved area only matches at the resolution it was captured at, so an
	 * offset expressed as a fraction of some other screen would be wrong
	 * precisely when the anchor was right.
	 */
	@Test
	fun anAnchoredTapIsPixelsFromWhereTheAnchorWasFound() {
		finder.area = AreaSearch.Found(TemplateMatcher.Match(300, 400, 0.99f))

		play(tap(90f, 16f, anchor = "continue-button"))

		assertEquals(listOf("click 390,416 50ms"), backend.calls)
	}

	/** Negative offsets place a gesture above or left of the anchor. */
	@Test
	fun anAnchoredTapAcceptsANegativeOffset() {
		finder.text = TextSearch.Found(Rect().apply { left = 500; top = 900 })

		play(tap(-40f, -60f, anchorText = "Continue"))

		assertEquals(listOf("click 460,840 50ms"), backend.calls)
	}

	/** A phrase wins when a step carries both: it is the more specific thing said. */
	@Test
	fun aPhraseTakesPrecedenceOverASavedArea() {
		finder.text = TextSearch.Found(Rect().apply { left = 10; top = 20 })
		finder.area = AreaSearch.Found(TemplateMatcher.Match(900, 900, 0.99f))

		play(tap(0f, 0f, anchor = "somewhere", anchorText = "Continue"))

		assertEquals(listOf("click 10,20 50ms"), backend.calls)
	}

	/**
	 * Falling back to the raw coordinates would put the touch somewhere
	 * arbitrary, which is worse than not touching at all.
	 */
	@Test
	fun aGestureWhoseAnchorIsMissingIsSkippedAndCounted() {
		val run = play(tap(90f, 16f, anchor = "not-on-screen"))

		assertEquals(1, run.degraded)
		// The toast is the report; no touch was injected.
		assertTrue(backend.calls.none { it.startsWith("click") })
	}

	@Test
	fun tapsRepeatThePress() {
		play(ClickStep(0.5f, 0.5f, duration = 50, taps = 3, delayBefore = 0))

		assertEquals(3, backend.calls.size)
		assertTrue(backend.calls.all { it == "click 500,1000 50ms" })
	}

	@Test
	fun aDragIsPlacedEndToEnd() {
		play(
			DragStep(
				points = listOf(DragPoint(0.5f, 0.7f, 0), DragPoint(0.5f, 0.3f, 200)),
				delayBefore = 0
			)
		)

		assertEquals(listOf("drag 500,1400 -> 500,600"), backend.calls)
	}

	// -----------------------------------------------------------------------
	// Reporting what went wrong
	// -----------------------------------------------------------------------

	/**
	 * The count is taken before the toast throttle, so a step failing the same
	 * way once per iteration is reported as the many skips it was rather than
	 * the one message the user saw.
	 */
	@Test
	fun everySkipIsCountedEvenThoughTheMessageIsShownOnce() {
		val run = play(
			ForLoopStep(
				repeatCount = 4,
				steps = listOf(tap(0f, 0f, anchor = "not-on-screen")),
				delayBefore = 0
			)
		)

		assertEquals(4, run.degraded)
		assertEquals(1, backend.calls.count { it.startsWith("toast ERROR") })
	}

	@Test
	fun aCleanRunReportsNothingDegraded() {
		val run = play(tap(0.5f, 0.5f), WaitStep(delayBefore = 0))

		assertEquals(0, run.degraded)
	}

	// -----------------------------------------------------------------------
	// Control flow
	// -----------------------------------------------------------------------

	@Test
	fun breakLeavesTheInnermostLoopAndTheRunCarriesOn() {
		play(
			ForLoopStep(
				repeatCount = 10,
				steps = listOf(ToastStep("inner", delayBefore = 0), BreakStep()),
				delayBefore = 0
			),
			ToastStep("after", delayBefore = 0)
		)

		assertEquals(listOf("toast inner", "toast after"), backend.calls)
	}

	@Test
	fun aRepeatOfZeroRunsUntilBroken() {
		play(
			ForLoopStep(
				repeatCount = 0,
				steps = listOf(
					SetVariableStep("i", "i + 1", delayBefore = 0),
					IfStep(
						branches = listOf(ConditionBranch("i >= 3", listOf(BreakStep()))),
						delayBefore = 0
					)
				),
				delayBefore = 0
			)
		)

		assertEquals(3L, context.variable("i")?.asNum())
	}

	@Test
	fun ifTakesTheFirstBranchThatHolds() {
		context.set("n", Value.Num(2))

		play(
			IfStep(
				branches = listOf(
					ConditionBranch("n == 1", listOf(ToastStep("one", delayBefore = 0))),
					ConditionBranch("n == 2", listOf(ToastStep("two", delayBefore = 0))),
					ConditionBranch("n >= 2", listOf(ToastStep("also two", delayBefore = 0)))
				),
				delayBefore = 0
			)
		)

		assertEquals(listOf("toast two"), backend.calls)
	}

	@Test
	fun theElseBranchRunsWhenNoneHold() {
		play(
			IfStep(
				branches = listOf(ConditionBranch("1 == 2", listOf(ToastStep("no", delayBefore = 0)))),
				elseBranch = listOf(ToastStep("otherwise", delayBefore = 0)),
				delayBefore = 0
			)
		)

		assertEquals(listOf("toast otherwise"), backend.calls)
	}

	/** The condition is re-read each pass, so a Set inside it is what ends it. */
	@Test
	fun whileRetestsItsConditionEveryPass() {
		play(
			WhileStep(
				condition = "i < 3",
				steps = listOf(SetVariableStep("i", "i + 1", delayBefore = 0)),
				delayBefore = 0
			)
		)

		assertEquals(3L, context.variable("i")?.asNum())
	}

	@Test
	fun randomSelectRunsExactlyOneOfItsSteps() {
		play(
			RandomSelectStep(
				steps = listOf(
					ToastStep("a", delayBefore = 0),
					ToastStep("b", delayBefore = 0),
					ToastStep("c", delayBefore = 0)
				),
				delayBefore = 0
			)
		)

		assertEquals(1, backend.calls.size)
	}

	// -----------------------------------------------------------------------
	// Steps that read something first
	// -----------------------------------------------------------------------

	/** How a code that was looked up ever reaches the field. */
	@Test
	fun textIsInterpolatedBeforeItIsTyped() {
		context.set("codes", Value.Arr(listOf(Value.Str("123456"))))

		play(TextStep("{codes[0]}", delayBefore = 0))

		assertEquals(listOf("text 123456"), backend.calls)
	}

	@Test
	fun aToastIsInterpolatedToo() {
		context.set("count", Value.Num(2))

		play(ToastStep("attempt {count} of 5", delayBefore = 0))

		assertEquals(listOf("toast attempt 2 of 5"), backend.calls)
	}

	/** Nothing is touched when the field already has focus. */
	@Test
	fun focusFieldLeavesAFocusedFieldAloneAndStoresItsLength() {
		finder.field = FieldSearch.Found(
			FieldNode(left = 0, top = 0, right = 100, bottom = 50, focused = true, textLength = 7)
		)

		play(FocusFieldStep("chars", delayBefore = 0))

		assertEquals(7L, context.variable("chars")?.asNum())
		assertTrue("a focused field must not be tapped", backend.calls.isEmpty())
	}

	@Test
	fun focusFieldTapsAFieldThatIsNotFocused() {
		finder.field = FieldSearch.Found(
			FieldNode(left = 100, top = 200, right = 300, bottom = 260, focused = false, textLength = 4)
		)

		play(FocusFieldStep("chars", delayBefore = 0))

		assertEquals(4L, context.variable("chars")?.asNum())
		assertEquals(1, backend.calls.count { it.startsWith("click") })
	}

	/**
	 * Zero rather than whatever was there before, so a clearing loop guarded on
	 * it does nothing instead of backspacing through the wrong field.
	 */
	@Test
	fun focusFieldStoresZeroWhenThereIsNoField() {
		context.set("chars", Value.Num(99))

		val run = play(FocusFieldStep("chars", delayBefore = 0))

		assertEquals(0L, context.variable("chars")?.asNum())
		assertEquals(1, run.degraded)
	}

	@Test
	fun waitForCodeStoresWhatArrived() {
		finder.codes = CodeServer.Result.Found(listOf("111111", "222222"))

		play(WaitCodeStep("codes", maxAgeSeconds = 120, timeoutMs = 1, delayBefore = 0))

		assertEquals(2L, runBlocking { context.evaluateOrZero("count(codes)") }.asNum())
	}

	/** An empty list, so a script can branch on count() rather than retype. */
	@Test
	fun waitForCodeStoresAnEmptyListWhenNoneArrive() {
		val run = play(WaitCodeStep("codes", maxAgeSeconds = 120, timeoutMs = 1, delayBefore = 0))

		assertEquals(0L, runBlocking { context.evaluateOrZero("count(codes)") }.asNum())
		assertEquals(1, run.degraded)
	}

	// -----------------------------------------------------------------------
	// Pass-through steps
	// -----------------------------------------------------------------------

	@Test
	fun theRemainingStepsReachTheBackendAsWritten() {
		play(
			KeyEventStep("BACK", delayBefore = 0),
			LaunchAppStep("com.example.app", delayBefore = 0),
			ShellStep("svc wifi disable", delayBefore = 0)
		)

		assertEquals(
			listOf("key BACK", "launch com.example.app", "shell svc wifi disable"),
			backend.calls
		)
	}

	/** A blank package or command would run something meaningless as root. */
	@Test
	fun blankLaunchesAndCommandsAreNotSent() {
		play(LaunchAppStep("", delayBefore = 0), ShellStep("", delayBefore = 0))

		assertTrue(backend.calls.isEmpty())
	}
}
