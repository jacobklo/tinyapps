/*
 * views.json: the table views, moved out of Kotlin constants and onto disk where a user
 * can hand-edit them.
 *
 * The file is the authority once it exists. [DefaultViews] stops being what the app shows
 * and becomes what a fresh install is seeded with and what [ViewsRepository.resetBuiltIns]
 * restores.
 */
package net.jacoblo.simpleanki.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** views.json in memory: every stored view, and which one the drawer had selected. */
data class ViewsFile(val activeViewId: String, val views: List<TableView>)

/**
 * Reads and writes views.json.
 *
 * Keys this build does not recognise are carried through untouched, per view and per
 * column as well as at the top level: [save] merges onto whatever is already on disk,
 * matching stored objects to saved ones by id. A file written by a newer build therefore
 * survives a downgrade with its extra fields intact.
 *
 * The built-ins are ordinary rows in that file. Nothing marks them read-only and nothing
 * refuses to save over them - a view the user cannot edit is a view they would have to
 * copy to change, and [resetBuiltIns] already covers the "put it back" case.
 */
class ViewsRepository(private val paths: AnkiPaths) {

	/**
	 * Returns the stored views, creating the file on first run.
	 *
	 * Recovery is per outcome and deliberately not uniform:
	 *  - absent: defaults are written, with no quarantine, since there is nothing to keep
	 *  - unreadable: nothing is written at all, and the defaults are returned for this
	 *    run only, so the next call retries a file that may be perfectly good
	 *  - unparseable or structurally invalid: quarantined to "views.json.corrupt" first,
	 *    then replaced, because a set of hand-tuned views is worth one cheap backup
	 *
	 * An [ViewsFile.activeViewId] naming no stored view falls back to the first one, so a
	 * hand-edit that renames a view cannot leave the drawer pointing at nothing.
	 *
	 * Never throws. When the fresh file cannot be written - typically because storage
	 * permission has not been granted yet - the defaults are still returned and the file
	 * is left absent, so the next call retries.
	 */
	fun load(tableSettings: TableSettings): ViewsFile {
		val store = JsonStore(paths.views)
		val read = store.read()
		if (read is ReadResult.Unreadable) return defaults(tableSettings)
		parseObject(read.textOrNull)?.let { fromJson(it) }?.let { return it }
		store.quarantine()
		val fresh = defaults(tableSettings)
		try {
			save(fresh)
		} catch (_: IOException) {
			// Deliberately swallowed; see the note on retrying in the doc comment.
		}
		return fresh
	}

	/**
	 * Writes [file], preserving every key already on disk that this build does not know
	 * about, at the top level and inside each stored view and column.
	 *
	 * @throws IOException when the file cannot be written, or when it exists but cannot be
	 *   read - see the refusal below.
	 */
	fun save(file: ViewsFile) {
		paths.ensureRoot()
		val store = JsonStore(paths.views)
		val read = store.read()
		// Refusing rather than overwriting. A file that exists but will not read may be a
		// perfectly good one behind a transient failure, and replacing it here would undo
		// the whole point of load() having left it alone.
		if (read is ReadResult.Unreadable) {
			throw IOException("refusing to overwrite unreadable ${paths.views.path}")
		}
		val root = parseObject(read.textOrNull) ?: JSONObject()
		val stored = indexById(root.optJSONArray(KEY_VIEWS))
		root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
		root.put(KEY_ACTIVE_VIEW_ID, file.activeViewId)
		val views = JSONArray()
		file.views.forEach { views.put(viewToJson(it, stored[it.id])) }
		root.put(KEY_VIEWS, views)
		store.write(root.toString())
	}

	/**
	 * Replaces the three built-ins with their factory definitions, leaving every other
	 * view exactly as it was - contents, and position in the list.
	 *
	 * A built-in the user had deleted comes back, appended, which is what makes this a
	 * usable way out of "I broke the History view". Purely in memory; the caller saves.
	 */
	fun resetBuiltIns(current: ViewsFile, tableSettings: TableSettings): ViewsFile {
		val builtIns = DefaultViews.all(tableSettings)
		val byId = builtIns.associateBy { it.id }
		val present = current.views.map { it.id }.toSet()
		val views = current.views.map { byId[it.id] ?: it } + builtIns.filter { it.id !in present }
		return ViewsFile(activeIdOf(current.activeViewId, views), views)
	}

	// -- reading ------------------------------------------------------------------------

	/** Null when [root] is not a usable views document; see [load] for what happens then. */
	private fun fromJson(root: JSONObject): ViewsFile? {
		val array = root.optJSONArray(KEY_VIEWS) ?: return null
		val views = ArrayList<TableView>(array.length())
		for (i in 0 until array.length()) {
			views.add(viewFromJson(array.optJSONObject(i) ?: return null) ?: return null)
		}
		// An empty list is corrupt rather than a user who deleted every view: it leaves the
		// drawer with no table entries at all, and so with no way back to a working state.
		if (views.isEmpty()) return null
		return ViewsFile(activeIdOf(root.stringOrNull(KEY_ACTIVE_VIEW_ID), views), views)
	}

	private fun viewFromJson(o: JSONObject): TableView? {
		val id = o.stringOrNull(KEY_ID)?.takeIf { it.isNotEmpty() } ?: return null
		val array = o.optJSONArray(KEY_COLUMNS) ?: JSONArray()
		val columns = ArrayList<ColumnSpec>(array.length())
		for (i in 0 until array.length()) {
			columns.add(columnFromJson(array.optJSONObject(i) ?: return null) ?: return null)
		}
		return TableView(
			id = id,
			name = o.stringOrNull(KEY_NAME) ?: id,
			filterToCurrentDeck = o.optBoolean(KEY_FILTER_TO_CURRENT_DECK, true),
			collapseDuplicatesOn = o.stringOrNull(KEY_COLLAPSE_DUPLICATES_ON),
			highlightEvery = o.optInt(KEY_HIGHLIGHT_EVERY, 0),
			defaultSort = sortFromJson(o.optJSONObject(KEY_DEFAULT_SORT), columns),
			columns = columns
		)
	}

	private fun columnFromJson(o: JSONObject): ColumnSpec? {
		val id = o.stringOrNull(KEY_ID)?.takeIf { it.isNotEmpty() } ?: return null
		return ColumnSpec(
			id = id,
			title = o.stringOrNull(KEY_TITLE) ?: id,
			width = o.optInt(KEY_WIDTH, DEFAULT_WIDTH),
			visible = o.optBoolean(KEY_VISIBLE, true),
			frozen = o.optBoolean(KEY_FROZEN, false),
			format = CellFormat.entries.firstOrNull { formatToken(it) == o.stringOrNull(KEY_FORMAT) },
			computed = computedFromJson(o),
			// A plain string until Task 12 parses it. Round-tripping it verbatim is what
			// lets a user hand-write a formula this build cannot yet read without losing it
			// on the next autosave.
			formula = o.stringOrNull(KEY_FORMULA),
			formulaError = o.stringOrNull(KEY_FORMULA_ERROR)
		)
	}

	/**
	 * The aggregate the column carries, or null when it carries none.
	 *
	 * A partition that is absent or names an unknown mode makes the aggregate unusable, so
	 * the column reads back as a plain one rather than as an aggregate over an invented
	 * default. [save] always writes a partition beside an aggregate, so this only fires on
	 * a hand-edit.
	 */
	private fun computedFromJson(o: JSONObject): ComputedSpec? {
		val token = o.stringOrNull(KEY_AGGREGATE) ?: return null
		val aggregate = Aggregate.entries.firstOrNull { it.name == token } ?: return null
		val partition = partitionFromJson(o.optJSONObject(KEY_PARTITION)) ?: return null
		return ComputedSpec(
			aggregate = aggregate,
			source = o.stringOrNull(KEY_SOURCE) ?: "",
			partition = partition,
			limit = o.optInt(KEY_LIMIT, 0)
		)
	}

	private fun partitionFromJson(o: JSONObject?): Partition? {
		if (o == null) return null
		return when (o.stringOrNull(KEY_MODE)) {
			MODE_GROUP -> Partition.Group(o.stringOrNull(KEY_BY) ?: return null)
			MODE_BUCKET -> Partition.Bucket(o.optInt(KEY_SIZE, 0))
			MODE_ROLLING -> Partition.Rolling(o.optInt(KEY_SIZE, 0))
			else -> null
		}
	}

	/**
	 * The stored sort, or the first column descending when there is none.
	 *
	 * A sort naming a column that cannot be sorted is not corrected here; TableEngine
	 * resolves it to its own fallback and reports a warning, which is the one place that
	 * decision belongs.
	 */
	private fun sortFromJson(o: JSONObject?, columns: List<ColumnSpec>): SortSpec {
		val column = o?.stringOrNull(KEY_COLUMN) ?: columns.firstOrNull()?.id ?: ""
		val token = o?.stringOrNull(KEY_DIR)
		return SortSpec(column, SortDir.entries.firstOrNull { dirToken(it) == token } ?: SortDir.DESC)
	}

	// -- writing ------------------------------------------------------------------------

	private fun viewToJson(view: TableView, existing: JSONObject?): JSONObject {
		val o = existing ?: JSONObject()
		val stored = indexById(o.optJSONArray(KEY_COLUMNS))
		o.put(KEY_ID, view.id)
		o.put(KEY_NAME, view.name)
		o.put(KEY_FILTER_TO_CURRENT_DECK, view.filterToCurrentDeck)
		// put(name, null) would delete the key, so an explicit null needs NULL.
		o.put(KEY_COLLAPSE_DUPLICATES_ON, view.collapseDuplicatesOn ?: JSONObject.NULL)
		o.put(KEY_HIGHLIGHT_EVERY, view.highlightEvery)
		child(o, KEY_DEFAULT_SORT).apply {
			put(KEY_COLUMN, view.defaultSort.column)
			put(KEY_DIR, dirToken(view.defaultSort.dir))
		}
		val columns = JSONArray()
		view.columns.forEach { columns.put(columnToJson(it, stored[it.id])) }
		o.put(KEY_COLUMNS, columns)
		return o
	}

	private fun columnToJson(column: ColumnSpec, existing: JSONObject?): JSONObject {
		val o = existing ?: JSONObject()
		o.put(KEY_ID, column.id)
		o.put(KEY_TITLE, column.title)
		o.put(KEY_WIDTH, column.width)
		o.put(KEY_VISIBLE, column.visible)
		o.put(KEY_FROZEN, column.frozen)
		putOrRemove(o, KEY_FORMAT, column.format?.let { formatToken(it) })
		val computed = column.computed
		if (computed == null) {
			// Removed rather than left behind: these four are this build's to own, so a
			// column that stopped being an aggregate must stop reading back as one.
			listOf(KEY_AGGREGATE, KEY_SOURCE, KEY_LIMIT, KEY_PARTITION).forEach { o.remove(it) }
		} else {
			o.put(KEY_AGGREGATE, computed.aggregate.name)
			o.put(KEY_SOURCE, computed.source)
			o.put(KEY_LIMIT, computed.limit)
			o.put(KEY_PARTITION, partitionToJson(computed.partition))
		}
		putOrRemove(o, KEY_FORMULA, column.formula)
		putOrRemove(o, KEY_FORMULA_ERROR, column.formulaError)
		return o
	}

	private fun partitionToJson(partition: Partition): JSONObject = when (partition) {
		is Partition.Group -> JSONObject().put(KEY_MODE, MODE_GROUP).put(KEY_BY, partition.by)
		is Partition.Bucket -> JSONObject().put(KEY_MODE, MODE_BUCKET).put(KEY_SIZE, partition.size)
		is Partition.Rolling -> JSONObject().put(KEY_MODE, MODE_ROLLING).put(KEY_SIZE, partition.size)
	}

	// -- shared helpers -------------------------------------------------------------------

	/** [stored] keyed by its elements' "id", so a rewrite can merge onto the right object. */
	private fun indexById(stored: JSONArray?): Map<String, JSONObject> {
		if (stored == null) return emptyMap()
		val map = HashMap<String, JSONObject>(stored.length())
		for (i in 0 until stored.length()) {
			val o = stored.optJSONObject(i) ?: continue
			map[o.stringOrNull(KEY_ID) ?: continue] = o
		}
		return map
	}

	/** [candidate] when some view carries it, and the first view's id otherwise. */
	private fun activeIdOf(candidate: String?, views: List<TableView>): String =
		views.firstOrNull { it.id == candidate }?.id ?: views.first().id

	/**
	 * The nested object stored at [name], created and attached when absent or when the
	 * stored value is not an object. Returning the live child is what lets a rewrite
	 * overwrite the keys it owns without disturbing its neighbours.
	 */
	private fun child(root: JSONObject, name: String): JSONObject {
		root.optJSONObject(name)?.let { return it }
		val created = JSONObject()
		root.put(name, created)
		return created
	}

	/** Writes [value], or removes the key entirely when it is null - an absent field. */
	private fun putOrRemove(o: JSONObject, key: String, value: String?) {
		if (value == null) o.remove(key) else o.put(key, value)
	}

	/**
	 * The string at [key], or null when the key is absent or holds a JSON null.
	 *
	 * optString cannot answer this on its own: it returns "" for an absent key and the
	 * literal "null" for a JSON null, so both have to be tested for up front.
	 */
	private fun JSONObject.stringOrNull(key: String): String? =
		if (!has(key) || isNull(key)) null else optString(key)

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

		/** The seed content of a fresh views.json: the three built-ins, the first active. */
		fun defaults(tableSettings: TableSettings): ViewsFile {
			val views = DefaultViews.all(tableSettings)
			return ViewsFile(views.first().id, views)
		}

		/**
		 * The stored spelling of a [CellFormat].
		 *
		 * The decimal formats are spelled the way a spreadsheet spells them, since this
		 * file is meant to be hand-edited. An exhaustive when rather than a map so a new
		 * format is a compile error here rather than a column that silently loses it.
		 */
		private fun formatToken(format: CellFormat): String = when (format) {
			CellFormat.TEXT -> "text"
			CellFormat.INT -> "int"
			CellFormat.ONE_DP -> "0.0"
			CellFormat.TWO_DP -> "0.00"
			CellFormat.PERCENT -> "percent"
			CellFormat.TIME -> "time"
		}

		/**
		 * The stored spelling of a [SortDir].
		 *
		 * Deliberately not TablePayload's toWireToken, which spells the same two values for
		 * the render bridge. That one may change with the page; this one is a contract with
		 * files already on the user's disk.
		 */
		private fun dirToken(dir: SortDir): String = when (dir) {
			SortDir.ASC -> "asc"
			SortDir.DESC -> "desc"
		}

		/** Width for a hand-written column that gives none. */
		private const val DEFAULT_WIDTH = 120

		private const val KEY_SCHEMA_VERSION = "schemaVersion"
		private const val KEY_ACTIVE_VIEW_ID = "activeViewId"
		private const val KEY_VIEWS = "views"
		private const val KEY_ID = "id"
		private const val KEY_NAME = "name"
		private const val KEY_FILTER_TO_CURRENT_DECK = "filterToCurrentDeck"
		private const val KEY_COLLAPSE_DUPLICATES_ON = "collapseDuplicatesOn"
		private const val KEY_HIGHLIGHT_EVERY = "highlightEvery"
		private const val KEY_DEFAULT_SORT = "defaultSort"
		private const val KEY_COLUMN = "column"
		private const val KEY_DIR = "dir"
		private const val KEY_COLUMNS = "columns"
		private const val KEY_TITLE = "title"
		private const val KEY_WIDTH = "width"
		private const val KEY_VISIBLE = "visible"
		private const val KEY_FROZEN = "frozen"
		private const val KEY_FORMAT = "format"
		private const val KEY_AGGREGATE = "aggregate"
		private const val KEY_SOURCE = "source"
		private const val KEY_LIMIT = "limit"
		private const val KEY_PARTITION = "partition"
		private const val KEY_FORMULA = "formula"
		private const val KEY_FORMULA_ERROR = "formulaError"
		private const val KEY_MODE = "mode"
		private const val KEY_BY = "by"
		private const val KEY_SIZE = "size"
		private const val MODE_GROUP = "group"
		private const val MODE_BUCKET = "bucket"
		private const val MODE_ROLLING = "rolling"
	}
}
