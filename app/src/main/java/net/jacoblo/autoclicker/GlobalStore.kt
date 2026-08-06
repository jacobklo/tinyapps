package net.jacoblo.autoclicker

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "autoclicker.globals"

/**
 * Variables that outlive one playback, so a script can be written once and run
 * against different values.
 *
 * A run seeds its [ScriptContext] from here and then keeps its own copy: a Set
 * step shadows a global for the rest of that run without writing it back, which
 * is what stops one playback leaking into the next. Only an outside caller --
 * the control server, or someone editing globals.json -- changes what is stored.
 */
object GlobalStore {

	fun all(): Map<String, Value> {
		val file = Storage.globalsFile
		if (!file.exists()) return emptyMap()
		return try {
			val obj = JSONObject(file.readText())
			obj.keys().asSequence().associateWith { toValue(obj.get(it)) }
		} catch (e: Exception) {
			Log.w(TAG, "cannot read globals", e)
			emptyMap()
		}
	}

	/** Creating and editing are one operation: a name holds exactly one value. */
	fun set(values: Map<String, Value>) {
		if (values.isEmpty()) return
		write(all() + values)
	}

	fun delete(name: String) = write(all() - name)

	fun clear() = write(emptyMap())

	private fun write(values: Map<String, Value>) {
		val obj = JSONObject()
		values.forEach { (name, value) -> obj.put(name, toJson(value)) }
		try {
			Storage.globalsFile.writeText(obj.toString(4))
		} catch (e: Exception) {
			Log.e(TAG, "cannot write globals", e)
		}
	}

	/**
	 * A decimal is kept as text rather than as a number, because [Value.Num] is
	 * a Long and would drop the fraction without saying so. Everything a script
	 * does with a decimal -- typing it, comparing it as text -- still works.
	 */
	fun toValue(raw: Any?): Value = when (raw) {
		is Boolean -> Value.Bool(raw)
		is Int -> Value.Num(raw.toLong())
		is Long -> Value.Num(raw)
		is JSONArray -> Value.Arr((0 until raw.length()).map { toValue(raw.opt(it)) })
		else -> Value.Str(raw?.toString().orEmpty())
	}

	fun toJson(value: Value): Any = when (value) {
		is Value.Bool -> value.value
		is Value.Num -> value.value
		is Value.Str -> value.value
		is Value.Arr -> JSONArray().apply { value.items.forEach { put(toJson(it)) } }
	}
}
