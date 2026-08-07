package net.jacoblo.autoclicker

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "autoclicker.screen.conditions"

const val DEFAULT_MATCH_THRESHOLD = 0.90f

// Polling faster than this buys nothing: a capture plus a search already costs
// a couple of hundred milliseconds.
private const val POLL_INTERVAL_MS = 250L

/**
 * A search that can say why it failed.
 *
 * A condition only cares whether the area is there, but a gesture positioned
 * relative to one has to be abandoned when it is not, and "nothing happened" is
 * indistinguishable from a typo in the area name unless the reason is reported.
 */
sealed class AreaSearch {
	data class Found(val match: TemplateMatcher.Match) : AreaSearch()

	/** Phrased for showing to the user, so it names the thing to fix. */
	data class Missing(val reason: String) : AreaSearch()
}

/**
 * The screen-reading half of the expression language: whether a saved area is
 * currently visible.
 *
 * Templates are cached by file identity because decoding the PNG on every poll
 * would dominate the cost of the search itself.
 */
object ScreenConditions {

	private class CachedTemplate(
		val template: TemplateMatcher.Template,
		val lastModified: Long,
		val length: Long
	)

	private val cache = mutableMapOf<String, CachedTemplate>()

	/** Blocks on capture; callers run it off the main thread. */
	fun matches(name: String, threshold: Float = DEFAULT_MATCH_THRESHOLD): Boolean =
		search(name, threshold) is AreaSearch.Found

	/** Blocks on capture; callers run it off the main thread. */
	fun search(
		name: String,
		threshold: Float = DEFAULT_MATCH_THRESHOLD
	): AreaSearch {
		val template = template(name)
		if (template == null) {
			// The second lookup only happens on this failure path, and telling a
			// misspelled name from an unreadable file is worth it.
			val reason =
				if (ScreenshotStore.find(name) == null) "no saved area named \"$name\""
				else "cannot read the image for \"$name\""
			return AreaSearch.Missing(reason)
		}
		val started = System.currentTimeMillis()

		val frame = ScreenCapture.capture()
		if (frame == null) {
			Log.w(TAG, "no frame captured, '$name' cannot be evaluated")
			return AreaSearch.Missing("cannot read the screen, which needs root")
		}
		val captured = System.currentTimeMillis()

		val match = TemplateMatcher.find(frame, template, threshold)
		val finished = System.currentTimeMillis()

		Log.d(
			TAG,
			"'$name' ${if (match == null) "miss" else "hit at ${match.x},${match.y} (%.3f)".format(match.similarity)}" +
				" capture=${captured - started}ms search=${finished - captured}ms"
		)
		return if (match == null) AreaSearch.Missing("\"$name\" is not on screen")
		else AreaSearch.Found(match)
	}

	/** Polls until the area shows up or [timeoutMs] elapses. */
	suspend fun waitFor(
		name: String,
		timeoutMs: Long,
		threshold: Float = DEFAULT_MATCH_THRESHOLD
	): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (true) {
			// withContext keeps the capture off the main thread while leaving
			// the wait cancellable by the stop button.
			if (withContext(Dispatchers.IO) { matches(name, threshold) }) return true
			if (System.currentTimeMillis() >= deadline) return false
			delay(POLL_INTERVAL_MS)
		}
	}

	@Synchronized
	private fun template(name: String): TemplateMatcher.Template? {
		val shot = ScreenshotStore.find(name)
		if (shot == null) {
			Log.w(TAG, "no saved area named '$name'")
			return null
		}
		val file: File = shot.file
		val cached = cache[name]
		// Identity by size and mtime, so an area replaced under the same name
		// is picked up without restarting.
		if (cached != null && cached.lastModified == file.lastModified() && cached.length == file.length()) {
			return cached.template
		}

		val bitmap = BitmapFactory.decodeFile(file.absolutePath)
		if (bitmap == null) {
			Log.w(TAG, "cannot decode area '$name'")
			return null
		}
		val template = TemplateMatcher.Template.fromBitmap(bitmap)
		cache[name] = CachedTemplate(template, file.lastModified(), file.length())
		return template
	}
}
