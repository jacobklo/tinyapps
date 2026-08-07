package net.jacoblo.autoclicker

import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "autoclicker.backend"

// Gap between typed characters on the root path. Unhurried touch typing sits
// around 150-250ms per character, and the spread matters as much as the mean: a
// fixed interval is as unlike a person as no interval at all.
private const val MIN_KEY_GAP_MS = 90L
private const val MAX_KEY_GAP_MS = 240L

/**
 * Everything a step does to the device.
 *
 * The two implementations are the two ways this app can drive a phone, and they
 * are not equivalent: root reaches the touchscreen device itself and can run
 * commands, while the accessibility service can only dispatch gestures and set
 * text. Actions the accessibility path has no answer for say so and carry on,
 * because a script that silently did half of what it says would be worse.
 *
 * Coordinates arriving here are final pixels for the current display. Placing
 * and jittering them happens once, above, so both backends land in the same
 * place.
 */
interface Backend {

	val isReady: Boolean

	/** Read per gesture, so a rotation part-way through a script is followed. */
	val screen: ScreenGeometry

	suspend fun click(x: Float, y: Float, duration: Long, sample: TouchSample)

	suspend fun drag(points: List<DragPoint>)

	suspend fun text(text: String)

	suspend fun keyEvent(key: String)

	suspend fun launchApp(packageName: String)

	suspend fun shell(command: String)

	suspend fun toast(message: String)
}

/** A toast is the script telling you what it is doing, so both backends show one. */
private suspend fun showToast(message: String) {
	if (message.isEmpty()) return
	withContext(Dispatchers.Main) {
		Toast.makeText(AppSettings.appContext, message, Toast.LENGTH_SHORT).show()
	}
}

private fun currentScreen() = ScreenGeometry.current(AppSettings.appContext)

/**
 * Injects through the touchscreen's own evdev node, falling back to `input
 * swipe` when that node is not writable.
 */
object RootBackend : Backend {

	override val isReady: Boolean
		get() = RootShell.isOpen

	override val screen: ScreenGeometry
		get() = currentScreen()

	override suspend fun click(x: Float, y: Float, duration: Long, sample: TouchSample) {
		if (GestureExecutor.evdevReady) {
			withContext(Dispatchers.IO) { EvdevInjector.playClick(x, y, duration, sample) }
			return
		}
		val px = x.roundToInt()
		val py = y.roundToInt()
		// A zero-length swipe is the only `input` form that honours a press duration.
		withContext(Dispatchers.IO) { RootShell.swipe(px, py, px, py, duration) }
	}

	override suspend fun drag(points: List<DragPoint>) {
		if (GestureExecutor.evdevReady) {
			// Every recorded point is replayed with its own timing, pressure and
			// contact size, instead of collapsing to a linear swipe.
			withContext(Dispatchers.IO) { EvdevInjector.playDrag(points) }
			return
		}
		val first = points.first()
		val last = points.last()
		val duration = points.sumOf { it.dt }
		withContext(Dispatchers.IO) {
			RootShell.swipe(
				first.x.roundToInt(), first.y.roundToInt(),
				last.x.roundToInt(), last.y.roundToInt(),
				duration
			)
		}
	}

	override suspend fun text(text: String) {
		// One character at a time with a varying gap. A whole field arriving in a
		// single frame is not something a person can produce, and the per-character
		// cost is only about 40ms, so the pacing is the delay rather than the
		// command.
		for (character in text) {
			withContext(Dispatchers.IO) { RootShell.text(character.toString()) }
			delay(Random.nextLong(MIN_KEY_GAP_MS, MAX_KEY_GAP_MS + 1))
		}
	}

	override suspend fun keyEvent(key: String) {
		if (!requireShell("key event")) return
		withContext(Dispatchers.IO) { RootShell.exec("input keyevent ${key.trim()}") }
	}

	override suspend fun launchApp(packageName: String) {
		if (!requireShell("launch app")) return
		withContext(Dispatchers.IO) {
			RootShell.exec("monkey -p ${packageName.trim()} -c android.intent.category.LAUNCHER 1")
		}
	}

	override suspend fun shell(command: String) {
		if (!requireShell("shell") ) return
		withContext(Dispatchers.IO) { RootShell.exec(command) }
	}

	override suspend fun toast(message: String) = showToast(message)

	/**
	 * The gesture calls above go through RootShell.swipe and RootShell.text,
	 * which reopen a shell that died with the su daemon. exec does not, so the
	 * commands that use it check first and say why they are skipping rather
	 * than failing quietly.
	 */
	private fun requireShell(what: String): Boolean {
		if (RootShell.isOpen) return true
		Log.w(TAG, "$what needs root, skipping")
		return false
	}
}

/**
 * Dispatches through the accessibility service.
 *
 * Keys, app launches and shell commands are root-only. The service has no
 * equivalent, and offering some of them would make the same script behave
 * differently depending on a setting.
 */
object AccessibilityBackend : Backend {

	override val isReady: Boolean
		get() = RecorderService.instance != null

	override val screen: ScreenGeometry
		get() = currentScreen()

	override suspend fun click(x: Float, y: Float, duration: Long, sample: TouchSample) {
		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping click")
			return
		}
		awaitDispatch { done -> service.dispatchClick(x, y, duration, done) }
	}

	override suspend fun drag(points: List<DragPoint>) {
		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping drag")
			return
		}
		awaitDispatch { done -> service.dispatchDrag(points, done) }
	}

	override suspend fun text(text: String) {
		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping text")
			return
		}
		service.dispatchText(text)
	}

	override suspend fun keyEvent(key: String) = unsupported("key event")

	override suspend fun launchApp(packageName: String) = unsupported("launch app")

	override suspend fun shell(command: String) = unsupported("shell")

	override suspend fun toast(message: String) = showToast(message)

	private fun unsupported(what: String) {
		Log.w(TAG, "$what needs root, skipping")
	}

	/**
	 * Bridges the service's completion callback, and stays cancellable: without
	 * that the stop button could not interrupt a gesture already in flight.
	 */
	private suspend fun awaitDispatch(dispatch: (() -> Unit) -> Unit) =
		suspendCancellableCoroutine { cont ->
			dispatch { if (cont.isActive) cont.resume(Unit) }
		}
}
