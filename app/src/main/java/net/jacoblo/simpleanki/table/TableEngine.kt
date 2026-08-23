/*
 * The table render pipeline: raw history plus a view definition in, one finished table out.
 *
 * Free of Android imports on purpose, so the whole pipeline is exercised by JVM tests with
 * no emulator. Nothing here touches the filesystem, and nothing reads the clock - the only
 * time involved is the timestamp already stored on each record, rendered in a zone the
 * caller supplies.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.CellFormat
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Definition of one base column: its type and how to read and format it. */
data class BaseColumn(
	val id: String,
	val type: ColumnType,
	val format: CellFormat,
	val sortable: Boolean
)

object TableEngine {

	const val ID_INDEX = "#"
	const val ID_WHEN = "When"
	const val ID_DATE = "Date"
	const val ID_TIME = "Time"
	const val ID_QUESTION = "Question"
	const val ID_ANSWER = "Answer"
	const val ID_SECONDS = "Seconds"
	const val ID_TIMED_OUT = "TimedOut"

	/** Rendered for a null value, which includes every cell of a not-yet-computed column. */
	const val EMPTY_CELL = "-"

	/** Rendered in every cell of a column whose formula failed. */
	const val ERROR_CELL = "#ERR"

	/** Rendered by a BOOL cell that is true. A false one renders the empty string. */
	const val TRUE_CELL = "x"

	/** Applied whenever the requested sort names a column that cannot be sorted. */
	val FALLBACK_SORT = SortSpec(ID_WHEN, SortDir.DESC)

	/** The eight base columns. Defined in Kotlin, never in config. */
	val BASE_COLUMNS: List<BaseColumn> = listOf(
		BaseColumn(ID_INDEX,     ColumnType.NUMBER, CellFormat.INT,    sortable = false),
		BaseColumn(ID_WHEN,      ColumnType.TIME,   CellFormat.TIME,   sortable = true),
		BaseColumn(ID_DATE,      ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn(ID_TIME,      ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn(ID_QUESTION,  ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn(ID_ANSWER,    ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn(ID_SECONDS,   ColumnType.NUMBER, CellFormat.TWO_DP, sortable = true),
		BaseColumn(ID_TIMED_OUT, ColumnType.BOOL,   CellFormat.TEXT,   sortable = true)
	)

	/**
	 * The eight base column ids, and the only ids a formula may name.
	 *
	 * Derived here rather than rebuilt at each call site so that the parser's gate and
	 * anything asserting on it cannot drift apart. Widening this set is what would let a
	 * computed column reference another computed column, which MemberSelectors cannot
	 * read - it takes a group key straight off a HistoryEntry.
	 */
	val BASE_COLUMN_IDS: Set<String> = BASE_COLUMNS.map { it.id }.toSet()

	private val BASE_BY_ID: Map<String, BaseColumn> = BASE_COLUMNS.associateBy { it.id }

	private val WHEN_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)
	private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
	private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

	fun baseColumn(id: String): BaseColumn? = BASE_BY_ID[id]

	/**
	 * Raw comparable value of a base column for one row, used for sorting.
	 *
	 * Returns null for a timed-out row on a numeric column so that such rows can be forced
	 * last in both sort directions, and for any id that is not a base column.
	 *
	 * [zone] only matters for Date and Time, whose values are calendar text derived from
	 * the record's timestamp. It defaults to the system zone so callers that do not care
	 * can ignore it; tests pin it so their assertions do not depend on the machine.
	 */
	fun rawValue(
		entry: HistoryEntry,
		columnId: String,
		displayIndex: Int,
		zone: ZoneId = ZoneId.systemDefault()
	): Comparable<*>? = when (columnId) {
		ID_INDEX -> displayIndex
		ID_WHEN -> entry.timestamp
		ID_DATE -> DATE_FORMATTER.withZone(zone).format(Instant.ofEpochMilli(entry.timestamp))
		ID_TIME -> TIME_FORMATTER.withZone(zone).format(Instant.ofEpochMilli(entry.timestamp))
		ID_QUESTION -> entry.question
		ID_ANSWER -> entry.answer
		ID_SECONDS -> if (entry.timedOut) null else entry.timeTaken
		ID_TIMED_OUT -> entry.timedOut
		else -> null
	}

	/**
	 * Formats one value for display. Null renders "-".
	 *
	 * A Boolean under [CellFormat.TEXT] renders "x" or the empty string, which is how the
	 * TimedOut column is spelled. PERCENT appends a percent sign without rescaling, so its
	 * input is already a percentage rather than a fraction. A value whose type does not
	 * suit the requested format falls back to its own toString rather than throwing.
	 */
	fun format(value: Any?, format: CellFormat, zone: ZoneId = ZoneId.systemDefault()): String {
		if (value == null) return EMPTY_CELL
		return when (format) {
			CellFormat.TEXT -> if (value is Boolean) {
				if (value) TRUE_CELL else ""
			} else {
				value.toString()
			}
			CellFormat.INT -> decimals(value, 0)
			CellFormat.ONE_DP -> decimals(value, 1)
			CellFormat.TWO_DP -> decimals(value, 2)
			CellFormat.PERCENT -> if (value is Number) decimals(value, 1) + "%" else value.toString()
			CellFormat.TIME -> if (value is Number) {
				WHEN_FORMATTER.withZone(zone).format(Instant.ofEpochMilli(value.toLong()))
			} else {
				value.toString()
			}
		}
	}

	/**
	 * The whole pipeline. Pure: no Android imports, no file access, no clock.
	 *
	 * [sort] is the sort the user currently has applied, which starts out as the view's
	 * default. [zone] renders the calendar columns; pin it in tests.
	 */
	fun render(
		history: List<HistoryEntry>,
		deckQuestions: Set<String>,
		view: TableView,
		sort: SortSpec = view.defaultSort,
		zone: ZoneId = ZoneId.systemDefault()
	): RenderedTable {
		val warnings = ArrayList<String>()
		val columns = resolveColumns(view, warnings)
		val activeSort = resolveSort(view, sort, warnings)

		// 1) Filter to the current deck.
		val filtered = if (view.filterToCurrentDeck) {
			history.filter { deckQuestions.contains(it.question) }
		} else {
			history
		}

		// 2) Sort. The base order is When descending and sortedWith is stable, so rows
		// tied on the sorted column stay newest first - which is what makes the survivor
		// of the collapse below the most recent attempt.
		val sorted = sortRows(filtered.sortedByDescending { it.timestamp }, activeSort, zone)

		// 3) Compute computed columns. Deliberately a no-op; Task 13 plugs the pivot
		// engine in HERE, and it has to stay above the collapse below. Collapsing first
		// would strip every partition of its members and make every aggregate wrong.
		//
		// A sort on a computed column also runs here, after the values exist and still
		// above the collapse. It cannot run at step 2 because the values do not exist
		// yet, and it cannot run below the collapse because bucket and rolling
		// partition by sort position.

		// 4) Collapse duplicates, keeping the first row of each key in the current order.
		val survivors = collapse(sorted, view.collapseDuplicatesOn, warnings, zone)

		// 5) Number the survivors 1..N, and 6) format every cell.
		val rows = survivors.mapIndexed { index, entry ->
			columns.map { column -> cell(entry, column, index + 1, zone) }
		}

		return RenderedTable(
			viewId = view.id,
			sort = activeSort,
			columns = columns.map { it.rendered },
			rows = rows,
			highlightEvery = view.highlightEvery,
			visibleRowCount = rows.size,
			warnings = warnings.toList()
		)
	}

	/** A column that survived validation, paired with the pieces needed to fill its cells. */
	private data class ResolvedColumn(
		val spec: ColumnSpec,
		/** Null for an aggregate or formula column, whose values Task 13 will supply. */
		val base: BaseColumn?,
		val rendered: RenderedColumn
	)

	/** A spec is computed when it carries an aggregate or a formula. */
	private fun isComputed(spec: ColumnSpec): Boolean = spec.computed != null || spec.formula != null

	/**
	 * Validates every spec and keeps the visible ones, in view order.
	 *
	 * A spec is valid when it names a base column or is computed; anything else is a config
	 * typo, so it is dropped with a warning rather than throwing. Hidden specs are validated
	 * too, because the column sheet lists them as well.
	 *
	 * A spec whose formula failed to parse keeps its place in the table so the user can see
	 * which column broke: it renders [ERROR_CELL] throughout, cannot be sorted, and repeats
	 * its message in [warnings], which is the only part of this the column sheet reads.
	 */
	private fun resolveColumns(view: TableView, warnings: MutableList<String>): List<ResolvedColumn> {
		val resolved = ArrayList<ResolvedColumn>(view.columns.size)
		for (spec in view.columns) {
			val base = baseColumn(spec.id)
			if (base == null && !isComputed(spec)) {
				warnings.add("unknown column \"${spec.id}\"")
				continue
			}
			val error = spec.formulaError
			if (error != null) warnings.add("column \"${spec.id}\" failed: $error")
			if (!spec.visible) continue
			resolved.add(
				ResolvedColumn(
					spec = spec,
					base = base,
					rendered = RenderedColumn(
						id = spec.id,
						title = spec.title,
						width = spec.width,
						frozen = spec.frozen,
						type = base?.type ?: ColumnType.NUMBER,
						sortable = error == null && (base?.sortable ?: true),
						error = error
					)
				)
			)
		}
		return resolved
	}

	/**
	 * The sort to actually apply, falling back to [FALLBACK_SORT] when the requested column
	 * is unknown or unsortable.
	 *
	 * A hidden column still sorts, since visibility is presentation only. A computed column
	 * sorts too, and until Task 13 fills it every key is null, which leaves the base order
	 * untouched. An errored column does not sort, since every one of its cells is a marker.
	 */
	private fun resolveSort(view: TableView, sort: SortSpec, warnings: MutableList<String>): SortSpec {
		val base = baseColumn(sort.column)
		val computed = view.columns.firstOrNull { it.id == sort.column && isComputed(it) }
		val sortable = when {
			base != null -> base.sortable
			computed != null -> computed.formulaError == null
			else -> {
				warnings.add("unknown sort column \"${sort.column}\", sorted by When descending")
				return FALLBACK_SORT
			}
		}
		if (sortable) return sort
		warnings.add("column \"${sort.column}\" cannot be sorted, sorted by When descending")
		return FALLBACK_SORT
	}

	/** Leaves [rows] in their base order when the sort names a column with no values yet. */
	private fun sortRows(rows: List<HistoryEntry>, sort: SortSpec, zone: ZoneId): List<HistoryEntry> {
		val column = baseColumn(sort.column) ?: return rows
		return rows.sortedWith(comparatorFor(column, sort.dir, zone))
	}

	/**
	 * Comparator for one column, typed by [ColumnType] rather than by comparing the
	 * `Comparable<*>` values directly - that cannot be done without an unchecked cast, and
	 * TEXT needs a key of its own anyway because it compares case-insensitively.
	 */
	private fun comparatorFor(
		column: BaseColumn,
		dir: SortDir,
		zone: ZoneId
	): Comparator<HistoryEntry> =
		when (column.type) {
			ColumnType.TEXT -> nullsLast(dir) { entry ->
				(rawValue(entry, column.id, 0, zone) as? String)?.lowercase(Locale.ROOT)
			}
			ColumnType.NUMBER -> nullsLast(dir) { entry ->
				(rawValue(entry, column.id, 0, zone) as? Number)?.toDouble()
			}
			ColumnType.TIME -> nullsLast(dir) { entry ->
				(rawValue(entry, column.id, 0, zone) as? Number)?.toLong()
			}
			ColumnType.BOOL -> nullsLast(dir) { entry ->
				rawValue(entry, column.id, 0, zone) as? Boolean
			}
		}

	/**
	 * Orders by [key], with null keys last in BOTH directions.
	 *
	 * Reversing the whole comparator instead would drag a timed-out row to the top of a
	 * descending Seconds sort, where it would read as the slowest attempt.
	 */
	private fun <T : Comparable<T>> nullsLast(
		dir: SortDir,
		key: (HistoryEntry) -> T?
	): Comparator<HistoryEntry> = Comparator { left, right ->
		val a = key(left)
		val b = key(right)
		when {
			a == null && b == null -> 0
			a == null -> 1
			b == null -> -1
			dir == SortDir.ASC -> a.compareTo(b)
			else -> b.compareTo(a)
		}
	}

	/**
	 * Keeps the first row of each key in the given order, the key being the column's raw
	 * value, so rows that would display the same value fold together.
	 *
	 * Two consequences of that single rule need no guard of their own: on Seconds every
	 * timed-out row keys to null and the lot folds into one, and on "#" every row keys to
	 * its own number and nothing folds. An unusable key collapses nothing.
	 */
	private fun collapse(
		rows: List<HistoryEntry>,
		columnId: String?,
		warnings: MutableList<String>,
		zone: ZoneId
	): List<HistoryEntry> {
		if (columnId == null) return rows
		if (baseColumn(columnId) == null) {
			warnings.add("cannot collapse duplicates on unknown column \"$columnId\"")
			return rows
		}
		return rows.withIndex()
			.distinctBy { (index, entry) -> rawValue(entry, columnId, index, zone) }
			.map { it.value }
	}

	private fun cell(
		entry: HistoryEntry,
		column: ResolvedColumn,
		displayIndex: Int,
		zone: ZoneId
	): String {
		if (column.rendered.error != null) return ERROR_CELL
		val base = column.base ?: return EMPTY_CELL
		val cellFormat = column.spec.format ?: base.format
		return format(rawValue(entry, base.id, displayIndex, zone), cellFormat, zone)
	}

	private fun decimals(value: Any, places: Int): String {
		val number = value as? Number ?: return value.toString()
		return "%.${places}f".format(Locale.ROOT, number.toDouble())
	}
}
