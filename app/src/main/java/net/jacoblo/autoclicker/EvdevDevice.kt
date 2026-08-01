package net.jacoblo.autoclicker

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface

private const val TAG = "autoclicker.evdev.device"

// Linux input event types and ABS_MT codes, from <linux/input-event-codes.h>.
const val EV_SYN = 0x00
const val EV_ABS = 0x03
const val SYN_REPORT = 0x00
const val ABS_MT_TOUCH_MAJOR = 0x30
const val ABS_MT_TOUCH_MINOR = 0x31
const val ABS_MT_POSITION_X = 0x35
const val ABS_MT_POSITION_Y = 0x36
const val ABS_MT_TRACKING_ID = 0x39
const val ABS_MT_PRESSURE = 0x3a
const val ABS_MT_SLOT = 0x2f

data class AxisRange(val min: Int, val max: Int) {
	val span: Int get() = (max - min).coerceAtLeast(1)
}

/**
 * A touchscreen discovered through `getevent -pl`, plus the mapping between its
 * raw axis values and screen pixels.
 *
 * Capture and replay both go through [rawToScreen] / [screenToRaw], so a
 * recording made and replayed in the same display rotation stays correct even
 * if the rotation transform itself is imperfect -- the two conversions cancel.
 * Recording in one rotation and replaying in another is what would break.
 */
data class EvdevDevice(
	val path: String,
	val rangeX: AxisRange,
	val rangeY: AxisRange,
	val rangePressure: AxisRange?,
	val rangeTouchMajor: AxisRange?,
	val rangeTouchMinor: AxisRange?
) {

	fun rawToScreen(rawX: Int, rawY: Int, screen: ScreenGeometry): Pair<Float, Float> {
		val nx = (rawX - rangeX.min).toFloat() / rangeX.span
		val ny = (rawY - rangeY.min).toFloat() / rangeY.span
		return when (screen.rotation) {
			Surface.ROTATION_90 -> Pair(ny * screen.width, (1f - nx) * screen.height)
			Surface.ROTATION_180 -> Pair((1f - nx) * screen.width, (1f - ny) * screen.height)
			Surface.ROTATION_270 -> Pair((1f - ny) * screen.width, nx * screen.height)
			else -> Pair(nx * screen.width, ny * screen.height)
		}
	}

	fun screenToRaw(x: Float, y: Float, screen: ScreenGeometry): Pair<Int, Int> {
		val sx = (x / screen.width).coerceIn(0f, 1f)
		val sy = (y / screen.height).coerceIn(0f, 1f)
		val (nx, ny) = when (screen.rotation) {
			Surface.ROTATION_90 -> Pair(1f - sy, sx)
			Surface.ROTATION_180 -> Pair(1f - sx, 1f - sy)
			Surface.ROTATION_270 -> Pair(sy, 1f - sx)
			else -> Pair(sx, sy)
		}
		return Pair(
			(rangeX.min + nx * rangeX.span).toInt().coerceIn(rangeX.min, rangeX.max),
			(rangeY.min + ny * rangeY.span).toInt().coerceIn(rangeY.min, rangeY.max)
		)
	}

	companion object {
		private val DEVICE_LINE = Regex("""add device \d+: (\S+)""")
		private val AXIS_LINE = Regex("""(ABS_MT_\w+)\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")

		/**
		 * Picks the direct-touch device that reports MT position axes. Returns
		 * null when the shell is unusable or no touchscreen matches.
		 */
		fun detect(): EvdevDevice? {
			val dump = RootShell.execOutput("getevent -pl")
			if (dump == null) {
				Log.w(TAG, "cannot read getevent -pl, no root shell")
				return null
			}

			var path: String? = null
			var direct = false
			var axes = mutableMapOf<String, AxisRange>()

			fun finish(): EvdevDevice? {
				val p = path ?: return null
				val x = axes["ABS_MT_POSITION_X"] ?: return null
				val y = axes["ABS_MT_POSITION_Y"] ?: return null
				if (!direct) return null
				return EvdevDevice(p, x, y, axes["ABS_MT_PRESSURE"], axes["ABS_MT_TOUCH_MAJOR"], axes["ABS_MT_TOUCH_MINOR"])
			}

			for (line in dump.lineSequence()) {
				val device = DEVICE_LINE.find(line)
				if (device != null) {
					finish()?.let { return it }
					path = device.groupValues[1]
					direct = false
					axes = mutableMapOf()
					continue
				}
				if (line.contains("INPUT_PROP_DIRECT")) direct = true
				AXIS_LINE.find(line)?.let {
					axes[it.groupValues[1]] = AxisRange(it.groupValues[2].toInt(), it.groupValues[3].toInt())
				}
			}
			return finish()
		}
	}
}

data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int) {

	companion object {
		@Suppress("DEPRECATION")
		fun current(context: Context): ScreenGeometry {
			val manager = context.getSystemService(DisplayManager::class.java)
			val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
			val size = Point()
			display.getRealSize(size)
			return ScreenGeometry(size.x, size.y, display.rotation)
		}
	}
}
