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
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.Partition
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

	/** Rendered for a null value, an undefined aggregate included. */
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
	 * Source value of a base column for aggregation, or NaN when it has none to give.
	 *
	 * NaN rather than 0.0 for a non-numeric source, and the difference is the whole point:
	 * nothing validates the pairing of an aggregate with a source column. ColumnSheet
	 * builds the two from independent pickers, so AVG over Question is two taps away and
	 * persists with no error anywhere, and [Aggregates] drops a NaN member where it would
	 * average a zero into the answer. So the type is checked here, at render time, rather
	 * than trusted from the parse that may never have happened.
	 *
	 * "#" is excluded by NAME rather than by type, and it is the one case a type check
	 * cannot catch: it is a NUMBER column, but its value is the display number, which is
	 * not assigned until after the collapse below the pivot. There is nothing to read
	 * here, so the 0 the sort comparators harmlessly pass would make every member 0.0 and
	 * hand back a plausible "0.00" precisely where a "-" belongs. That "#" is already
	 * unsortable is the same fact in a different place: it describes a row's position in
	 * the finished table, never its content.
	 *
	 * No zone: the one aggregable base column is the row's answer time, not calendar text.
	 */
	fun numericSource(entry: HistoryEntry, columnId: String): Double {
		if (columnId == ID_INDEX) return Double.NaN
		if (baseColumn(columnId)?.type != ColumnType.NUMBER) return Double.NaN
		return (rawValue(entry, columnId, 0) as? Number)?.toDouble() ?: Double.NaN
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

		// 3) Compute the computed columns, over every row that survived the filter and in
		// the order step 2 left them. This has to stay above the collapse below:
		// collapsing first would strip every partition of its members and make every
		// aggregate wrong.
		val pivot = Pivot(sorted, zone)
		val values = columns.map { column ->
			// Only a column with no base column of its own takes its value from here. A
			// base id carrying an aggregate too - reachable by hand-editing views.json -
			// renders from the record, so computing its pivot would buy a partition pass
			// per render whose answer cell() then throws away. An errored column is
			// skipped for its own reason: it renders a marker in every cell, and its
			// struct is the one that may be untrustworthy.
			if (column.base != null || column.rendered.error != null) {
				null
			} else {
				column.spec.computed?.let { pivot.values(it) }
			}
		}

		// 3b) Reorder by the computed sort, if that is what the sort is. This is the only
		// place it can go: not at step 2, where the values do not exist yet, and not below
		// the collapse, because bucket and rolling partition by sort position and ordering
		// by a value derived from sort position would be circular. The consequence is that
		// the aggregates were computed against the BASE order and the rows are then
		// reordered for display, which is the only non-circular reading of "sort by an
		// aggregate that is itself a function of the sort".
		val order = orderByComputed(view, activeSort, sorted.size, pivot)

		// 4) Collapse duplicates, keeping the first row of each key in the current order.
		// Positions rather than rows from here down, because the per-column value arrays
		// above are indexed by position in [sorted].
		val survivors = collapse(sorted, order, view, warnings, zone)

		// 5) Number the survivors 1..N, and 6) format every cell.
		val rows = survivors.mapIndexed { display, position ->
			columns.mapIndexed { column, resolved ->
				cell(sorted[position], resolved, display + 1, values[column]?.get(position), zone)
			}
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
		/** Null for an aggregate or formula column, whose values come from [Pivot]. */
		val base: BaseColumn?,
		val rendered: RenderedColumn
	)

	/** A spec is computed when it carries an aggregate or a formula. */
	private fun isComputed(spec: ColumnSpec): Boolean = spec.computed != null || spec.formula != null

	/**
	 * The struct a sort on this column would order by, or null when it cannot be sorted.
	 *
	 * The single rule [resolveSort] and [orderByComputed] both read, so that what the
	 * header reports sortable and what the pipeline can actually order by cannot drift.
	 * Being computed is not enough: [isComputed] is true for a formula alone, and a column
	 * with a formula but no struct - or with a struct beside a parse failure - has no
	 * values, so calling it sortable would draw a sort arrow that does nothing.
	 */
	private fun sortableSpec(spec: ColumnSpec): ComputedSpec? =
		if (spec.formulaError == null) spec.computed else null

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
	 * A hidden column still sorts, since visibility is presentation only - which is why
	 * this and [orderByComputed] both read the view's columns rather than the resolved
	 * ones, the resolved list being the visible columns alone. A computed column sorts
	 * too, in [orderByComputed] below the values it needs. An errored column does not
	 * sort, since every one of its cells is a marker.
	 */
	private fun resolveSort(view: TableView, sort: SortSpec, warnings: MutableList<String>): SortSpec {
		val base = baseColumn(sort.column)
		val computed = view.columns.firstOrNull { it.id == sort.column && isComputed(it) }
		val sortable = when {
			base != null -> base.sortable
			computed != null -> sortableSpec(computed) != null
			else -> {
				warnings.add("unknown sort column \"${sort.column}\", sorted by When descending")
				return FALLBACK_SORT
			}
		}
		if (sortable) return sort
		warnings.add("column \"${sort.column}\" cannot be sorted, sorted by When descending")
		return FALLBACK_SORT
	}

	/** Leaves [rows] in their base order when the sort names a computed column. */
	private fun sortRows(rows: List<HistoryEntry>, sort: SortSpec, zone: ZoneId): List<HistoryEntry> {
		val column = baseColumn(sort.column) ?: return rows
		return rows.sortedWith(comparatorFor(column, sort.dir, zone))
	}

	/**
	 * Row positions in display order: the identity when the sort is on a base column,
	 * since [sortRows] has already applied it, and the computed order otherwise.
	 *
	 * Positions rather than rows because the value arrays are indexed by the position a
	 * row held in the base order, and reordering the rows must not lose that mapping.
	 * sortedWith is stable, so rows tied on the aggregate - every row of one group, for a
	 * group pivot - keep the order step 2 gave them.
	 */
	private fun orderByComputed(
		view: TableView,
		sort: SortSpec,
		rowCount: Int,
		pivot: Pivot
	): List<Int> {
		val positions = List(rowCount) { it }
		if (baseColumn(sort.column) != null) return positions
		val spec = view.columns
			.firstOrNull { it.id == sort.column && isComputed(it) }
			?.let { sortableSpec(it) } ?: return positions
		val values = pivot.values(spec)
		return positions.sortedWith(nullsLast(sort.dir) { position: Int -> values[position] })
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
			ColumnType.TEXT -> nullsLast(dir) { entry: HistoryEntry ->
				(rawValue(entry, column.id, 0, zone) as? String)?.lowercase(Locale.ROOT)
			}
			ColumnType.NUMBER -> nullsLast(dir) { entry: HistoryEntry ->
				(rawValue(entry, column.id, 0, zone) as? Number)?.toDouble()
			}
			ColumnType.TIME -> nullsLast(dir) { entry: HistoryEntry ->
				(rawValue(entry, column.id, 0, zone) as? Number)?.toLong()
			}
			ColumnType.BOOL -> nullsLast(dir) { entry: HistoryEntry ->
				rawValue(entry, column.id, 0, zone) as? Boolean
			}
		}

	/**
	 * Orders by [key], with null keys last in BOTH directions.
	 *
	 * Reversing the whole comparator instead would drag a timed-out row to the top of a
	 * descending Seconds sort, where it would read as the slowest attempt. A "-" cell of a
	 * computed column sorts last for exactly the same reason.
	 *
	 * Generic in the element as well as the key so that [orderByComputed] can share it:
	 * that one orders row POSITIONS, and a key of `(HistoryEntry) -> T?` cannot express a
	 * lookup into an array indexed by position.
	 */
	private fun <S, T : Comparable<T>> nullsLast(
		dir: SortDir,
		key: (S) -> T?
	): Comparator<S> = Comparator { left, right ->
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
		order: List<Int>,
		view: TableView,
		warnings: MutableList<String>,
		zone: ZoneId
	): List<Int> {
		val columnId = view.collapseDuplicatesOn ?: return order
		if (baseColumn(columnId) == null) {
			// A computed column is a real column that simply cannot be a collapse key: its
			// value is a property of a partition rather than of a row, and it does not
			// even exist until after the sort that this step follows. Calling it unknown
			// would send the user hunting for a typo that is not there.
			val computed = view.columns.any { it.id == columnId && isComputed(it) }
			val kind = if (computed) "computed" else "unknown"
			warnings.add("cannot collapse duplicates on $kind column \"$columnId\"")
			return order
		}
		// Keyed on the display index rather than the underlying position, so that "#"
		// still keys every row to its own number and folds nothing.
		return order.withIndex()
			.distinctBy { (index, position) -> rawValue(rows[position], columnId, index, zone) }
			.map { it.value }
	}

	private fun cell(
		entry: HistoryEntry,
		column: ResolvedColumn,
		displayIndex: Int,
		computed: Double?,
		zone: ZoneId
	): String {
		if (column.rendered.error != null) return ERROR_CELL
		// TWO_DP by default: a computed column has no base column to take a format from,
		// and every aggregate but COUNT and ACCURACY answers in the units of its source.
		val base = column.base
			?: return format(computed, column.spec.format ?: CellFormat.TWO_DP, zone)
		val cellFormat = column.spec.format ?: base.format
		return format(rawValue(entry, base.id, displayIndex, zone), cellFormat, zone)
	}

	private fun decimals(value: Any, places: Int): String {
		val number = value as? Number ?: return value.toString()
		return "%.${places}f".format(Locale.ROOT, number.toDouble())
	}
}

/** Cache key so two columns with identical partitioning share one partition pass. */
private data class PartitionKey(val partition: Partition, val limit: Int)

/**
 * The computed columns of ONE render, as one value per row.
 *
 * Two caches, both dead at the end of the render that made them. [partitions] means two
 * columns partitioned the same way cost one pass over the rows - the stats view has three
 * columns keyed group:Question, last:10 and two more keyed group:Question, so its five
 * aggregates cost two passes. [byColumn] means a column that is both displayed and sorted
 * on is aggregated once rather than twice.
 *
 * A top-level private class rather than a nested one so that its calls back into
 * [TableEngine] read as the deliberate collaboration they are.
 *
 * @param rows already filtered and sorted; position means sort position
 */
private class Pivot(private val rows: List<HistoryEntry>, private val zone: ZoneId) {

	private val partitions = HashMap<PartitionKey, PartitionResult>()
	private val byColumn = HashMap<ComputedSpec, Array<Double?>>()

	/**
	 * One value per row of [rows], in that order, null where the aggregate is undefined.
	 *
	 * Computed once per PARTITION and broadcast to its rows, never once per row: a group
	 * pivot recomputed per row would be quadratic, and returning partitions rather than a
	 * member list per row is what MemberSelectors exists to make possible.
	 */
	fun values(spec: ComputedSpec): Array<Double?> = byColumn.getOrPut(spec) {
		val result = partitionFor(spec)
		val perPartition = arrayOfNulls<Double>(result.membersOfPartition.size)
		for (id in result.membersOfPartition.indices) {
			val members = result.membersOfPartition[id]
			perPartition[id] = Aggregates.compute(
				spec.aggregate,
				DoubleArray(members.size) { TableEngine.numericSource(rows[members[it]], spec.source) },
				BooleanArray(members.size) { rows[members[it]].timedOut }
			)
		}
		Array(rows.size) { perPartition[result.partitionOfRow[it]] }
	}

	private fun partitionFor(spec: ComputedSpec): PartitionResult {
		val partition = capped(spec.partition)
		// A limit bounds a group only - forPartition ignores it for a bucket and a rolling
		// window, which are bounded by their own size - so carrying it in the key would
		// split one pass into two for a pair of bucket columns differing in nothing the
		// selector reads. Normalised once and used for both the key and the pass below, so
		// the two cannot disagree about what was cached.
		val limit = if (partition is Partition.Group) spec.limit else 0
		return partitions.getOrPut(PartitionKey(partition, limit)) {
			// Any column can be a group key, Date and Time included, so the key is the
			// rendered raw value as text. A row a column has no value for - Seconds on a
			// timed-out attempt - keys to the empty string and groups with its like.
			MemberSelectors.forPartition(partition, limit) { entry, columnId ->
				TableEngine.rawValue(entry, columnId, 0, zone)?.toString() ?: ""
			}.partition(rows)
		}
	}

	/**
	 * A rolling window no wider than the table itself.
	 *
	 * "rolling:999999" is the documented spelling of a running cumulative, so a size far
	 * past the row count is an idiom a user is meant to reach for rather than a
	 * pathological input. Clamping changes no member set - a window already stops at the
	 * top of the table, so every size at or above the row count selects the same rows -
	 * but it does collapse all of those spellings onto one [PartitionKey], so a view
	 * carrying both "rolling:999999" and a rolling window the size of the table pays for
	 * one pass rather than two.
	 */
	private fun capped(partition: Partition): Partition =
		if (partition is Partition.Rolling && partition.size > rows.size) {
			// Not below 1: MemberSelectors would clamp it there anyway, and going through
			// zero would make an empty table's key depend on the size it started from.
			Partition.Rolling(if (rows.isEmpty()) 1 else rows.size)
		} else {
			partition
		}
}
