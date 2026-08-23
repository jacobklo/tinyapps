package net.jacoblo.simpleanki.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
	 * A missing file yields an empty list. A document that will not parse as an array at
	 * all is QUARANTINED to "history.json.corrupt" before the empty list is returned:
	 * this is the largest file the app owns and the only one whose contents cannot be
	 * reconstructed from anywhere else, so it is the last one that should be allowed to
	 * disappear quietly under the next write. Per-record tolerance is unchanged; see
	 * [parse].
	 *
	 * A file that exists but cannot be read throws instead of yielding an empty list.
	 * The caller holds the list this returns and writes it back on the next answer, so
	 * "no history" and "could not read the history" have to be told apart here or a
	 * transient read failure would replace thousands of records with one.
	 *
	 * When a migration runs, the original text is copied to [AnkiPaths.historyBackup]
	 * before history.json is replaced, overwriting any previous backup.
	 *
	 * @throws IOException when history.json cannot be read, or when a migration cannot be
	 *   written back.
	 */
	fun load(): List<HistoryEntry> {
		val store = JsonStore(paths.history)
		val raw = when (val read = store.read()) {
			is ReadResult.Absent -> return emptyList()
			is ReadResult.Unreadable -> throw IOException("could not read ${paths.history.path}")
			is ReadResult.Present -> read.text
		}
		// migrate returns null both for an already-migrated file and for one that does not
		// parse, so the two are told apart below rather than here.
		val migrated = migrate(raw)
		if (migrated == null) {
			return parse(raw) ?: run {
				store.quarantine()
				emptyList()
			}
		}
		paths.ensureRoot()
		JsonStore(paths.historyBackup).write(raw)
		store.write(migrated)
		return parse(migrated) ?: emptyList()
	}

	/**
	 * Writes the newest [maxEntries] of [entries], oldest-first.
	 *
	 * There is deliberately no append. One that re-read the file would parse up to five
	 * thousand records twice on the UI thread on every card flip, and the caller already
	 * holds the authoritative list; see [net.jacoblo.simpleanki.data.recordAnswer].
	 *
	 * @throws IOException when history.json cannot be written, or when something is at
	 *   that path that cannot be read - see the refusal below.
	 */
	fun save(entries: List<HistoryEntry>, maxEntries: Int) {
		paths.ensureRoot()
		val store = JsonStore(paths.history)
		// The same refusal SettingsRepository.save and ViewsRepository.save make, and the
		// one that matters most: load() answers an unreadable file with an exception, the
		// caller shows a toast and carries on with an empty list, and without this the
		// next flip would write that empty list over every stored record. isUnreadable
		// rather than read() because this runs on every card flip.
		if (store.isUnreadable()) {
			throw IOException("refusing to overwrite unreadable ${paths.history.path}")
		}
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
		store.write(array.toString())
	}

	/**
	 * Parses the stored array, skipping any record that is not a well formed entry.
	 *
	 * Tolerance is per record, matching [migrate]: history.json is the only source of
	 * every per-card figure, so one bad row must not discard thousands of good ones.
	 *
	 * Returns NULL when the document is not a parseable array at all, which is a different
	 * thing from an array of unusable records and is what lets [load] quarantine the one
	 * case and not the other.
	 */
	private fun parse(rawJson: String): List<HistoryEntry>? = try {
		val array = JSONArray(rawJson)
		val list = ArrayList<HistoryEntry>(array.length())
		for (i in 0 until array.length()) {
			val entry = try {
				val obj = array.getJSONObject(i)
				HistoryEntry(
					question = obj.getString("question"),
					answer = obj.getString("answer"),
					timeTaken = obj.getDouble("timeTaken").toFloat(),
					timestamp = obj.getLong("timestamp"),
					timedOut = obj.optBoolean(KEY_TIMED_OUT, false)
				)
			} catch (_: Exception) {
				continue
			}
			list.add(entry)
		}
		list
	} catch (_: Exception) {
		null
	}

	companion object {
		/** Threshold for inferring timedOut on pre-migration records. Never configurable. */
		const val LEGACY_TIMEOUT_SECONDS = 10.0f

		private const val KEY_TIMED_OUT = "timedOut"

		/**
		 * Returns the migrated text, or null to leave the file untouched - which covers
		 * an already-migrated file, an empty array, and text that does not parse.
		 *
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
