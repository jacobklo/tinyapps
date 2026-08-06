package net.jacoblo.autoclicker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

private const val TAG = "autoclicker.screen.text"

// Recognition costs a few hundred milliseconds, so polling faster than this
// only burns battery waiting for the same answer.
private const val POLL_INTERVAL_MS = 400L

/** Where a phrase is on screen, or why it could not be found. */
sealed class TextSearch {
	data class Found(val box: Rect) : TextSearch()

	/** Phrased for showing to the user, so it names the thing to fix. */
	data class Missing(val reason: String) : TextSearch()
}

/**
 * Finds a phrase on screen by reading it, rather than by matching a picture of
 * it.
 *
 * A saved area matches only the pixels it was cropped from: the same words in a
 * different weight, colour or position are a different image, and every screen
 * needs its own crop kept in step with the app. A phrase survives all of that,
 * and says in the script what it is looking for.
 *
 * Recognition is the bundled ML Kit model, which ships inside the APK and runs
 * on the device -- nothing is uploaded and it works with the radios off.
 */
object ScreenText {

	private val recognizer by lazy {
		TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	}

	/** Blocks on capture and recognition; callers run it off the main thread. */
	fun find(phrase: String, matchCase: Boolean = true): TextSearch {
		if (phrase.isBlank()) return TextSearch.Missing("no phrase to look for")

		val started = System.currentTimeMillis()
		val frame = ScreenCapture.capture()
			?: return TextSearch.Missing("cannot read the screen, which needs root")
		val captured = System.currentTimeMillis()

		val recognised = try {
			Tasks.await(recognizer.process(InputImage.fromBitmap(frame.toBitmap(), 0)))
		} catch (e: Exception) {
			Log.e(TAG, "recognition failed", e)
			return TextSearch.Missing("cannot read the text on screen")
		}
		val read = System.currentTimeMillis()

		val box = locate(recognised, phrase.trim(), matchCase)
		Log.d(
			TAG,
			"'$phrase' ${if (box == null) "miss" else "hit at ${box.left},${box.top}"}" +
				" capture=${captured - started}ms read=${read - captured}ms"
		)
		return if (box == null) TextSearch.Missing("\"$phrase\" is not on screen")
		else TextSearch.Found(box)
	}

	/** Polls until the phrase shows up or [timeoutMs] elapses. */
	suspend fun waitFor(phrase: String, timeoutMs: Long, matchCase: Boolean = true): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (true) {
			// withContext keeps the work off the main thread while leaving the
			// wait cancellable by the stop button.
			if (withContext(Dispatchers.IO) { find(phrase, matchCase) } is TextSearch.Found) return true
			if (System.currentTimeMillis() >= deadline) return false
			delay(POLL_INTERVAL_MS)
		}
	}

	/**
	 * The tightest box that covers the phrase.
	 *
	 * Tried against a run of words first, because that is the only level that
	 * bounds the phrase itself rather than whatever else shares its line. A
	 * heading that wrapped is two lines to the recogniser and one phrase to the
	 * person who wrote the script, so the block is tried last with its lines
	 * joined.
	 */
	private fun locate(text: Text, phrase: String, matchCase: Boolean): Rect? {
		for (block in text.textBlocks) {
			for (line in block.lines) {
				wordRun(line, phrase, matchCase)?.let { return it }
			}
		}
		for (block in text.textBlocks) {
			for (line in block.lines) {
				if (line.text.holds(phrase, matchCase)) return line.boundingBox
			}
			val joined = block.lines.joinToString(" ") { it.text }
			if (joined.holds(phrase, matchCase)) return block.boundingBox
		}
		return null
	}

	/** A consecutive run of words in [line] whose text is exactly the phrase. */
	private fun wordRun(line: Text.Line, phrase: String, matchCase: Boolean): Rect? {
		val words = line.elements
		for (start in words.indices) {
			val builder = StringBuilder()
			for (end in start until words.size) {
				if (builder.isNotEmpty()) builder.append(' ')
				builder.append(words[end].text)
				if (builder.length > phrase.length) break
				if (!builder.toString().same(phrase, matchCase)) continue

				val box = Rect(words[start].boundingBox ?: return null)
				for (i in start..end) words[i].boundingBox?.let { box.union(it) }
				return box
			}
		}
		return null
	}

	private fun String.holds(phrase: String, matchCase: Boolean) =
		if (matchCase) contains(phrase) else lowercase().contains(phrase.lowercase())

	private fun String.same(phrase: String, matchCase: Boolean) =
		if (matchCase) this == phrase else equals(phrase, ignoreCase = true)
}

/**
 * Straight reinterpretation of the capture buffer.
 *
 * screencap hands back tightly packed RGBA_8888, which is byte for byte what an
 * ARGB_8888 bitmap holds, so the pixels are copied once rather than converted
 * one at a time -- the per-pixel loop costs over a second on a 1080x2400 frame.
 */
private fun ScreenCapture.Frame.toBitmap(): Bitmap =
	Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
		copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
	}
