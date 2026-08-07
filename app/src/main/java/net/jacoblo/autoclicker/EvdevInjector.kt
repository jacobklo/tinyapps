package net.jacoblo.autoclicker

import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

private const val TAG = "autoclicker.evdev.injector"

// struct input_event on a 64-bit writer: 16-byte timeval + u16 type + u16 code + s32 value.
private const val EVENT_SIZE = 24

/**
 * Replays gestures by writing raw input_event structs to the real touchscreen
 * node, so they enter at evdev and are processed by InputReader exactly like
 * the digitizer's own -- unlike `input swipe`, which enters at InputDispatcher
 * with constant pressure and a linear path.
 *
 * The kernel overwrites the timestamp on input, so the timeval is left zero;
 * pacing comes from when each batch is written, not from what is written.
 */
object EvdevInjector {

	private const val DEFAULT_FRACTION = 0.4f

	private var process: Process? = null
	private var out: OutputStream? = null
	private var device: EvdevDevice? = null

	private val buffer = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

	val isReady: Boolean
		@Synchronized get() = out != null

	@Synchronized
	fun open(target: EvdevDevice): Boolean {
		if (out != null) return true
		val spawned = RootShell.spawn("cat > ${target.path}") ?: return false
		process = spawned
		out = spawned.outputStream
		device = target
		// A bare SYN_REPORT is a no-op with no pending contact state, so it
		// proves the node is writable without producing a phantom touch.
		if (!syncNow()) {
			Log.w(TAG, "cannot write to ${target.path}")
			close()
			return false
		}
		Log.i(TAG, "evdev injector open on ${target.path}")
		return true
	}

	@Synchronized
	fun close() {
		try {
			out?.close()
		} catch (e: Exception) {
			Log.d(TAG, "writer already closed", e)
		}
		process?.destroy()
		process = null
		out = null
		device = null
	}

	@Synchronized
	fun playClick(x: Float, y: Float, durationMs: Long, sample: TouchSample) {
		val target = device ?: return
		val screen = ScreenGeometry.current(AppSettings.appContext)
		val jitter = AppSettings.jitter
		val trackingId = Random.nextInt(1, 0xFFFF)

		if (!touchDown(target, screen, jitter, x, y, sample, trackingId)) return
		sleepJittered(durationMs, jitter)
		touchUp()
	}

	@Synchronized
	fun playDrag(points: List<DragPoint>) {
		if (points.isEmpty()) return
		val target = device ?: return
		val screen = ScreenGeometry.current(AppSettings.appContext)
		val jitter = AppSettings.jitter
		val trackingId = Random.nextInt(1, 0xFFFF)

		val first = points.first()
		if (!touchDown(target, screen, jitter, first.x, first.y, first.toSample(), trackingId)) return

		for (i in 1 until points.size) {
			val point = points[i]
			sleepJittered(point.dt, jitter)
			val (rawX, rawY) = target.screenToRaw(
				point.x + jitterPx(jitter.positionPx),
				point.y + jitterPx(jitter.positionPx),
				screen
			)
			writeEvent(EV_ABS, ABS_MT_POSITION_X, rawX)
			writeEvent(EV_ABS, ABS_MT_POSITION_Y, rawY)
			writeSample(target, jitter, point.toSample())
			if (!syncNow()) return
		}
		touchUp()
	}

	private fun touchDown(
		target: EvdevDevice,
		screen: ScreenGeometry,
		jitter: JitterConfig,
		x: Float,
		y: Float,
		sample: TouchSample,
		trackingId: Int
	): Boolean {
		val (rawX, rawY) = target.screenToRaw(
			x + jitterPx(jitter.positionPx),
			y + jitterPx(jitter.positionPx),
			screen
		)
		writeEvent(EV_ABS, ABS_MT_SLOT, 0)
		writeEvent(EV_ABS, ABS_MT_TRACKING_ID, trackingId)
		writeEvent(EV_ABS, ABS_MT_POSITION_X, rawX)
		writeEvent(EV_ABS, ABS_MT_POSITION_Y, rawY)
		writeSample(target, jitter, sample)
		// Without this the contacts arrive but TouchInputMapper reads them as
		// hovering, so a target highlights under the touch and is never clicked.
		// The kernel drops the code on a device that does not declare it, so it
		// costs nothing where it is not needed.
		writeEvent(EV_KEY, BTN_TOUCH, 1)
		return syncNow()
	}

	private fun touchUp() {
		writeEvent(EV_ABS, ABS_MT_SLOT, 0)
		writeEvent(EV_ABS, ABS_MT_TRACKING_ID, -1)
		writeEvent(EV_KEY, BTN_TOUCH, 0)
		syncNow()
	}

	/** Emits pressure and contact size, skipping axes this device does not report. */
	private fun writeSample(target: EvdevDevice, jitter: JitterConfig, sample: TouchSample) {
		target.rangePressure?.let {
			writeEvent(EV_ABS, ABS_MT_PRESSURE, scatter(sample.pressure, jitter.pressurePct, it))
		}
		target.rangeTouchMajor?.let {
			writeEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, scatter(sample.touchMajor, jitter.sizePct, it))
		}
		target.rangeTouchMinor?.let {
			writeEvent(EV_ABS, ABS_MT_TOUCH_MINOR, scatter(sample.touchMinor, jitter.sizePct, it))
		}
	}

	private fun writeEvent(type: Int, code: Int, value: Int) {
		val stream = out ?: return
		buffer.clear()
		buffer.putLong(0L)
		buffer.putLong(0L)
		buffer.putShort(type.toShort())
		buffer.putShort(code.toShort())
		buffer.putInt(value)
		try {
			stream.write(buffer.array())
		} catch (e: Exception) {
			Log.e(TAG, "evdev write failed", e)
			close()
		}
	}

	private fun syncNow(): Boolean {
		writeEvent(EV_SYN, SYN_REPORT, 0)
		val stream = out ?: return false
		return try {
			stream.flush()
			true
		} catch (e: Exception) {
			Log.e(TAG, "evdev flush failed", e)
			close()
			false
		}
	}

	private fun sleepJittered(millis: Long, jitter: JitterConfig) {
		if (millis <= 0) return
		val spread = millis * jitter.timingPct / 100
		val actual = millis + if (spread > 0) Random.nextLong(-spread, spread + 1) else 0
		if (actual > 0) Thread.sleep(actual)
	}

	private fun jitterPx(px: Int): Float =
		if (px > 0) Random.nextInt(-px, px + 1).toFloat() else 0f

	private fun scatter(value: Int, percent: Int, range: AxisRange): Int {
		// A recording made on the accessibility backend carries no captured
		// pressure or size, so fall back to a plausible mid-scale value.
		val base = if (value > 0) value else (range.min + range.span * DEFAULT_FRACTION).toInt()
		val spread = base * percent / 100
		val jittered = if (spread > 0) base + Random.nextInt(-spread, spread + 1) else base
		return jittered.coerceIn(range.min.coerceAtLeast(1), range.max)
	}
}

/** The non-positional half of a touch sample. */
data class TouchSample(val pressure: Int, val touchMajor: Int, val touchMinor: Int)

internal fun DragPoint.toSample(): TouchSample = TouchSample(pressure, touchMajor, touchMinor)
