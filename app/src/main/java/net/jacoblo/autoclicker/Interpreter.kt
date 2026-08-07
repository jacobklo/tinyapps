package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.random.Random

private const val TAG = "autoclicker.interpreter"

// Gap between the taps of a multi-tap. Android treats presses more than about
// 300ms apart as separate taps, so this has to stay well under that to read as
// a double tap rather than two single ones.
private const val TAP_GAP_MS = 60L

// Long enough that a repeating failure shows once rather than once per attempt,
// short enough that a second, separate failure is not swallowed.
private const val ERROR_REPEAT_MS = 5000L

// Landing on a field the window itself located needs no help from the caller,
// so the press is described here rather than in every recording.
private const val FIELD_TAP_MS = 120L
private const val FIELD_TAP_JITTER_PX = 6

/**
 * Runs a list of steps, once.
 *
 * One instance per playback. [backend] and [finder] are what it does and what
 * it can ask, [context] holds the run's variables, and [globalRandom] is the
 * extra wait added to every step so a run is not identically timed. All four
 * are fixed for the run, which is why they are held here rather than threaded
 * through every recursive call.
 *
 * Coordinate placement lives here rather than in a backend, so both backends
 * receive final pixels and a script lands in the same place whichever is in
 * use. A step that cannot do what it was asked -- an anchor that is not on
 * screen, a field that cannot be focused, a code that never arrives -- is
 * skipped and counted in [degraded] rather than ending the run.
 */
class Interpreter(
	private val backend: Backend,
	private val finder: Finder,
	private val context: ScriptContext,
	private val globalRandom: Int = 0
) {

	/**
	 * Steps that ran but could not do what they were asked. Without a count, a
	 * half-working script reports exactly like a clean one.
	 */
	var degraded = 0
		private set

	private var lastError: String? = null
	private var lastErrorAt = 0L

	suspend fun run(steps: List<RuntimeStep>) {
		steps.forEach { step ->
			// A loop whose body has no delay would otherwise never yield, and the
			// stop button could not interrupt it.
			currentCoroutineContext().ensureActive()

			val randDelay = if (globalRandom > 0) Random.nextInt(0, globalRandom + 1) else 0
			delay(step.delayBefore + randDelay)

			when (step) {
				is ClickStep -> runClick(
					step.x, step.y, step.duration, step.randomFactor,
					TouchSample(step.pressure, step.touchMajor, step.touchMinor),
					step.anchor, step.anchorText, step.taps
				)
				is DragStep -> runDrag(
					step.points, step.randomFactorStart, step.randomFactorHighest,
					step.anchor, step.anchorText
				)
				// Braces are worked out the same way a Toast does, which is the
				// only way a script can type something it looked up rather than
				// something that was written into it.
				is TextStep -> runText(context.interpolate(step.text))
				is KeyEventStep -> backend.keyEvent(step.key)
				is LaunchAppStep -> runLaunchApp(step.packageName)
				is ShellStep -> runShell(step.command)
				is WaitStep -> {
					// The delay above is the whole action.
				}
				is ToastStep -> backend.toast(context.interpolate(step.message))
				is SetVariableStep ->
					context.set(step.variable, context.evaluateOrZero(step.expression))

				is FocusFieldStep -> runFocusField(step)

				is WaitCodeStep -> runWaitCode(step)

				is BreakStep -> throw BreakSignal()

				is ForLoopStep -> runLoop(step)

				is WhileStep -> {
					try {
						while (context.condition(step.condition)) {
							currentCoroutineContext().ensureActive()
							run(step.steps)
						}
					} catch (stop: BreakSignal) {
						Log.d(TAG, "break out of while")
					}
				}

				is IfStep -> {
					val taken = step.branches.firstOrNull { context.condition(it.condition) }
					if (taken != null) run(taken.steps) else run(step.elseBranch)
				}

				is RandomSelectStep -> {
					if (step.steps.isNotEmpty()) run(listOf(step.steps.random()))
				}
			}
		}
	}

	private suspend fun runLoop(step: ForLoopStep) {
		try {
			if (step.repeatCount <= 0) {
				// Repeat forever, until Break or the stop button.
				while (true) {
					currentCoroutineContext().ensureActive()
					run(step.steps)
				}
			}
			repeat(step.repeatCount) {
				run(step.steps)
			}
		} catch (stop: BreakSignal) {
			Log.d(TAG, "break out of repeat")
		}
	}

	/**
	 * Pixel origin that a gesture's coordinates are measured from.
	 *
	 * Null means the gesture cannot be placed: an anchor was named but is not on
	 * screen. Falling back to the raw coordinates would put the touch somewhere
	 * arbitrary, which is worse than not touching at all -- but a gesture that
	 * silently does nothing looks the same as a broken script, so the reason is
	 * shown as well as logged.
	 */
	private suspend fun anchorOrigin(anchor: AnchorImage, anchorText: String): Pair<Float, Float>? {
		// A phrase wins when both are set: it is the more specific thing to have
		// said, and a script carrying both was written around the words.
		if (anchorText.isNotBlank()) {
			return when (val result = finder.findText(anchorText)) {
				is TextSearch.Found -> result.box.left.toFloat() to result.box.top.toFloat()
				is TextSearch.Missing -> {
					Log.w(TAG, "anchor text '$anchorText' unusable: ${result.reason}, skipping gesture")
					reportError(result.reason)
					null
				}
			}
		}
		if (anchor.isBlank()) return 0f to 0f
		return when (val result = finder.findArea(anchor)) {
			is AreaSearch.Found -> result.match.x.toFloat() to result.match.y.toFloat()
			is AreaSearch.Missing -> {
				Log.w(TAG, "anchor '$anchor' unusable: ${result.reason}, skipping gesture")
				reportError(result.reason)
				null
			}
		}
	}

	/**
	 * Shows an error once, then holds the same one back for a while.
	 *
	 * Toasts queue rather than replace, so an anchored gesture inside a loop
	 * would otherwise leave minutes of identical messages playing out long after
	 * the script stopped.
	 */
	private suspend fun reportError(reason: String) {
		// Counted before the throttle below, so a step failing the same way once
		// per loop iteration is reported as the many skips it actually was.
		degraded++
		val now = System.currentTimeMillis()
		if (reason == lastError && now - lastErrorAt < ERROR_REPEAT_MS) return
		lastError = reason
		lastErrorAt = now
		backend.toast("ERROR: $reason")
	}

	// Fractions of the screen when absolute, pixels from the anchor when not.
	private fun place(value: Float, screenSize: Int, origin: Float, anchored: Boolean): Float =
		if (anchored) origin + value else value * screenSize

	suspend fun runClick(
		x: Float,
		y: Float,
		duration: Long,
		randomFactor: Int,
		sample: TouchSample = TouchSample(0, 0, 0),
		anchor: AnchorImage = "",
		anchorText: String = "",
		taps: Int = 1
	) {
		val (originX, originY) = anchorOrigin(anchor, anchorText) ?: return
		val anchored = anchor.isNotBlank() || anchorText.isNotBlank()
		repeat(taps.coerceAtLeast(1)) { index ->
			if (index > 0) delay(TAP_GAP_MS)
			// Re-jittered per tap, so a double tap does not land twice on the
			// exact same pixel.
			tap(x, y, duration, randomFactor, sample, originX, originY, anchored)
		}
	}

	private suspend fun tap(
		x: Float,
		y: Float,
		duration: Long,
		randomFactor: Int,
		sample: TouchSample,
		originX: Float,
		originY: Float,
		anchored: Boolean
	) {
		// Everything downstream of here works in pixels for the current display.
		val screen = backend.screen
		val finalX = place(x, screen.width, originX, anchored) + jitter(randomFactor)
		val finalY = place(y, screen.height, originY, anchored) + jitter(randomFactor)
		backend.click(finalX, finalY, duration, sample)
	}

	suspend fun runDrag(
		points: List<DragPoint>,
		randomFactorStart: Int,
		randomFactorHighest: Int,
		anchor: AnchorImage = "",
		anchorText: String = ""
	) {
		if (points.isEmpty()) return
		val (originX, originY) = anchorOrigin(anchor, anchorText) ?: return
		val anchored = anchor.isNotBlank() || anchorText.isNotBlank()
		val screen = backend.screen
		val pixels = points.map {
			it.copy(
				x = place(it.x, screen.width, originX, anchored),
				y = place(it.y, screen.height, originY, anchored)
			)
		}
		backend.drag(randomizePath(pixels, randomFactorStart, randomFactorHighest))
	}

	private suspend fun runText(text: String) {
		if (text.isEmpty()) return
		backend.text(text)
	}

	private suspend fun runLaunchApp(packageName: String) {
		if (packageName.isBlank()) return
		backend.launchApp(packageName)
	}

	private suspend fun runShell(command: String) {
		if (command.isBlank()) return
		backend.shell(command)
	}

	/**
	 * Leaves [FocusFieldStep.variable] holding the field's current length so the
	 * script can clear it exactly, and 0 when there is no field, so a clearing
	 * loop guarded on it does nothing rather than backspacing through whatever
	 * is focused instead.
	 */
	private suspend fun runFocusField(step: FocusFieldStep) {
		val variable = step.variable.ifBlank { "field" }
		when (val result = finder.findField()) {
			is FieldSearch.Found -> {
				val field = result.field
				context.set(variable, Value.Num(field.textLength.toLong()))
				if (field.focused) {
					// Already where the text will land. A tap here would be a
					// synthetic touch that changes nothing.
					Log.d(TAG, "field already focused, holding ${field.textLength} char(s)")
					return
				}
				Log.d(TAG, "focusing field at ${field.centreX},${field.centreY}")
				tap(
					field.centreX, field.centreY, FIELD_TAP_MS, FIELD_TAP_JITTER_PX,
					TouchSample(45, 130, 120), 0f, 0f, anchored = true
				)
			}
			is FieldSearch.Missing -> {
				context.set(variable, Value.Num(0))
				Log.w(TAG, "no field to focus: ${result.reason}")
				reportError(result.reason)
			}
		}
	}

	/**
	 * Leaves the variable holding an empty list when no code arrives, so a
	 * script can branch on count() instead of typing whatever was there before.
	 */
	private suspend fun runWaitCode(step: WaitCodeStep) {
		val variable = step.variable.ifBlank { "codes" }
		when (val result = finder.awaitCodes(step.maxAgeSeconds, step.timeoutMs)) {
			is CodeServer.Result.Found -> {
				context.set(variable, Value.Arr(result.codes.map { Value.Str(it) }))
				Log.d(TAG, "stored ${result.codes.size} code(s) in '$variable'")
			}
			is CodeServer.Result.Failed -> {
				context.set(variable, Value.Arr(emptyList()))
				Log.w(TAG, "no codes: ${result.reason}")
				reportError(result.reason)
			}
		}
	}

	private fun jitter(factor: Int): Int =
		if (factor > 0) Random.nextInt(-factor, factor + 1) else 0

	private fun randomizePath(
		points: List<DragPoint>,
		randomFactorStart: Int,
		randomFactorHighest: Int
	): List<DragPoint> {
		// copy() so captured pressure and contact size survive the offsetting.
		if (points.size <= 1) {
			return points.map {
				it.copy(x = it.x + jitter(randomFactorStart), y = it.y + jitter(randomFactorStart))
			}
		}

		val mid = (points.size - 1) / 2.0
		return points.mapIndexed { index, point ->
			// Jitter ramps from randomFactorStart at both ends to randomFactorHighest at the middle.
			val t = (1.0 - abs(index - mid) / mid).coerceIn(0.0, 1.0)
			val factor = (randomFactorStart + (randomFactorHighest - randomFactorStart) * t).toInt()
			point.copy(x = point.x + jitter(factor), y = point.y + jitter(factor))
		}
	}
}
