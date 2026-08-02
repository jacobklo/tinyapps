package net.jacoblo.autoclicker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "autoclicker.screenshot.store"

/**
 * A captured screen region. [name] is what scripts reference, so it has to stay
 * unique; the rect and screen size are kept for context when reviewing them.
 */
data class Screenshot(
	val name: String,
	val fileName: String,
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
	val screenWidth: Int,
	val screenHeight: Int
) {
	val file: File get() = File(Storage.screenshotsDir, fileName)
}

/**
 * Saved screen regions plus the index describing them.
 *
 * The PNGs are the media; index.json carries everything else, matching the
 * rule that non-media data is JSON.
 */
object ScreenshotStore {

	private val _revision = MutableStateFlow(0)
	val revision: StateFlow<Int> = _revision.asStateFlow()

	fun list(): List<Screenshot> {
		val file = Storage.screenshotIndexFile
		if (!file.exists()) return emptyList()
		return try {
			val array = JSONArray(file.readText())
			(0 until array.length()).mapNotNull { i ->
				val obj = array.optJSONObject(i) ?: return@mapNotNull null
				Screenshot(
					name = obj.optString("name"),
					fileName = obj.optString("file"),
					left = obj.optInt("left"),
					top = obj.optInt("top"),
					width = obj.optInt("width"),
					height = obj.optInt("height"),
					screenWidth = obj.optInt("screenWidth"),
					screenHeight = obj.optInt("screenHeight")
				)
			}.filter { it.name.isNotBlank() && it.file.exists() }
		} catch (e: Exception) {
			Log.w(TAG, "cannot read screenshot index", e)
			emptyList()
		}
	}

	fun find(name: String): Screenshot? = list().firstOrNull { it.name == name }

	fun save(bitmap: Bitmap, rect: Rect, screen: ScreenGeometry): Screenshot? {
		val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
		val existing = list()
		val name = uniqueName(stamp, existing)
		val file = File(Storage.screenshotsDir, "$name.png")

		return try {
			FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
			val shot = Screenshot(
				name = name,
				fileName = file.name,
				left = rect.left,
				top = rect.top,
				width = bitmap.width,
				height = bitmap.height,
				screenWidth = screen.width,
				screenHeight = screen.height
			)
			write(existing + shot)
			shot
		} catch (e: Exception) {
			Log.e(TAG, "cannot save screenshot", e)
			null
		}
	}

	fun delete(names: Set<String>) {
		val remaining = list().filter { shot ->
			if (shot.name in names) {
				shot.file.delete()
				false
			} else {
				true
			}
		}
		write(remaining)
	}

	/** Returns false when the new name is blank or already taken. */
	fun rename(from: String, to: String): Boolean {
		val trimmed = to.trim()
		val all = list()
		if (trimmed.isBlank() || all.any { it.name == trimmed }) return false
		val target = all.firstOrNull { it.name == from } ?: return false

		val renamedFile = File(Storage.screenshotsDir, "$trimmed.png")
		if (!target.file.renameTo(renamedFile)) return false

		write(all.map { if (it.name == from) it.copy(name = trimmed, fileName = renamedFile.name) else it })
		return true
	}

	/**
	 * Thumbnails are decoded downsampled: the crops are small, but a grid of
	 * full-size bitmaps still adds up.
	 */
	fun thumbnail(shot: Screenshot, maxWidth: Int = 400): Bitmap? = try {
		val options = BitmapFactory.Options().apply {
			inSampleSize = if (shot.width > maxWidth) shot.width / maxWidth else 1
		}
		BitmapFactory.decodeFile(shot.file.absolutePath, options)
	} catch (e: Exception) {
		Log.w(TAG, "cannot decode ${shot.fileName}", e)
		null
	}

	private fun uniqueName(base: String, existing: List<Screenshot>): String {
		if (existing.none { it.name == base }) return base
		var suffix = 2
		while (existing.any { it.name == "${base}_$suffix" }) suffix++
		return "${base}_$suffix"
	}

	private fun write(shots: List<Screenshot>) {
		val array = JSONArray()
		shots.forEach { shot ->
			array.put(JSONObject().apply {
				put("name", shot.name)
				put("file", shot.fileName)
				put("left", shot.left)
				put("top", shot.top)
				put("width", shot.width)
				put("height", shot.height)
				put("screenWidth", shot.screenWidth)
				put("screenHeight", shot.screenHeight)
			})
		}
		try {
			Storage.screenshotIndexFile.writeText(array.toString(4))
			_revision.value++
		} catch (e: Exception) {
			Log.e(TAG, "cannot write screenshot index", e)
		}
	}
}
