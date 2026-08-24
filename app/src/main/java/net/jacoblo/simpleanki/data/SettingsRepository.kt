package net.jacoblo.simpleanki.data

import org.json.JSONObject
import java.io.IOException

/**
 * Reads and writes settings.json, the home of every user preference and of the
 * lifetime review counter.
 *
 * Keys this build does not recognise are carried through untouched: [save] merges onto
 * whatever is already on disk instead of replacing it, so a file written by a newer
 * build survives a downgrade with its extra fields intact.
 *
 * [SCHEMA_VERSION] is the deliberate exception: it describes the shape the writer just
 * wrote, so a downgrade stamps it back down while still keeping those extra keys. That
 * is what tells the next newer build "a v1 build wrote this last, re-run your
 * migration", and it is why any migration added here must be idempotent.
 */
class SettingsRepository(private val paths: AnkiPaths) {

	/**
	 * Returns the settings in force, creating the file on first run.
	 *
	 * A file that parses is authoritative and stats.json is never consulted again,
	 * which is what makes the seed idempotent - a user who later resets their count
	 * cannot have it silently restored from a stale stats.json.
	 *
	 * Absent and CORRUPT are one path: with no readable settings.json this is a first
	 * run, so the file is seeded from stats.json either way, the corrupt case being
	 * quarantined to "settings.json.corrupt" first. Recovering the lifetime count
	 * under-reports the reviews logged since the seed, but the alternative - defaulting
	 * to zero with the real total sitting in stats.json - destroys the only copy
	 * instead of merely aging it.
	 *
	 * UNREADABLE is its own path and takes neither branch. A file that exists but will
	 * not read is most likely a perfectly good one behind a transient failure or a
	 * permission that has not been granted yet, and quarantining it would trade the
	 * user's real lifetime total for whatever stats.json still remembers. Nothing is
	 * written, defaults are returned for this run, and the next call retries.
	 *
	 * Never throws. When the new file cannot be written - typically because storage
	 * permission has not been granted yet - the settings are still returned and the
	 * file is left absent, so the next call retries the seed rather than banking a
	 * zero over the user's real total.
	 */
	fun load(): Settings {
		val store = JsonStore(paths.settings)
		val read = store.read()
		if (read is ReadResult.Unreadable) return Settings()
		val root = parseObject(read.textOrNull)
		if (root != null) return fromJson(root)
		// A no-op when there was no file to move, which is what lets the absent and
		// corrupt cases share the seeding branch below.
		store.quarantine()
		val fresh = Settings(
			counters = CounterSettings(
				// Absent and unreadable are one case here and only here: the seed is
				// best effort, and a stats.json that will not read has nothing to offer.
				seedLifetimeReviews(JsonStore(paths.legacyStats).read().textOrNull)
			)
		)
		try {
			save(fresh)
		} catch (_: IOException) {
			// Deliberately swallowed; see the note on retrying in the doc comment.
		}
		return fresh
	}

	/**
	 * Writes [settings], preserving every key already on disk that this build does not
	 * know about, at the top level and inside each nested object.
	 *
	 * @throws IOException when the file cannot be written, or when it exists but cannot be
	 *   read - see the refusal below.
	 */
	fun save(settings: Settings) {
		paths.ensureRoot()
		val store = JsonStore(paths.settings)
		val read = store.read()
		// Refusing rather than overwriting, and the single most valuable line in this
		// file. Merging needs the current contents; without them a save would replace a
		// file that may be entirely healthy, taking the lifetime review count - which
		// exists in no other copy - down to whatever this caller happened to be holding.
		if (read is ReadResult.Unreadable) {
			throw IOException("refusing to overwrite unreadable ${paths.settings.path}")
		}
		val root = parseObject(read.textOrNull) ?: JSONObject()
		root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
		child(root, KEY_METRONOME).apply {
			put(KEY_ENABLED, settings.metronome.enabled)
			put(KEY_INTERVAL_SECONDS, settings.metronome.intervalSeconds.toDouble())
			// put(name, null) would delete the key, so an explicit null needs NULL.
			put(KEY_SOUND_PATH, settings.metronome.soundPath ?: JSONObject.NULL)
		}
		child(root, KEY_TABLE).apply {
			put(KEY_DEFAULT_LIMIT, settings.table.defaultLimit)
			put(KEY_HIGHLIGHT_EVERY, settings.table.highlightEvery)
			put(KEY_DEFAULT_WINDOW_SIZE, settings.table.defaultWindowSize)
			put(KEY_HIGHLIGHT_COLOR_LIGHT, settings.table.highlightColorLight)
			put(KEY_HIGHLIGHT_COLOR_DARK, settings.table.highlightColorDark)
		}
		child(root, KEY_HISTORY).put(KEY_MAX_ENTRIES, settings.history.maxEntries)
		child(root, KEY_COUNTERS).put(KEY_LIFETIME_REVIEWS, settings.counters.lifetimeReviews)
		child(root, KEY_NUMBERS).apply {
			put(KEY_COUNT, settings.numbers.count)
			put(KEY_COLUMNS, settings.numbers.columns)
			put(KEY_CELL_WIDTH_DP, settings.numbers.cellWidthDp)
			put(KEY_CELL_HEIGHT_DP, settings.numbers.cellHeightDp)
		}
		// No count key: Poker is one full deck, and writing one would put a number on
		// disk that a hand-edit could change with nothing honouring it.
		child(root, KEY_POKER).apply {
			put(KEY_COLUMNS, settings.poker.columns)
			put(KEY_CELL_WIDTH_DP, settings.poker.cellWidthDp)
			put(KEY_CELL_HEIGHT_DP, settings.poker.cellHeightDp)
		}
		store.write(root.toString())
	}

	/**
	 * Reads every field, falling back to the declared default for anything missing or
	 * of the wrong type. The schema version is deliberately not read; it is written on
	 * every save so that a future migration has something to branch on.
	 */
	private fun fromJson(root: JSONObject): Settings {
		val fallback = Settings()
		// Every section is read the same way, and the empty object is doing real work in
		// each: optJSONObject yields null for a section that is absent AND for one that is
		// present but is not an object, so a hand-edited "numbers": 50 or "table": [] falls
		// back to defaults per key below rather than throwing on the way to the settings
		// screen that would let the user repair it.
		val metronome = root.optJSONObject(KEY_METRONOME) ?: JSONObject()
		val table = root.optJSONObject(KEY_TABLE) ?: JSONObject()
		val history = root.optJSONObject(KEY_HISTORY) ?: JSONObject()
		val counters = root.optJSONObject(KEY_COUNTERS) ?: JSONObject()
		val numbers = root.optJSONObject(KEY_NUMBERS) ?: JSONObject()
		val poker = root.optJSONObject(KEY_POKER) ?: JSONObject()
		return Settings(
			metronome = MetronomeSettings(
				enabled = metronome.optBoolean(KEY_ENABLED, fallback.metronome.enabled),
				intervalSeconds = metronome.optDouble(
					KEY_INTERVAL_SECONDS,
					fallback.metronome.intervalSeconds.toDouble()
				).toFloat(),
				// optString on a JSON null yields the literal "null", never "", so the
				// absent and null cases are both tested for up front.
				soundPath = if (metronome.isNull(KEY_SOUND_PATH)) null
					else metronome.getString(KEY_SOUND_PATH)
			),
			table = TableSettings(
				defaultLimit = table.optInt(KEY_DEFAULT_LIMIT, fallback.table.defaultLimit),
				highlightEvery = table.optInt(KEY_HIGHLIGHT_EVERY, fallback.table.highlightEvery),
				defaultWindowSize = table.optInt(
					KEY_DEFAULT_WINDOW_SIZE,
					fallback.table.defaultWindowSize
				),
				// Read verbatim, malformed values included. What is stored has to be
				// visible in the settings screen for a hand-edited typo to be fixable
				// there; TableSettings.highlightColor is what keeps it off the page.
				highlightColorLight = table.optString(
					KEY_HIGHLIGHT_COLOR_LIGHT,
					fallback.table.highlightColorLight
				),
				highlightColorDark = table.optString(
					KEY_HIGHLIGHT_COLOR_DARK,
					fallback.table.highlightColorDark
				)
			),
			history = HistorySettings(
				maxEntries = history.optInt(KEY_MAX_ENTRIES, fallback.history.maxEntries)
			),
			counters = CounterSettings(
				lifetimeReviews = counters.optInt(
					KEY_LIFETIME_REVIEWS,
					fallback.counters.lifetimeReviews
				)
			),
			numbers = NumbersSettings(
				count = numbers.optInt(KEY_COUNT, fallback.numbers.count),
				columns = numbers.optInt(KEY_COLUMNS, fallback.numbers.columns),
				cellWidthDp = numbers.optInt(KEY_CELL_WIDTH_DP, fallback.numbers.cellWidthDp),
				cellHeightDp = numbers.optInt(KEY_CELL_HEIGHT_DP, fallback.numbers.cellHeightDp)
			),
			poker = PokerSettings(
				columns = poker.optInt(KEY_COLUMNS, fallback.poker.columns),
				cellWidthDp = poker.optInt(KEY_CELL_WIDTH_DP, fallback.poker.cellWidthDp),
				cellHeightDp = poker.optInt(KEY_CELL_HEIGHT_DP, fallback.poker.cellHeightDp)
			)
		)
	}

	/**
	 * The nested object stored at [name], created and attached when absent or when the
	 * stored value is not an object. Returning the live child is what lets [save]
	 * overwrite the keys it owns without disturbing its neighbours.
	 */
	private fun child(root: JSONObject, name: String): JSONObject {
		root.optJSONObject(name)?.let { return it }
		val created = JSONObject()
		root.put(name, created)
		return created
	}

	/** Parses [raw] as an object, or null when it is unparseable or not an object. */
	private fun parseObject(raw: String?): JSONObject? {
		if (raw == null) return null
		return try {
			JSONObject(raw)
		} catch (_: Exception) {
			null
		}
	}

	companion object {
		/** Written on every save; not read yet - reserved for a future migration. */
		const val SCHEMA_VERSION = 1

		/**
		 * The lifetime review count carried over from the retired stats.json.
		 *
		 * Returns 0 when [statsJson] is null, does not parse as an object, or has no
		 * "statsUpdateCount" key. The count exists nowhere else - history.json is a
		 * rolling window - so a bad file costs the user their total, but it must never
		 * take the app down with it.
		 *
		 * Only ever called by [load], and only when settings.json is absent or
		 * unreadable. A doubly broken state - no settings.json and no usable stats.json -
		 * therefore degrades to a count of zero rather than to a crash.
		 */
		fun seedLifetimeReviews(statsJson: String?): Int {
			if (statsJson == null) return 0
			return try {
				JSONObject(statsJson).optInt(KEY_STATS_UPDATE_COUNT, 0)
			} catch (_: Exception) {
				0
			}
		}

		/** The only key ever read from the retired stats.json. */
		private const val KEY_STATS_UPDATE_COUNT = "statsUpdateCount"

		private const val KEY_SCHEMA_VERSION = "schemaVersion"
		private const val KEY_METRONOME = "metronome"
		private const val KEY_ENABLED = "enabled"
		private const val KEY_INTERVAL_SECONDS = "intervalSeconds"
		private const val KEY_SOUND_PATH = "soundPath"
		private const val KEY_TABLE = "table"
		private const val KEY_DEFAULT_LIMIT = "defaultLimit"
		private const val KEY_HIGHLIGHT_EVERY = "highlightEvery"
		private const val KEY_DEFAULT_WINDOW_SIZE = "defaultWindowSize"
		private const val KEY_HIGHLIGHT_COLOR_LIGHT = "highlightColorLight"
		private const val KEY_HIGHLIGHT_COLOR_DARK = "highlightColorDark"
		private const val KEY_HISTORY = "history"
		private const val KEY_MAX_ENTRIES = "maxEntries"
		private const val KEY_COUNTERS = "counters"
		private const val KEY_LIFETIME_REVIEWS = "lifetimeReviews"
		private const val KEY_NUMBERS = "numbers"
		private const val KEY_POKER = "poker"
		private const val KEY_COUNT = "count"

		// One constant per JSON key, not per field. The two drill sections are separate
		// objects, so the same spelling under both cannot collide - and settings.json is
		// hand-edited, so both grids must use one word for one knob. Six constants would
		// let "columns" be renamed under numbers and left alone under poker.
		private const val KEY_COLUMNS = "columns"
		private const val KEY_CELL_WIDTH_DP = "cellWidthDp"
		private const val KEY_CELL_HEIGHT_DP = "cellHeightDp"
	}
}
