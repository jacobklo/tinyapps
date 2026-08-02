package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "autoclicker.triggers"

enum class TriggerType(val label: String, val takesParameter: Boolean, val parameterLabel: String) {
	APP_OPENED("App opens", true, "Package name"),
	APP_CLOSED("App closes", true, "Package name"),
	SCREEN_ON("Screen turns on", false, ""),
	SCREEN_OFF("Screen turns off", false, ""),
	UNLOCKED("Device unlocked", false, ""),
	NOTIFICATION("Notification contains", true, "Words to match")
}

/**
 * Runs [recording] when [type] fires. [parameter] is the package name or the
 * text to look for, depending on the type.
 */
data class Trigger(
	val id: Long,
	val type: TriggerType,
	val parameter: String,
	val recording: String,
	val enabled: Boolean = true
)

object TriggerStore {

	private val _revision = MutableStateFlow(0)
	val revision: StateFlow<Int> = _revision.asStateFlow()

	fun list(): List<Trigger> {
		val file = Storage.triggersFile
		if (!file.exists()) return emptyList()
		return try {
			val array = JSONArray(file.readText())
			(0 until array.length()).mapNotNull { i ->
				val obj = array.optJSONObject(i) ?: return@mapNotNull null
				val type = runCatching { TriggerType.valueOf(obj.optString("type")) }.getOrNull()
					?: return@mapNotNull null
				Trigger(
					id = obj.optLong("id"),
					type = type,
					parameter = obj.optString("parameter"),
					recording = obj.optString("recording"),
					enabled = obj.optBoolean("enabled", true)
				)
			}
		} catch (e: Exception) {
			Log.w(TAG, "cannot read triggers", e)
			emptyList()
		}
	}

	fun save(triggers: List<Trigger>) {
		val array = JSONArray()
		triggers.forEach { trigger ->
			array.put(JSONObject().apply {
				put("id", trigger.id)
				put("type", trigger.type.name)
				put("parameter", trigger.parameter)
				put("recording", trigger.recording)
				put("enabled", trigger.enabled)
			})
		}
		try {
			Storage.triggersFile.writeText(array.toString(4))
			_revision.value++
		} catch (e: Exception) {
			Log.e(TAG, "cannot write triggers", e)
		}
	}

	fun upsert(trigger: Trigger) {
		val existing = list()
		val replaced = existing.map { if (it.id == trigger.id) trigger else it }
		save(if (replaced.any { it.id == trigger.id }) replaced else existing + trigger)
	}

	fun delete(id: Long) = save(list().filterNot { it.id == id })

	fun nextId(): Long = (list().maxOfOrNull { it.id } ?: 0L) + 1L
}
