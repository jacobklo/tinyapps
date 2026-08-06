package net.jacoblo.autoclicker

import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "autoclicker.gesture.executor"

// Safety net so a runaway script cannot hold the CPU awake indefinitely.
private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

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

// Gap between typed characters. Unhurried touch typing sits around 150-250ms
// per character, and the spread matters as much as the mean: a fixed interval
// is as unlike a person as no interval at all.
private const val MIN_KEY_GAP_MS = 90L
private const val MAX_KEY_GAP_MS = 240L

/**
 * Replays recorded interactions through whichever backend the user selected.
 *
 * Coordinate randomisation lives here so both backends jitter identically and
 * the backends only ever inject final coordinates. The root backend maps a drag
 * onto a single `input swipe`, which interpolates a straight line: recorded
 * intermediate points and their per-point timing survive only on the
 * accessibility backend.
 */
object GestureExecutor {

	private val scope = CoroutineScope(Dispatchers.Main + Job())

	@Volatile
	var evdevDevice: EvdevDevice? = null
		private set

	/** True when root replay can use evdev rather than falling back to `input swipe`. */
	val evdevReady: Boolean
		get() = evdevDevice != null && EvdevInjector.isReady

	/**
	 * Discovers the touchscreen and opens the injector. Blocks on root, so call
	 * it off the main thread. Failing here is not fatal: root replay degrades to
	 * the `input swipe` path.
	 */
	fun prepareRoot(): Boolean {
		val device = EvdevDevice.detect()
		if (device == null) {
			Log.w(TAG, "no touchscreen found, root replay falls back to input swipe")
			evdevDevice = null
			return false
		}
		if (!EvdevInjector.open(device)) {
			Log.w(TAG, "evdev node not writable, root replay falls back to input swipe")
			evdevDevice = null
			return false
		}
		evdevDevice = device
		Log.i(TAG, "evdev backend ready on ${device.path}")
		return true
	}

	fun releaseRoot() {
		EvdevInjector.close()
		evdevDevice = null
	}

	fun isReady(): Boolean =
		if (AppSettings.useRoot) RootShell.isOpen else RecorderService.instance != null

	private var playJob: Job? = null
	private var wakeLock: PowerManager.WakeLock? = null

	val isPlaying: Boolean
		get() = playJob?.isActive == true

	/**
	 * [onFinished] runs on the main thread whether playback completed, was
	 * stopped or threw, so callers can restore a play/stop button without
	 * polling.
	 */
	fun playRecording(
		events: List<Interaction>,
		globalRandom: Int = 0,
		onFinished: (() -> Unit)? = null
	) {
		stop()
		// Pressing play again after a failure must show the error again, even
		// though nothing about it has changed.
		lastError = null
		playJob = scope.launch {
			acquireWakeLock()
			try {
				// Variables start empty each run, so a script cannot inherit
				// state from the previous playback.
				executeEvents(events, globalRandom, ScriptContext())
			} catch (stop: BreakSignal) {
				Log.w(TAG, "break outside any loop, ending playback")
			} finally {
				releaseWakeLock()
				onFinished?.invoke()
			}
		}
	}

	fun stop() {
		playJob?.cancel()
		playJob = null
	}

	// Playback is mostly delay(), so without this the CPU can sleep mid-script
	// and the remaining steps fire late or not at all.
	private fun acquireWakeLock() {
		if (wakeLock != null) return
		val power = AppSettings.appContext.getSystemService(PowerManager::class.java) ?: return
		wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoclicker:playback").apply {
			setReferenceCounted(false)
			acquire(WAKE_LOCK_TIMEOUT_MS)
		}
	}

	private fun releaseWakeLock() {
		wakeLock?.let { if (it.isHeld) it.release() }
		wakeLock = null
	}

	fun click(x: Float, y: Float, duration: Long, randomFactor: Int, onDone: (() -> Unit)? = null) {
		scope.launch {
			runClick(x, y, duration, randomFactor)
			onDone?.invoke()
		}
	}

	fun drag(
		points: List<DragPoint>,
		randomFactorStart: Int,
		randomFactorHighest: Int,
		onDone: (() -> Unit)? = null
	) {
		scope.launch {
			runDrag(points, randomFactorStart, randomFactorHighest)
			onDone?.invoke()
		}
	}

	private suspend fun executeEvents(
		events: List<Interaction>,
		globalRandom: Int,
		context: ScriptContext
	) {
		events.forEach { event ->
			// A loop whose body has no delay would otherwise never yield, and
			// the stop button could not interrupt it.
			currentCoroutineContext().ensureActive()

			val randDelay = if (globalRandom > 0) Random.nextInt(0, globalRandom + 1) else 0
			delay(event.delayBefore + randDelay)

			when (event) {
				is ClickInteraction -> runClick(
					event.x, event.y, event.duration, event.randomFactor,
					TouchSample(event.pressure, event.touchMajor, event.touchMinor),
					event.anchor, event.anchorText, event.taps
				)
				is DragInteraction -> runDrag(
					event.points, event.randomFactorStart, event.randomFactorHighest,
					event.anchor, event.anchorText
				)
				// Braces are worked out the same way a Toast does, which is the
				// only way a script can type something it looked up rather than
				// something that was written into it.
				is TextInteraction -> runText(context.interpolate(event.text))
				is KeyEventInteraction -> runKeyEvent(event.key)
				is LaunchAppInteraction -> runLaunchApp(event.packageName)
				is ShellInteraction -> runShell(event.command)
				is WaitInteraction -> {
					// The delay above is the whole action.
				}
				is ToastInteraction -> runToast(context.interpolate(event.message))
				is SetVariableInteraction ->
					context.set(event.variable, context.evaluateOrZero(event.expression))

				is FocusFieldInteraction -> runFocusField(event, context)

				is WaitCodeInteraction -> runWaitCode(event, context)

				is BreakInteraction -> throw BreakSignal()

				is ForLoopInteraction -> runLoop(event, globalRandom, context)

				is WhileInteraction -> {
					try {
						while (context.condition(event.condition)) {
							currentCoroutineContext().ensureActive()
							executeEvents(event.interactions, globalRandom, context)
						}
					} catch (stop: BreakSignal) {
						Log.d(TAG, "break out of while")
					}
				}

				is IfInteraction -> {
					val taken = event.branches.firstOrNull { context.condition(it.condition) }
					if (taken != null) {
						executeEvents(taken.interactions, globalRandom, context)
					} else {
						executeEvents(event.elseBranch, globalRandom, context)
					}
				}

				is RandomSelectInteraction -> {
					if (event.interactions.isNotEmpty()) {
						executeEvents(listOf(event.interactions.random()), globalRandom, context)
					}
				}
				else -> {
					// Editor-only markers never reach playback
				}
			}
		}
	}

	private suspend fun runLoop(
		event: ForLoopInteraction,
		globalRandom: Int,
		context: ScriptContext
	) {
		try {
			if (event.repeatCount <= 0) {
				// Repeat forever, until Break or the stop button.
				while (true) {
					currentCoroutineContext().ensureActive()
					executeEvents(event.interactions, globalRandom, context)
				}
			}
			repeat(event.repeatCount) {
				executeEvents(event.interactions, globalRandom, context)
			}
		} catch (stop: BreakSignal) {
			Log.d(TAG, "break out of repeat")
		}
	}

	/**
	 * Pixel origin that a gesture's coordinates are measured from.
	 *
	 * Null means the gesture cannot be placed: an anchor image was named but is
	 * not on screen. Falling back to the raw coordinates would put the touch
	 * somewhere arbitrary, which is worse than not touching at all -- but a
	 * gesture that silently does nothing looks the same as a broken script, so
	 * the reason is shown as well as logged.
	 */
	private suspend fun anchorOrigin(anchor: AnchorImage, anchorText: String): Pair<Float, Float>? {
		// A phrase wins when both are set: it is the more specific thing to have
		// said, and a script carrying both was written around the words.
		if (anchorText.isNotBlank()) {
			return when (val result = withContext(Dispatchers.IO) { ScreenText.find(anchorText) }) {
				is TextSearch.Found -> result.box.left.toFloat() to result.box.top.toFloat()
				is TextSearch.Missing -> {
					Log.w(TAG, "anchor text '$anchorText' unusable: ${result.reason}, skipping gesture")
					reportError(result.reason)
					null
				}
			}
		}
		if (anchor.isBlank()) return 0f to 0f
		return when (val result = withContext(Dispatchers.IO) { ScreenConditions.search(anchor) }) {
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
	 * would otherwise leave minutes of identical messages playing out long
	 * after the script stopped.
	 */
	private var lastError: String? = null
	private var lastErrorAt = 0L

	private suspend fun reportError(reason: String) {
		val now = System.currentTimeMillis()
		if (reason == lastError && now - lastErrorAt < ERROR_REPEAT_MS) return
		lastError = reason
		lastErrorAt = now
		runToast("ERROR: $reason")
	}

	// Fractions of the screen when absolute, pixels from the anchor when not.
	private fun place(value: Float, screenSize: Int, origin: Float, anchored: Boolean): Float =
		if (anchored) origin + value else value * screenSize

	private suspend fun runClick(
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
		val screen = ScreenGeometry.current(AppSettings.appContext)
		val finalX = place(x, screen.width, originX, anchored) + jitter(randomFactor)
		val finalY = place(y, screen.height, originY, anchored) + jitter(randomFactor)

		if (AppSettings.useRoot) {
			if (evdevReady) {
				withContext(Dispatchers.IO) { EvdevInjector.playClick(finalX, finalY, duration, sample) }
				return
			}
			val px = finalX.roundToInt()
			val py = finalY.roundToInt()
			// A zero-length swipe is the only `input` form that honours a press duration.
			withContext(Dispatchers.IO) { RootShell.swipe(px, py, px, py, duration) }
			return
		}

		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping click")
			return
		}
		awaitDispatch { done -> service.dispatchClick(finalX, finalY, duration, done) }
	}

	private suspend fun runDrag(
		points: List<DragPoint>,
		randomFactorStart: Int,
		randomFactorHighest: Int,
		anchor: AnchorImage = "",
		anchorText: String = ""
	) {
		if (points.isEmpty()) return
		val (originX, originY) = anchorOrigin(anchor, anchorText) ?: return
		val anchored = anchor.isNotBlank() || anchorText.isNotBlank()
		val screen = ScreenGeometry.current(AppSettings.appContext)
		val pixels = points.map {
			it.copy(
				x = place(it.x, screen.width, originX, anchored),
				y = place(it.y, screen.height, originY, anchored)
			)
		}
		val randomized = randomizePath(pixels, randomFactorStart, randomFactorHighest)

		if (AppSettings.useRoot) {
			if (evdevReady) {
				// Every recorded point is replayed with its own timing, pressure
				// and contact size, instead of collapsing to a linear swipe.
				withContext(Dispatchers.IO) { EvdevInjector.playDrag(randomized) }
				return
			}
			val first = randomized.first()
			val last = randomized.last()
			val duration = randomized.sumOf { it.dt }
			withContext(Dispatchers.IO) {
				RootShell.swipe(
					first.x.roundToInt(), first.y.roundToInt(),
					last.x.roundToInt(), last.y.roundToInt(),
					duration
				)
			}
			return
		}

		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping drag")
			return
		}
		awaitDispatch { done -> service.dispatchDrag(randomized, done) }
	}

	private suspend fun runText(text: String) {
		if (text.isEmpty()) return

		if (AppSettings.useRoot) {
			// One character at a time with a varying gap. A whole field arriving
			// in a single frame is not something a person can produce, and the
			// per-character cost is only about 40ms, so the pacing is the delay
			// rather than the command.
			for (character in text) {
				withContext(Dispatchers.IO) { RootShell.text(character.toString()) }
				delay(Random.nextLong(MIN_KEY_GAP_MS, MAX_KEY_GAP_MS + 1))
			}
			return
		}

		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping text")
			return
		}
		service.dispatchText(text)
	}

	/**
	 * Leaves [FocusFieldInteraction.variable] holding the field's current length
	 * so the script can clear it exactly, and 0 when there is no field, so a
	 * clearing loop guarded on it does nothing rather than backspacing through
	 * whatever is focused instead.
	 */
	private suspend fun runFocusField(event: FocusFieldInteraction, context: ScriptContext) {
		val variable = event.variable.ifBlank { "field" }
		when (val result = withContext(Dispatchers.IO) { ViewHierarchy.findField() }) {
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
	private suspend fun runWaitCode(event: WaitCodeInteraction, context: ScriptContext) {
		val variable = event.variable.ifBlank { "codes" }
		when (val result = withContext(Dispatchers.IO) {
			CodeServer.waitForCodes(event.maxAgeSeconds, event.timeoutMs)
		}) {
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

	// Deliberately not root-gated: a toast is the script telling you what it is
	// doing, which is most wanted when the rest is not working.
	private suspend fun runToast(message: String) {
		if (message.isEmpty()) return
		withContext(Dispatchers.Main) {
			Toast.makeText(AppSettings.appContext, message, Toast.LENGTH_SHORT).show()
		}
	}

	// The actions below are root-only. The accessibility backend has no
	// equivalent for launching an app or running a command, and offering half
	// of them would make a script silently behave differently per backend.
	private suspend fun runKeyEvent(key: String) {
		if (!requireRoot("key event")) return
		withContext(Dispatchers.IO) { RootShell.exec("input keyevent ${key.trim()}") }
	}

	private suspend fun runLaunchApp(packageName: String) {
		if (packageName.isBlank() || !requireRoot("launch app")) return
		withContext(Dispatchers.IO) {
			RootShell.exec("monkey -p ${packageName.trim()} -c android.intent.category.LAUNCHER 1")
		}
	}

	private suspend fun runShell(command: String) {
		if (command.isBlank() || !requireRoot("shell")) return
		withContext(Dispatchers.IO) { RootShell.exec(command) }
	}

	private fun requireRoot(what: String): Boolean {
		if (AppSettings.useRoot && RootShell.isOpen) return true
		Log.w(TAG, "$what needs root, skipping")
		return false
	}

	private suspend fun awaitDispatch(dispatch: (() -> Unit) -> Unit) =
		suspendCancellableCoroutine { cont ->
			dispatch { if (cont.isActive) cont.resume(Unit) }
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
