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

// Enough to pull a dimmed pill's grey-on-grey apart without flattening the
// antialiasing that gives ordinary text its shape.
private const val CONTRAST = 3f

// Bands the frame is cut into, tall and overlapping. Recognition works from a
// resized copy of whatever it is given, so a band of the screen keeps detail a
// whole screen loses, and a phrase landing on one band's seam is whole in its
// neighbour.
private const val BAND_HEIGHT = 700
private const val BAND_STEP = 600

// A region is only worth reading again if what came back is already recognisably
// the phrase, so at most this share of it may be wrong. Higher and every heading
// on screen becomes a candidate.
private const val NEAR_MISS_SHARE = 0.5f
private const val NEAR_MISSES = 3

// Room around a re-read region for the parts of a glyph that fall outside the
// box the recogniser drew around it.
private const val CROP_MARGIN = 12

// What one line of text is grown to before being read again. Big enough that
// nothing is lost to resizing, small enough not to be a new problem.
private const val CROP_LINE_HEIGHT = 96
private const val CROP_MAX_SCALE = 4f

/** One look at part of the frame: which part, how big, and how hard. */
private class Look(val region: Rect, val scale: Float = 1f, val liftContrast: Boolean = false) {
	override fun toString() = "${region.top}-${region.bottom} at ${scale}x" +
		if (liftContrast) " contrast" else ""
}

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
 *
 * One reading of one frame is not reliable enough to anchor a gesture on. The
 * recogniser resizes whatever it is given, so a screen's worth of pixels costs
 * it detail; it mangles case and invents accents on large glyphs; it groups a
 * heading into blocks that do not match how the heading reads; and a label drawn
 * grey on grey it may not find at all. So the frame is looked at several ways
 * until the phrase turns up, and the matching is done on what the words spell
 * rather than on what came back character for character.
 */
object ScreenText {

	private val recognizer by lazy {
		TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	}

	/**
	 * Blocks on capture and recognition; callers run it off the main thread.
	 *
	 * A [thorough] look takes the frame apart and costs seconds; the quick one is
	 * a single reading and costs a fraction of one. Which to ask for depends on
	 * why the phrase might be missing. A caller watching for a screen to arrive
	 * wants to hear about it promptly and can ask again, so it asks quickly and
	 * often. A caller with one chance -- placing a gesture, deciding a condition
	 * -- cannot afford to be told no by a reading that simply struggled.
	 */
	fun find(phrase: String, matchCase: Boolean = true, thorough: Boolean = true): TextSearch {
		if (phrase.isBlank()) return TextSearch.Missing("no phrase to look for")

		val started = System.currentTimeMillis()
		val frame = ScreenCapture.capture()
			?: return TextSearch.Missing("cannot read the screen, which needs root")
		val full = frame.toBitmap()

		try {
			val box = search(full, phrase.trim(), matchCase, thorough)
			Log.d(
				TAG,
				"'$phrase' ${if (box == null) "miss" else "hit at ${box.left},${box.top}"}" +
					" in ${System.currentTimeMillis() - started}ms"
			)
			return if (box == null) TextSearch.Missing("\"$phrase\" is not on screen")
			else TextSearch.Found(box)
		} finally {
			// A whole screen is ten megabytes and a wait polls twice a second, so
			// leaving these to the collector builds a backlog it cannot keep up with.
			full.recycle()
		}
	}

	/**
	 * Every look at [full], cheapest and likeliest first, until one finds it.
	 *
	 * The order is what keeps this affordable: almost every phrase is found by
	 * the first look, and only a genuine absence pays for all of them.
	 */
	private fun search(full: Bitmap, wanted: String, matchCase: Boolean, thorough: Boolean): Rect? {
		val whole = Look(Rect(0, 0, full.width, full.height))

		// The plain, whole, full-size reading -- where most phrases are found, and
		// the one whose near misses tell the next look where to concentrate.
		val first = recognise(full, whole) ?: return null
		locate(first, wanted, matchCase)?.let { return it.onScreen(whole) }
		if (!thorough) return null

		val strips = bands(full.height).map { Rect(0, it.top, full.width, it.bottom) }
		val rest = nearMisses(first, wanted) +
			listOf(
				Look(whole.region, scale = 0.6f),
				Look(whole.region, scale = 0.4f),
				Look(whole.region, liftContrast = true)
			) +
			strips.map { Look(it) } +
			strips.map { Look(it, liftContrast = true) }

		rest.forEach { look ->
			val text = recognise(full, look) ?: return@forEach
			locate(text, wanted, matchCase)?.let {
				Log.d(TAG, "found by looking at $look")
				return it.onScreen(look)
			}
		}
		return null
	}

	private fun recognise(full: Bitmap, look: Look): Text? {
		val bitmap = full.viewedThrough(look) ?: return null
		return try {
			Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
		} catch (e: Exception) {
			Log.e(TAG, "recognition failed looking at $look", e)
			null
		} finally {
			if (bitmap !== full) bitmap.recycle()
		}
	}

	/**
	 * Regions worth reading again on their own, closest first.
	 *
	 * Reading a whole screen costs the recogniser detail it does not get back, so
	 * a heading it half read from the screen it can often read outright from a
	 * crop of itself, grown. The catch is that cropping everything would cost a
	 * recognition per line, so only what already came back looking like the phrase
	 * is worth a second look -- which is exactly the case this is for, since a
	 * region that came back as nothing like it was not misread, it was elsewhere.
	 *
	 * Blocks are offered as well as lines because a heading that wrapped is one
	 * block and two lines, and the phrase is in neither line on its own.
	 */
	private fun nearMisses(text: Text, phrase: String): List<Look> {
		val wanted = phrase.folded()
		if (wanted.isEmpty()) return emptyList()

		val regions = mutableListOf<Triple<Rect, String, Int>>()
		text.textBlocks.forEach { block ->
			val lines = block.lines
			block.boundingBox?.let { box ->
				val perLine = box.height() / lines.size.coerceAtLeast(1)
				regions.add(Triple(box, lines.joinToString(" ") { it.text }, perLine))
			}
			lines.forEach { line ->
				line.boundingBox?.let { regions.add(Triple(it, line.text, it.height())) }
			}
		}

		return regions
			.map { it to editDistance(it.second.folded(), wanted) }
			.filter { (_, distance) -> distance <= wanted.length * NEAR_MISS_SHARE }
			.sortedBy { (_, distance) -> distance }
			.distinctBy { (region, _) -> region.first }
			.take(NEAR_MISSES)
			.map { (region, _) ->
				val (box, _, lineHeight) = region
				val grown = Rect(box).apply { inset(-CROP_MARGIN, -CROP_MARGIN) }
				val scale = (CROP_LINE_HEIGHT.toFloat() / lineHeight.coerceAtLeast(1))
					.coerceIn(1f, CROP_MAX_SCALE)
				Look(grown, scale)
			}
	}

	/**
	 * Polls until the phrase shows up or [timeoutMs] elapses.
	 *
	 * Quick looks while waiting, because the point of a wait is to notice the
	 * moment the screen arrives, and a thorough look would put seconds between
	 * one glance and the next. The thorough look is saved for the end: a phrase
	 * that was on screen the whole time but hard to read should not be reported
	 * absent just because no quick glance could manage it.
	 */
	suspend fun waitFor(phrase: String, timeoutMs: Long, matchCase: Boolean = true): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (true) {
			// withContext keeps the work off the main thread while leaving the
			// wait cancellable by the stop button.
			if (withContext(Dispatchers.IO) {
					find(phrase, matchCase, thorough = false)
				} is TextSearch.Found
			) {
				return true
			}
			if (System.currentTimeMillis() >= deadline) break
			delay(POLL_INTERVAL_MS)
		}
		return withContext(Dispatchers.IO) { find(phrase, matchCase) } is TextSearch.Found
	}

	/**
	 * The tightest box that covers the phrase.
	 *
	 * Tried against a run of words first, because that is the only level that
	 * bounds the phrase itself rather than whatever else shares its line. A
	 * heading that wrapped is two lines to the recogniser and one phrase to the
	 * person who wrote the script, so the block is tried after with its lines
	 * joined, and what the words spell last of all.
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
	 * to have kept it inside one line or block. Where anything is drawn over the
	 * words it does neither: with the app's own bubble across the top of a
	 * heading, "Choose a password" came back as "chhoose a password" read at full
	 * size, "chooseă password" at four tenths, and split across two blocks in
	 * between. Every one of those is the phrase to anyone looking at the screen,
	 * so this last pass matches what the words spell rather than how they were
	 * written down.
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

/** A horizontal slice of the frame: rows [top] up to but not including [bottom]. */
data class Band(val top: Int, val bottom: Int)

/**
 * Overlapping bands covering a frame [height] tall, or none if it is already
 * short enough that a band would be the whole thing.
 *
 * Rows rather than rectangles so the rule can be tested without a phone, since
 * a Rect off the device is a stub whose fields never take the values given.
 */
fun bands(height: Int): List<Band> {
	if (height <= BAND_HEIGHT) return emptyList()
	return (0 until height step BAND_STEP)
		.map { top -> Band(top, (top + BAND_HEIGHT).coerceAtMost(height)) }
		// A last sliver of a band holds no whole line and only costs a recognition.
		.filter { it.bottom - it.top > BAND_HEIGHT / 2 }
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
 * Scaled to the phrase, because the risk is not symmetric: over a heading a
 * character or two of slack costs nothing, since nothing else on screen is that
 * close to it, while over "Skip" the same slack would also accept "Slip". The
 * floor is where the two meet -- six characters is short enough to be a button
 * and long enough that one wrong character does not make it another word.
 *
 * Note that a phrase below the floor is not left defenceless: the confusions the
 * recogniser actually makes cost nothing in [editDistance], so "5kip" matches
 * "Skip" on any length while "Slip" still does not.
 */
private fun misreadAllowance(phrase: String): Int =
	if (phrase.length >= 6) maxOf(1, phrase.length / 10) else 0

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

/**
 * Shapes that are the same shape, in the alphabet the recogniser answers in.
 *
 * Held as sorted pairs so the lookup does not care which way round it is asked.
 * Deliberately only the digit-for-letter confusions: those are what a recogniser
 * mixes up, and they cannot turn one English word into another the way treating
 * c and e alike would.
 */
private val CONFUSABLE = setOf("0o", "1i", "1l", "il", "5s", "8b", "6b", "2z", "9g")

private fun confusable(left: Char, right: Char): Boolean {
	val pair = if (left < right) "$left$right" else "$right$left"
	return pair in CONFUSABLE
}

/**
 * Levenshtein, over two rows rather than the whole table, and forgiving a
 * substitution the recogniser was always liable to make.
 */
private fun editDistance(left: String, right: String): Int {
	var previous = IntArray(right.length + 1) { it }
	var current = IntArray(right.length + 1)
	for (i in 1..left.length) {
		current[0] = i
		for (j in 1..right.length) {
			val same = left[i - 1] == right[j - 1] || confusable(left[i - 1], right[j - 1])
			val substitution = previous[j - 1] + if (same) 0 else 1
			current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
		}
		val swap = previous
		previous = current
		current = swap
	}
	return previous[right.length]
}

/**
 * The frame as this look wants it. Null when there would be nothing left to read.
 *
 * The plain, whole, full-size look hands back the frame itself rather than a
 * copy, which saves ten megabytes on the look that finds most phrases -- and is
 * why callers compare by identity before recycling.
 */
private fun Bitmap.viewedThrough(look: Look): Bitmap? {
	val region = Rect(look.region)
	if (!region.intersect(0, 0, width, height)) return null
	val scaledWidth = (region.width() * look.scale).toInt()
	val scaledHeight = (region.height() * look.scale).toInt()
	if (scaledWidth < 1 || scaledHeight < 1) return null

	var view = Bitmap.createBitmap(this, region.left, region.top, region.width(), region.height())
	if (look.scale != 1f) {
		val scaled = Bitmap.createScaledBitmap(view, scaledWidth, scaledHeight, true)
		if (view !== this && view !== scaled) view.recycle()
		view = scaled
	}
	if (look.liftContrast) {
		val lifted = view.contrastLifted()
		if (view !== this) view.recycle()
		view = lifted
	}
	return view
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
 * A box found through a look, back in screen pixels.
 *
 * Everything downstream places a gesture in real pixels, so a match found in a
 * band of the screen, or in a crop grown four times, has to be put back where it
 * came from -- otherwise a phrase read from the lower half would anchor a tap
 * near the top of the screen.
 */
private fun Rect.onScreen(look: Look) = Rect(
	look.region.left + (left / look.scale).toInt(),
	look.region.top + (top / look.scale).toInt(),
	look.region.left + (right / look.scale).toInt(),
	look.region.top + (bottom / look.scale).toInt()
)
