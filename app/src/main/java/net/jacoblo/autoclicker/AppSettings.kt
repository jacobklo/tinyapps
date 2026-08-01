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

	private lateinit var prefs: SharedPreferences

	fun init(context: Context) {
		prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	}

	var useRoot: Boolean
		get() = prefs.getBoolean(KEY_USE_ROOT, false)
		set(value) = prefs.edit { putBoolean(KEY_USE_ROOT, value) }
}
