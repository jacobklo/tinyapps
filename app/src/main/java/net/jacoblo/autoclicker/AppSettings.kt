package net.jacoblo.autoclicker

import android.content.Context
import android.util.Log
import org.json.JSONObject

private const val TAG = "autoclicker.settings"

/**
 * Persisted user settings, initialised once from [AutoClickerApp].
 *
 * Stored as JSON next to the recordings rather than in SharedPreferences, so
 * everything the app owns lives together and can be edited by hand.
 */
object AppSettings {

	private var values = JSONObject()

	lateinit var appContext: Context
		private set

	fun init(context: Context) {
		appContext = context.applicationContext
		reload()
	}

	/**
	 * Settings live on shared storage, which is unreadable until all-files
	 * access is granted, so the first load can legitimately come up empty and
	 * has to be repeated once permission arrives.
	 */
	fun reload() {
		values = try {
			val file = Storage.settingsFile
			if (file.exists()) JSONObject(file.readText()) else JSONObject()
		} catch (e: Exception) {
			Log.w(TAG, "cannot read settings, using defaults", e)
			JSONObject()
		}
	}

	private fun save() {
		try {
			Storage.settingsFile.writeText(values.toString(4))
		} catch (e: Exception) {
			Log.w(TAG, "cannot write settings", e)
		}
	}

	var useRoot: Boolean
		get() = values.optBoolean("useRoot", false)
		set(value) {
			values.put("useRoot", value)
			save()
		}

	/**
	 * Where the gmail-six-digit service is reachable, as host:port or a full
	 * URL. Kept here rather than on each step because there is one service on
	 * the network and repeating its address in every script only creates places
	 * for it to go stale.
	 */
	var codeServer: String
		get() = values.optString("codeServer", "")
		set(value) {
			values.put("codeServer", value.trim())
			save()
		}

	/**
	 * Humanization applied to every replayed evdev sample. Distinct from the
	 * per-interaction randomFactor, which varies behaviour rather than
	 * disguising it, and which still applies on top.
	 */
	var jitter: JitterConfig
		get() = JitterConfig(
			positionPx = values.optInt("jitterPositionPx", 2),
			pressurePct = values.optInt("jitterPressurePct", 8),
			timingPct = values.optInt("jitterTimingPct", 5),
			sizePct = values.optInt("jitterSizePct", 10)
		)
		set(value) {
			values.put("jitterPositionPx", value.positionPx)
			values.put("jitterPressurePct", value.pressurePct)
			values.put("jitterTimingPct", value.timingPct)
			values.put("jitterSizePct", value.sizePct)
			save()
		}
}

data class JitterConfig(
	val positionPx: Int,
	val pressurePct: Int,
	val timingPct: Int,
	val sizePct: Int
)
