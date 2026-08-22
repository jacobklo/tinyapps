package net.jacoblo.simpleanki.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes history.json, the single source of truth for every per-card figure.
 *
 * Records are stored oldest-first, exactly as the pre-repository code stored them, with
 * one added key: [HistoryEntry.timedOut]. A file written before that key existed is
 * migrated in place the first time it is loaded; see [migrate].
 */
class HistoryRepository(private val paths: AnkiPaths) {

	/**
	 * Loads all records oldest-first, running the timeout migration on first
	 * encounter with a pre-migration file.
	 *
	 * A missing or malformed file yields an empty list rather than an exception. When a
	 * migration runs, the original text is copied to [AnkiPaths.historyBackup] before
	 * history.json is replaced, overwriting any previous backup.
	 */
	fun load(): List<HistoryEntry> {
		val store = JsonStore(paths.history)
		val raw = store.readOrNull() ?: return emptyList()
		val migrated = migrate(raw) ?: return parse(raw)
		paths.ensureRoot()
		JsonStore(paths.historyBackup).write(raw)
		store.write(migrated)
		return parse(migrated)
	}

	/** Appends one record and trims to the newest [maxEntries] before writing. */
	fun append(entry: HistoryEntry, maxEntries: Int): List<HistoryEntry> {
		val trimmed = (load() + entry).takeLast(maxEntries)
		save(trimmed, maxEntries)
		return trimmed
	}

	/** Writes the newest [maxEntries] of [entries], oldest-first. */
	fun save(entries: List<HistoryEntry>, maxEntries: Int) {
		paths.ensureRoot()
		val array = JSONArray()
		entries.takeLast(maxEntries).forEach { entry ->
			val obj = JSONObject()
			obj.put("question", entry.question)
			obj.put("answer", entry.answer)
			obj.put("timeTaken", entry.timeTaken.toDouble())
			obj.put("timestamp", entry.timestamp)
			obj.put(KEY_TIMED_OUT, entry.timedOut)
			array.put(obj)
		}
		JsonStore(paths.history).write(array.toString())
	}

	private fun parse(rawJson: String): List<HistoryEntry> = try {
		val array = JSONArray(rawJson)
		val list = ArrayList<HistoryEntry>(array.length())
		for (i in 0 until array.length()) {
			val obj = array.getJSONObject(i)
			list.add(
				HistoryEntry(
					question = obj.getString("question"),
					answer = obj.getString("answer"),
					timeTaken = obj.getDouble("timeTaken").toFloat(),
					timestamp = obj.getLong("timestamp"),
					timedOut = obj.optBoolean(KEY_TIMED_OUT, false)
				)
			)
		}
		list
	} catch (_: Exception) {
		emptyList()
	}

	companion object {
		/** Threshold for inferring timedOut on pre-migration records. Never configurable. */
		const val LEGACY_TIMEOUT_SECONDS = 10.0f

		private const val KEY_TIMED_OUT = "timedOut"

		/**
		 * Returns null when no migration is needed, otherwise the migrated list.
		 * Pure: takes and returns parsed JSON text so it is directly unit-testable.
		 *
		 * A record already carrying a "timedOut" key keeps its value even when that value
		 * disagrees with the threshold; the rule only fills in missing keys. The presence
		 * of the key is the migration marker, so the file needs no schema version field.
		 *
		 * [LEGACY_TIMEOUT_SECONDS] is hardcoded rather than read from settings on purpose:
		 * it describes attempts that already happened under the old fixed behaviour, not
		 * the user's current metronome interval.
		 */
		fun migrate(rawJson: String): String? {
			val array = try {
				JSONArray(rawJson)
			} catch (_: Exception) {
				return null
			}
			var changed = false
			for (i in 0 until array.length()) {
				val obj = array.optJSONObject(i) ?: continue
				if (obj.has(KEY_TIMED_OUT)) continue
				val timeTaken = obj.optDouble("timeTaken", 0.0).toFloat()
				obj.put(KEY_TIMED_OUT, timeTaken >= LEGACY_TIMEOUT_SECONDS)
				changed = true
			}
			return if (changed) array.toString() else null
		}
	}
}
