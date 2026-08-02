package net.jacoblo.autoclicker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "autoclicker.screen.capture"

// screencap writes width, height, format and colorspace as little-endian
// uint32 before the pixel data.
private const val HEADER_BYTES = 16
private const val FORMAT_RGBA_8888 = 1

/**
 * Whole-screen grabs through root `screencap`.
 *
 * The raw form is used rather than `screencap -p`: on a Pixel 2 the PNG encode
 * costs about 2.1s versus 0.2s raw, which is the difference between a usable
 * polling condition and an unusable one. Raw also means a region can be read
 * straight out of the byte array with no decoding.
 */
object ScreenCapture {

	/** RGBA_8888, tightly packed, row-major. */
	class Frame(val width: Int, val height: Int, val pixels: ByteArray) {

		fun pixelAt(x: Int, y: Int): Int {
			val i = (y * width + x) * 4
			val r = pixels[i].toInt() and 0xFF
			val g = pixels[i + 1].toInt() and 0xFF
			val b = pixels[i + 2].toInt() and 0xFF
			val a = pixels[i + 3].toInt() and 0xFF
			return (a shl 24) or (r shl 16) or (g shl 8) or b
		}
	}

	/** Blocks on the root shell; never call from the main thread. */
	fun capture(): Frame? {
		val process = RootShell.spawn("screencap") ?: return null
		return try {
			val stream = process.inputStream

			val header = ByteArray(HEADER_BYTES)
			if (!readFully(stream, header)) {
				Log.w(TAG, "screencap produced no header")
				return null
			}
			val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
			val width = buffer.int
			val height = buffer.int
			val format = buffer.int

			if (width <= 0 || height <= 0 || width > 20000 || height > 20000) {
				Log.w(TAG, "implausible screencap size ${width}x$height")
				return null
			}
			if (format != FORMAT_RGBA_8888) {
				Log.w(TAG, "unexpected screencap format $format, expected RGBA_8888")
				return null
			}

			val pixels = ByteArray(width * height * 4)
			if (!readFully(stream, pixels)) {
				Log.w(TAG, "screencap pixel data truncated")
				return null
			}
			Frame(width, height, pixels)
		} catch (e: Exception) {
			Log.e(TAG, "screencap failed", e)
			null
		} finally {
			process.destroy()
		}
	}

	fun crop(frame: Frame, rect: Rect): Bitmap? {
		val left = rect.left.coerceIn(0, frame.width - 1)
		val top = rect.top.coerceIn(0, frame.height - 1)
		val width = rect.width().coerceAtMost(frame.width - left)
		val height = rect.height().coerceAtMost(frame.height - top)
		if (width <= 0 || height <= 0) return null

		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val row = IntArray(width)
		for (y in 0 until height) {
			for (x in 0 until width) {
				row[x] = frame.pixelAt(left + x, top + y)
			}
			bitmap.setPixels(row, 0, width, 0, y, width, 1)
		}
		return bitmap
	}

	// A pipe hands over whatever is ready, so a single read of 8MB comes up
	// short; keep pulling until the buffer is filled.
	private fun readFully(stream: InputStream, into: ByteArray): Boolean {
		var offset = 0
		while (offset < into.size) {
			val read = stream.read(into, offset, into.size - offset)
			if (read < 0) return false
			offset += read
		}
		return true
	}
}
