package net.jacoblo.autoclicker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import java.text.Normalizer

private const val TAG = "autoclicker.screen.text"

// Recognition costs a few hundred milliseconds, so polling faster than this
// only burns battery waiting for the same answer.
private const val POLL_INTERVAL_MS = 400L

/** One offering of the frame to the recogniser. */
private class Pass(val scale: Float, val liftContrast: Boolean)

/**
 * How the frame is offered, in order, until the phrase is found.
 *
 * No single offering covers a screen. The recogniser has a working range rather
 * than just a floor, so a 34sp heading is misread where the body text beside it
 * is not, and the same heading shrunk is misread differently again -- three
 * sizes give three chances at a phrase that only has to be found once. The last
 * pass is for the other failure: a label drawn in grey on grey, which is legible
 * enough to a person and too flat to detect, and which no amount of resizing
 * helps. Full size and untouched comes first because that is where most phrases
 * are found and every further pass costs another recognition.
 */
private val PASSES = listOf(
	Pass(1f, liftContrast = false),
	Pass(0.6f, liftContrast = false),
	Pass(0.4f, liftContrast = false),
	Pass(1f, liftContrast = true)
)

// Enough to pull a dimmed pill's grey-on-grey apart without flattening the
// antialiasing that gives ordinary text its shape.
private const val CONTRAST = 3f

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
		val full = frame.toBitmap()
		val captured = System.currentTimeMillis()
		val wanted = phrase.trim()

		// One capture, offered several ways. Re-capturing per pass would cost as
		// much again and could catch a screen mid-change, which would make a
		// phrase that is on screen throughout look like it came and went.
		try {
			return read(full, phrase, wanted, matchCase, started, captured)
		} finally {
			// A whole screen is ten megabytes and a wait polls twice a second, so
			// leaving these to the collector builds a backlog it cannot keep up with.
			full.recycle()
		}
	}

	private fun read(
		full: Bitmap,
		phrase: String,
		wanted: String,
		matchCase: Boolean,
		started: Long,
		captured: Long
	): TextSearch {
		PASSES.forEach { pass ->
			val bitmap = full.prepared(pass) ?: return@forEach
			val box = try {
				locate(
					Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))),
					wanted,
					matchCase
				)
			} catch (e: Exception) {
				Log.e(TAG, "recognition failed at ${pass.scale}x", e)
				null
			} finally {
				if (bitmap !== full) bitmap.recycle()
			}
			if (box != null) {
				val at = box.rescaled(pass.scale)
				Log.d(
					TAG,
					"'$phrase' hit at ${at.left},${at.top} at ${pass.scale}x" +
						" contrast=${pass.liftContrast}" +
						" capture=${captured - started}ms total=${System.currentTimeMillis() - started}ms"
				)
				return TextSearch.Found(at)
			}
		}

		Log.d(
			TAG,
			"'$phrase' miss after ${PASSES.size} passes" +
				" capture=${captured - started}ms total=${System.currentTimeMillis() - started}ms"
		)
		return TextSearch.Missing("\"$phrase\" is not on screen")
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
		return elementRun(text, phrase)
	}

	/**
	 * A run of words anywhere on screen that spells the phrase, near enough.
	 *
	 * Everything above needs the recogniser to have read the phrase exactly and
	 * to have kept it inside one line or block. On large text it does neither:
	 * "Choose a password" comes back as "chhoose a password" read at full size,
	 * "chooseă password" at four tenths, and split across two blocks in between.
	 * Every one of those is the phrase to anyone looking at the screen, so this
	 * last pass matches what the words spell rather than how they were written
	 * down -- case, accents and spacing dropped, and a little distance allowed.
	 */
	private fun elementRun(text: Text, phrase: String): Rect? {
		val words = readingOrder(text)
		val run = spellingRun(words.map { it.text }, phrase) ?: return null
		val box = Rect(words[run.first].boundingBox)
		for (i in run) box.union(words[i].boundingBox)
		return box
	}

	/**
	 * Every recognised word, ordered the way the screen reads.
	 *
	 * Blocks come back in whatever order they were found, which for two halves of
	 * one heading is not necessarily left to right. Sorting by band and then by
	 * left edge puts them back in the order the phrase was written in. The band is
	 * the median word height, so words sharing a line share a bucket whatever size
	 * the frame was read at.
	 */
	private fun readingOrder(text: Text): List<Word> {
		val words = text.textBlocks
			.flatMap { it.lines }
			.flatMap { it.elements }
			.mapNotNull { element -> element.boundingBox?.let { Word(element.text, it) } }
		if (words.isEmpty()) return words

		val band = words.map { it.boundingBox.height() }.sorted()[words.size / 2].coerceAtLeast(1)
		return words.sortedWith(compareBy({ it.boundingBox.centerY() / band }, { it.boundingBox.left }))
	}

	/** A recognised word that is known to have a box, so callers need no null check. */
	private class Word(val text: String, val boundingBox: Rect)

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

/**
 * The consecutive [words] that spell [phrase], or null if none do.
 *
 * Kept free of the recogniser's own types so the rule can be tested without a
 * phone, because the rule is the whole of the decision: everything around it
 * only supplies words and turns an answer back into a box.
 */
fun spellingRun(words: List<String>, phrase: String): IntRange? {
	val wanted = phrase.folded()
	if (wanted.isEmpty()) return null
	val allowed = misreadAllowance(wanted)

	for (start in words.indices) {
		val spelled = StringBuilder()
		for (end in start until words.size) {
			spelled.append(words[end].folded())
			// Past the phrase's length plus what a misread could add, no longer
			// run can come back under the allowance, so stop growing this one.
			if (spelled.length > wanted.length + allowed) break
			if (editDistance(spelled.toString(), wanted) <= allowed) return start..end
		}
	}
	return null
}

/**
 * How many characters the recogniser may get wrong before a run stops counting.
 *
 * Scaled to the phrase because the risk is not symmetric: over a long heading a
 * character or two of slack costs nothing, since nothing else on screen is that
 * close to it, while over "Skip" the same slack would also accept "Ski" and
 * "Slip". Short phrases therefore get none, and have not needed any -- the
 * misreads only showed up on the oversized text.
 */
private fun misreadAllowance(phrase: String): Int = phrase.length / 10

/**
 * What the word spells, rather than how it was written.
 *
 * The recogniser varies case, invents accents and loses spaces on large glyphs,
 * and none of those change which words a person sees. Decomposing first turns an
 * invented accent into a letter plus a mark, so dropping the marks leaves the
 * letter behind rather than an unrelated character.
 */
private fun String.folded(): String =
	Normalizer.normalize(this, Normalizer.Form.NFD)
		.filterNot { it.isWhitespace() || it.category == CharCategory.NON_SPACING_MARK }
		.lowercase()

/** Levenshtein, over two rows rather than the whole table. */
private fun editDistance(left: String, right: String): Int {
	var previous = IntArray(right.length + 1) { it }
	var current = IntArray(right.length + 1)
	for (i in 1..left.length) {
		current[0] = i
		for (j in 1..right.length) {
			val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
			current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
		}
		val swap = previous
		previous = current
		current = swap
	}
	return previous[right.length]
}

/**
 * The frame as this pass wants it. Null when there would be nothing left to read.
 *
 * An untouched full-size pass hands back the original rather than a copy, which
 * is why callers compare by identity before recycling.
 */
private fun Bitmap.prepared(pass: Pass): Bitmap? {
	val scaledWidth = (width * pass.scale).toInt()
	val scaledHeight = (height * pass.scale).toInt()
	if (scaledWidth < 1 || scaledHeight < 1) return null

	val sized =
		if (pass.scale >= 1f) this
		else Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
	if (!pass.liftContrast) return sized

	val lifted = sized.contrastLifted()
	if (sized !== this) sized.recycle()
	return lifted
}

/**
 * Grey, with the greys pulled apart around mid-tone.
 *
 * Done through a colour matrix on a canvas rather than a loop over the pixels,
 * because a per-pixel pass over a 1080x2400 frame costs over a second and this
 * runs on every miss.
 */
private fun Bitmap.contrastLifted(): Bitmap {
	val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
	val shift = 128f * (1f - CONTRAST)
	val matrix = ColorMatrix().apply {
		setSaturation(0f)
		postConcat(
			ColorMatrix(
				floatArrayOf(
					CONTRAST, 0f, 0f, 0f, shift,
					0f, CONTRAST, 0f, 0f, shift,
					0f, 0f, CONTRAST, 0f, shift,
					0f, 0f, 0f, 1f, 0f
				)
			)
		)
	}
	Canvas(out).drawBitmap(this, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
	return out
}

/**
 * A box found in a shrunken frame, back in screen pixels.
 *
 * Everything downstream places a gesture in real pixels, so a match found at a
 * reduced size has to be grown back before it leaves here -- otherwise a phrase
 * read at 0.4x would anchor a tap near the top-left corner of the screen.
 */
private fun Rect.rescaled(scale: Float): Rect =
	if (scale >= 1f) this
	else Rect(
		(left / scale).toInt(),
		(top / scale).toInt(),
		(right / scale).toInt(),
		(bottom / scale).toInt()
	)
