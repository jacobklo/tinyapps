package net.jacoblo.autoclicker

import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "autoclicker.gesture.executor"

// Safety net so a runaway script cannot hold the CPU awake indefinitely.
private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

/**
 * How a playback ended.
 *
 * [Completed.degraded] counts the steps that ran but could not do what they were
 * asked -- an anchor that was not on screen, a field that could not be focused, a
 * verification code that never arrived. Those are skipped rather than fatal, so
 * without a count a half-working script reports exactly like a clean one.
 */
sealed class PlaybackResult {
	data class Completed(val degraded: Int) : PlaybackResult()
	data object Stopped : PlaybackResult()
	data class Failed(val reason: String) : PlaybackResult()
}

/**
 * Replays recorded steps through whichever backend the user selected.
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

	/**
	 * Which way touches are injected. Read per call rather than held, so turning
	 * Use Root on or off takes effect without restarting anything.
	 */
	private val backend: Backend
		get() = if (AppSettings.useRoot) RootBackend else AccessibilityBackend

	fun isReady(): Boolean = backend.isReady

	private val finder: Finder = DeviceFinder

	private var playJob: Job? = null
	private var wakeLock: PowerManager.WakeLock? = null

	val isPlaying: Boolean
		get() = playJob?.isActive == true

	/**
	 * Observable form of [isPlaying], so the bubble and the notification can show
	 * a stop control for a script they did not start themselves -- playback is
	 * also driven over the control server.
	 */
	private val _playing = MutableStateFlow(false)
	val playing: StateFlow<Boolean> = _playing.asStateFlow()

	/**
	 * [onFinished] runs on the main thread whether playback completed, was
	 * stopped or threw, so callers can restore a play/stop button without
	 * polling.
	 */
	fun playRecording(
		events: List<RuntimeStep>,
		globalRandom: Int = 0,
		onFinished: ((PlaybackResult) -> Unit)? = null
	) {
		stop()
		// A fresh interpreter per run, so the previous run's variables and its
		// count of skipped steps cannot be mistaken for this one's.
		val interpreter = Interpreter(backend, finder, ScriptContext(), globalRandom)
		_playing.value = true
		playJob = scope.launch {
			acquireWakeLock()
			var result: PlaybackResult = PlaybackResult.Stopped
			try {
				// A run starts from the globals rather than from nothing, so the
				// same script can be driven with different values.
				interpreter.run(events)
				result = PlaybackResult.Completed(interpreter.degraded)
			} catch (stop: BreakSignal) {
				Log.w(TAG, "break outside any loop, ending playback")
				result = PlaybackResult.Completed(interpreter.degraded)
			} catch (cancel: CancellationException) {
				Log.i(TAG, "playback stopped")
				throw cancel
			} catch (e: Exception) {
				Log.e(TAG, "playback failed", e)
				result = PlaybackResult.Failed(e.message ?: e.javaClass.simpleName)
			} finally {
				releaseWakeLock()
				_playing.value = false
				onFinished?.invoke(result)
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
			preview().runClick(x, y, duration, randomFactor)
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
			preview().runDrag(points, randomFactorStart, randomFactorHighest)
			onDone?.invoke()
		}
	}

	/**
	 * For the single gesture the recorder echoes back as it is captured, which
	 * has no script around it and so needs no variables of its own.
	 */
	private fun preview() = Interpreter(backend, finder, ScriptContext())
}
