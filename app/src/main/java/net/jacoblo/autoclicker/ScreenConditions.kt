package net.jacoblo.autoclicker

import android.graphics.BitmapFactory
import android.graphics.Rect
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
	fun matches(name: String, threshold: Float = DEFAULT_MATCH_THRESHOLD, roi: Rect? = null): Boolean =
		locate(name, threshold, roi) != null

	fun locate(
		name: String,
		threshold: Float = DEFAULT_MATCH_THRESHOLD,
		roi: Rect? = null
	): TemplateMatcher.Match? {
		val template = template(name) ?: return null
		val started = System.currentTimeMillis()

		val frame = ScreenCapture.capture()
		if (frame == null) {
			Log.w(TAG, "no frame captured, '$name' cannot be evaluated")
			return null
		}
		val captured = System.currentTimeMillis()

		val match = TemplateMatcher.find(frame, template, threshold, roi)
		val finished = System.currentTimeMillis()

		Log.d(
			TAG,
			"'$name' ${if (match == null) "miss" else "hit at ${match.x},${match.y} (%.3f)".format(match.similarity)}" +
				" capture=${captured - started}ms search=${finished - captured}ms"
		)
		return match
	}

	/** Polls until the area shows up or [timeoutMs] elapses. */
	suspend fun waitFor(
		name: String,
		timeoutMs: Long,
		threshold: Float = DEFAULT_MATCH_THRESHOLD,
		roi: Rect? = null
	): Boolean {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (true) {
			// withContext keeps the capture off the main thread while leaving
			// the wait cancellable by the stop button.
			if (withContext(Dispatchers.IO) { matches(name, threshold, roi) }) return true
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
