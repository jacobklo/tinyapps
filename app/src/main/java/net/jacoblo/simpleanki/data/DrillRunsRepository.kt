package net.jacoblo.simpleanki.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One drill's run file: a bare JSON array of completed runs, oldest first, the same shape
 * history.json takes.
 *
 * Takes the runs file itself - DrillKind.runsFile(paths) - rather than an AnkiPaths and a
 * kind, so an instance IS the file it owns. The alternative, one repository told which drill
 * it is on every call, is one mistaken argument away from filing a Numbers run in
 * poker-runs.json, and nothing downstream could tell that it had happened.
 *
 * Everything below mirrors [HistoryRepository]: tolerance per record, a quarantine for a
 * document that is not an array at all, and a refusal to write over a file it could not read.
 * The reasons given there apply here unchanged, and two files with the same job answering a
 * corrupt one two different ways would be a trap for whoever next fixes a bug in either.
 *
 * There is exactly ONE deliberate exception, in [load]: an unreadable file reads as empty here
 * rather than throwing. The argument for it is made there.
 */
class DrillRunsRepository(private val file: File) {

	/**
	 * Every stored run, oldest first. Never throws.
	 *
	 * An absent file is a user who has not drilled yet rather than an error. It reads as an
	 * empty list, and the file is NOT created on the way out, so opening a stats screen on a
	 * fresh install leaves the storage directory exactly as it found it.
	 *
	 * A document that will not parse as an array at all is QUARANTINED to "<name>.corrupt"
	 * before the empty list is returned; otherwise that empty list goes straight back out
	 * through the next [save] and takes every stored run with it. Per-record tolerance is a
	 * separate rule and does not quarantine anything; see [parse].
	 *
	 * An unreadable file reads as empty too, which is the one place this parts company with
	 * [HistoryRepository.load]. Folding it in with absence is safe here only because nothing
	 * on this path recreates the file, and because the guard the exception exists to feed
	 * there - a caller telling "no runs" from "could not read the runs" before writing the
	 * list back - is taken over by the [JsonStore.isUnreadable] refusal in [save].
	 *
	 * That substitute is not an equal, and the residue is accepted rather than overlooked:
	 * isUnreadable is approximate in one direction on purpose - see the note on it - so it
	 * catches the reproducible cases, a directory in the file's place or a permission that
	 * was never granted, and not a read that fails part way through. What the remaining case
	 * costs is one drill's runs, where the same slip against history.json would cost a
	 * lifetime of cards.
	 */
	fun load(): List<DrillRun> {
		val store = JsonStore(file)
		val raw = store.read().textOrNull ?: return emptyList()
		return parse(raw) ?: run {
			store.quarantine()
			emptyList()
		}
	}

	/**
	 * Writes the newest [maxEntries] of [runs], oldest first.
	 *
	 * takeLast and not take, because the list is held oldest-first: take keeps the OLDEST
	 * runs and silently discards every recent one, which is not a crash but a stats screen
	 * frozen on the user's first week forever.
	 *
	 * @throws IOException when the file cannot be written, or when something is at that path
	 *   that cannot be read - see the refusal below.
	 */
	fun save(runs: List<DrillRun>, maxEntries: Int) {
		// JsonStore.write does not create the parent directory and AnkiPaths.ensureRoot is
		// out of reach from a bare File, so the equivalent happens here: a user who drills
		// before ever touching a card reaches this line with no storage directory at all.
		file.parentFile?.mkdirs()
		val store = JsonStore(file)
		// The same refusal HistoryRepository.save makes, for the same reason: load() answers
		// an unreadable file with an empty list, so without this the save at the end of the
		// next drill would write that one run over every run the user has ever done.
		// isUnreadable rather than read() because nothing here wants the contents.
		if (store.isUnreadable()) {
			throw IOException("refusing to overwrite unreadable ${file.path}")
		}
		val array = JSONArray()
		runs.takeLast(maxEntries).forEach { array.put(toJson(it)) }
		store.write(array.toString())
	}

	/**
	 * Parses the stored array, skipping any record that is not a well formed run.
	 *
	 * Returns NULL when the document is not a parseable array at all, which is a different
	 * thing from an array of unusable records and is what lets [load] quarantine the one case
	 * and not the other.
	 */
	private fun parse(rawJson: String): List<DrillRun>? = try {
		val array = JSONArray(rawJson)
		val list = ArrayList<DrillRun>(array.length())
		for (i in 0 until array.length()) {
			val run = try {
				val obj = array.getJSONObject(i)
				DrillRun(
					id = obj.getString(KEY_ID),
					startedAt = obj.getLong(KEY_STARTED_AT),
					seconds = obj.getDouble(KEY_SECONDS).toFloat(),
					items = parseItems(obj.getJSONArray(KEY_ITEMS))
				)
			} catch (_: Exception) {
				continue
			}
			list.add(run)
		}
		list
	} catch (_: Exception) {
		null
	}

	/**
	 * The run's cells, in the order they were shown, with an unreadable one KEPT as a blank
	 * cell rather than dropped, and never at the cost of the run around it.
	 *
	 * Keeping the slot is what protects the score. The number of items is the denominator of
	 * [DrillRun.accuracy], so dropping one silently rewrites the run: 50 items with one
	 * unreadable cell would score out of 49 and read better than the user did. Keeping the
	 * run matters just as much, because a record refused at load does not merely go
	 * unreported - the caller loads the list, upserts into it and writes the whole thing
	 * back, so it is deleted from the only copy one save later.
	 *
	 * The mark goes with the value and is never kept beside a blank: a "right" on a cell
	 * whose value cannot be read is a claim about something nobody can check. UNSCORED weighs
	 * exactly as WRONG does in [DrillRun.accuracy], so the blank scores conservatively rather
	 * than in the user's favour, and it shows in the grid as the empty cell it is - visible
	 * damage the user can go and repair, rather than a silently better number.
	 */
	private fun parseItems(array: JSONArray): List<DrillItem> =
		(0 until array.length()).map { parseItem(array.optJSONObject(it)) }

	/** [obj] as a cell, or [BLANK_CELL] when it carries no value this can show. */
	private fun parseItem(obj: JSONObject?): DrillItem {
		if (obj == null) return BLANK_CELL
		val value = obj.optString(KEY_VALUE)
		if (value.isEmpty()) return BLANK_CELL
		return DrillItem(value, statusFromWire(obj.optString(KEY_STATUS)))
	}

	private fun toJson(run: DrillRun): JSONObject {
		val items = JSONArray()
		run.items.forEach { item ->
			items.put(JSONObject().put(KEY_VALUE, item.value).put(KEY_STATUS, statusToWire(item.status)))
		}
		return JSONObject()
			.put(KEY_ID, run.id)
			.put(KEY_STARTED_AT, run.startedAt)
			// As a double, because org.json has no float. Widening and then narrowing again
			// is exact, so 83.4f - which is not exactly representable - goes out as
			// 83.4000015258789 and reads back as the same float rather than a neighbour.
			.put(KEY_SECONDS, run.seconds.toDouble())
			.put(KEY_ITEMS, items)
	}

	companion object {
		/**
		 * [runs] with [run] appended, or replaced IN PLACE when a run with its id is already
		 * there.
		 *
		 * In place, and not removed-then-appended. Scoring rewrites the same run on every
		 * tap, so a re-scored run that moved to the end would climb over runs that genuinely
		 * came after it - and since [save] keeps the newest by position, that is not just a
		 * misordered list but an older run evicting a newer one from the file.
		 */
		fun upsert(runs: List<DrillRun>, run: DrillRun): List<DrillRun> =
			if (runs.none { it.id == run.id }) runs + run
			else runs.map { if (it.id == run.id) run else it }

		/** What an unreadable cell reads as: present, so it still counts, and blank so it shows. */
		private val BLANK_CELL = DrillItem("")

		private const val KEY_ID = "id"
		private const val KEY_STARTED_AT = "startedAt"
		private const val KEY_SECONDS = "seconds"
		private const val KEY_ITEMS = "items"
		private const val KEY_VALUE = "value"
		private const val KEY_STATUS = "status"

		private const val STATUS_RIGHT = "right"
		private const val STATUS_WRONG = "wrong"

		/**
		 * UNSCORED writes as the EMPTY string rather than as "unscored", so the mark a cell
		 * carries when the user has not touched it costs the file nothing to read past. It is
		 * also what a hand-edit reaches for when clearing a mark.
		 */
		private fun statusToWire(status: ItemStatus): String = when (status) {
			ItemStatus.UNSCORED -> ""
			ItemStatus.RIGHT -> STATUS_RIGHT
			ItemStatus.WRONG -> STATUS_WRONG
		}

		/**
		 * Anything that is not [STATUS_RIGHT] or [STATUS_WRONG] reads as UNSCORED - a missing
		 * key, a typo, a status some later version writes that this one has never heard of.
		 *
		 * These files are meant to be hand-edited, and "unchecked" is the one reading that is
		 * never a claim about the user: failing the record instead would throw away the 49
		 * cells that were fine, and guessing RIGHT would credit them with an answer they did
		 * not give.
		 */
		private fun statusFromWire(text: String): ItemStatus = when (text) {
			STATUS_RIGHT -> ItemStatus.RIGHT
			STATUS_WRONG -> ItemStatus.WRONG
			else -> ItemStatus.UNSCORED
		}
	}
}
