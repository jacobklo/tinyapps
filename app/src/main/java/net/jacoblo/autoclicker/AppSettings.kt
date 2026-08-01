package net.jacoblo.autoclicker

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persisted user settings, initialised once from [AutoClickerApp].
 */
object AppSettings {

	private const val PREFS_NAME = "autoclicker_settings"
	private const val KEY_USE_ROOT = "use_root"
	private const val KEY_JITTER_POSITION = "jitter_position_px"
	private const val KEY_JITTER_PRESSURE = "jitter_pressure_pct"
	private const val KEY_JITTER_TIMING = "jitter_timing_pct"
	private const val KEY_JITTER_SIZE = "jitter_size_pct"

	private lateinit var prefs: SharedPreferences

	lateinit var appContext: Context
		private set

	fun init(context: Context) {
		appContext = context.applicationContext
		prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

	var useRoot: Boolean
		get() = prefs.getBoolean(KEY_USE_ROOT, false)
		set(value) = prefs.edit { putBoolean(KEY_USE_ROOT, value) }

	/**
	 * Humanization applied to every replayed evdev sample. Distinct from the
	 * per-interaction randomFactor, which varies behaviour rather than
	 * disguising it, and which still applies on top.
	 */
	var jitter: JitterConfig
		get() = JitterConfig(
			positionPx = prefs.getInt(KEY_JITTER_POSITION, 2),
			pressurePct = prefs.getInt(KEY_JITTER_PRESSURE, 8),
			timingPct = prefs.getInt(KEY_JITTER_TIMING, 5),
			sizePct = prefs.getInt(KEY_JITTER_SIZE, 10)
		)
		set(value) = prefs.edit {
			putInt(KEY_JITTER_POSITION, value.positionPx)
			putInt(KEY_JITTER_PRESSURE, value.pressurePct)
			putInt(KEY_JITTER_TIMING, value.timingPct)
			putInt(KEY_JITTER_SIZE, value.sizePct)
		}
}

data class JitterConfig(
	val positionPx: Int,
	val pressurePct: Int,
	val timingPct: Int,
	val sizePct: Int
)
