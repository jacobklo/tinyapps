package net.jacoblo.autoclicker

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "autoclicker.evdev.recorder"

// Anything shorter than this is a click rather than a drag, matching the
// threshold the overlay recorder has always used.
private const val CLICK_DISTANCE_PX = 20f

/**
 * Captures real touches straight off the touchscreen node.
 *
 * Nothing is overlaid on the target app and nothing is consumed: the finger's
 * own events reach the app untouched while a passive `getevent` reader mirrors
 * them. That removes both the latency and the detectability of the overlay
 * recorder, which had to swallow each touch and re-inject it.
 */
object EvdevRecorder {

	private val LINE = Regex("""\[\s*([0-9]+\.[0-9]+)]\s+([0-9a-f]{4})\s+([0-9a-f]{4})\s+([0-9a-f]{8})""")

	private var process: Process? = null
	private var reader: Thread? = null
	@Volatile private var running = false

	val isRecording: Boolean get() = running

	/**
	 * [onGesture] is called on the reader thread for every completed touch.
	 * [shouldIgnore] filters out gestures the caller does not want recorded --
	 * used to drop taps that land on the app's own floating bubble, which would
	 * otherwise capture the press of the stop button.
	 */
	@Synchronized
	fun start(
		device: EvdevDevice,
		shouldIgnore: (Float, Float) -> Boolean,
		onGesture: (Interaction) -> Unit
	): Boolean {
		if (running) return true
		val spawned = RootShell.spawn("getevent -t ${device.path}")
		if (spawned == null) {
			Log.w(TAG, "cannot spawn getevent on ${device.path}")
			return false
		}
		process = spawned
		running = true
		reader = Thread { readLoop(spawned, device, shouldIgnore, onGesture) }.apply {
			isDaemon = true
			start()
		}
		Log.i(TAG, "recording from ${device.path}")
		return true
	}

	@Synchronized
	fun stop() {
		running = false
		process?.destroy()
		process = null
		reader = null
		Log.i(TAG, "recording stopped")
	}

	private fun readLoop(
		source: Process,
		device: EvdevDevice,
		shouldIgnore: (Float, Float) -> Boolean,
		onGesture: (Interaction) -> Unit
	) {
		val screen = ScreenGeometry.current(AppSettings.appContext)
		val input = BufferedReader(InputStreamReader(source.inputStream))
		val gesture = GestureBuilder(device, screen)
		var lastGestureEndMs = 0L

		try {
			while (running) {
				val line = input.readLine() ?: break
				val match = LINE.find(line) ?: continue
				val timeMs = (match.groupValues[1].toDouble() * 1000).toLong()
				val type = match.groupValues[2].toInt(16)
				val code = match.groupValues[3].toInt(16)
				// Values are two's complement, so -1 arrives as ffffffff.
				val value = match.groupValues[4].toLong(16).toInt()

				val finished = gesture.accept(type, code, value, timeMs) ?: continue
				if (shouldIgnore(finished.startX, finished.startY)) {
					Log.d(TAG, "ignoring gesture at (${finished.startX}, ${finished.startY})")
					continue
				}
				val delayBefore = if (lastGestureEndMs == 0L) 0L else finished.startMs - lastGestureEndMs
				lastGestureEndMs = finished.endMs
				onGesture(finished.toInteraction(delayBefore.coerceAtLeast(0), screen))
			}
		} catch (e: Exception) {
			if (running) Log.e(TAG, "reader loop failed", e)
		}
	}
}

/**
 * Points are held in screen pixels while capturing, because the bubble-bounds
 * filter compares against window coordinates. They are converted to fractions
 * of the screen only when the interaction is handed over.
 */
private class CapturedGesture(
	val points: List<DragPoint>,
	val startMs: Long,
	val endMs: Long
) {
	val startX: Float get() = points.first().x
	val startY: Float get() = points.first().y

	fun toInteraction(delayBefore: Long, screen: ScreenGeometry): Interaction {
		val last = points.last()
		val distance = sqrt((last.x - startX).pow(2) + (last.y - startY).pow(2))
		if (distance < CLICK_DISTANCE_PX) {
			val first = points.first()
			return ClickInteraction(
				x = startX / screen.width,
				y = startY / screen.height,
				duration = (endMs - startMs).coerceAtLeast(1),
				randomFactor = 0,
				pressure = first.pressure,
				touchMajor = first.touchMajor,
				touchMinor = first.touchMinor,
				delayBefore = delayBefore
			)
		}
		val scaled = points.map {
			it.copy(x = it.x / screen.width, y = it.y / screen.height)
		}
		return DragInteraction(points = scaled, delayBefore = delayBefore)
	}
}

/**
 * Protocol B state machine. Only the first active slot is followed, matching
 * the single-touch model the recording format uses.
 */
private class GestureBuilder(private val device: EvdevDevice, private val screen: ScreenGeometry) {

	private var slot = 0
	private var activeSlot = -1
	private var rawX = 0
	private var rawY = 0
	private var pressure = 0
	private var touchMajor = 0
	private var touchMinor = 0

	private var points = mutableListOf<DragPoint>()
	private var startMs = 0L
	private var lastSampleMs = 0L
	private var pendingRelease = false

	/** Returns a gesture once the tracked contact lifts, otherwise null. */
	fun accept(type: Int, code: Int, value: Int, timeMs: Long): CapturedGesture? {
		if (type == EV_ABS) {
			when (code) {
				ABS_MT_SLOT -> slot = value
				ABS_MT_TRACKING_ID -> {
					if (value == -1) {
						if (slot == activeSlot) pendingRelease = true
					} else if (activeSlot == -1) {
						activeSlot = slot
						points = mutableListOf()
						startMs = timeMs
						lastSampleMs = timeMs
					}
				}
				ABS_MT_POSITION_X -> if (slot == activeSlot) rawX = value
				ABS_MT_POSITION_Y -> if (slot == activeSlot) rawY = value
				ABS_MT_PRESSURE -> if (slot == activeSlot) pressure = value
				ABS_MT_TOUCH_MAJOR -> if (slot == activeSlot) touchMajor = value
				ABS_MT_TOUCH_MINOR -> if (slot == activeSlot) touchMinor = value
			}
			return null
		}

		if (type != EV_SYN || code != SYN_REPORT) return null
		if (activeSlot == -1) return null

		if (pendingRelease) {
			val captured = if (points.isEmpty()) null else CapturedGesture(points.toList(), startMs, timeMs)
			activeSlot = -1
			pendingRelease = false
			points = mutableListOf()
			return captured
		}

		val (x, y) = device.rawToScreen(rawX, rawY, screen)
		points.add(DragPoint(x, y, timeMs - lastSampleMs, pressure, touchMajor, touchMinor))
		lastSampleMs = timeMs
		return null
	}
}
