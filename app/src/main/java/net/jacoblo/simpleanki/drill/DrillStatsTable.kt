/*
 * One drill's stored runs, rendered as the table the stats screen shows.
 *
 * The whole point of producing a [RenderedTable] is that nothing in the table stack has to
 * learn about drills: TableWebView takes a finished table and knows nothing about where its
 * rows came from, so the drill stats screens reuse the Tabulator page unchanged.
 *
 * A separate object from [TableEngine] rather than a mode of it. TableEngine is wired to
 * HistoryEntry from its base columns down through every comparator, and these eight columns
 * are FIXED - they appear in no views.json, take no column sheet, and accept no computed
 * column - so there is no view for it to render and nothing for the two to share but the
 * value types and the dash. The small duplication below is the price of that, and it is paid
 * knowingly: widening TableEngine to two row types would put a branch in every one of its
 * steps, most of which a drill run has no answer for.
 *
 * Free of Android imports on purpose, like the rest of the table stack, so every rule here is
 * asserted by a JVM test with no emulator. No clock either: the only time involved is the
 * timestamp already stored on each run, rendered in a zone the caller supplies.
 */
package net.jacoblo.simpleanki.drill

import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.table.RenderedColumn
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.TableEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DrillStatsTable {

	const val ID_INDEX = "#"
	const val ID_WHEN = "When"
	const val ID_TIME = "Time"
	const val ID_COUNT = "Count"
	const val ID_RIGHT = "Right"
	const val ID_WRONG = "Wrong"
	const val ID_ACCURACY = "Accuracy"
	const val ID_SEC_PER_ITEM = "Sec/Item"

	/**
	 * Newest run first, which is the run the user just finished and the one they came to the
	 * screen to look at.
	 *
	 * Also the fallback whenever a requested sort names a column that cannot be ordered, so
	 * the table is never left in a state with no sort at all.
	 */
	val DEFAULT_SORT = SortSpec(ID_WHEN, SortDir.DESC)

	/**
	 * The runs in display order for [sort].
	 *
	 * Public because a row tap arrives from the page as a display INDEX and has to be mapped
	 * back to the run it stands for, in order to reopen that run's grid. [render] calls this
	 * rather than ordering its rows itself, so the two orderings are the same list by
	 * construction: two independent sorts would agree under the default and diverge the first
	 * time the user tapped a header, and the symptom would be a tap opening the wrong run.
	 *
	 * A sort naming an unorderable column - [ID_INDEX], or an id from nowhere - falls back to
	 * [DEFAULT_SORT], matching what [render] reports as the applied sort.
	 *
	 * Sorted twice, the way TableEngine.render does it, and the pre-sort is not decoration.
	 * sortedWith is stable, so runs tied on the sorted column keep the order they arrive in -
	 * and the runs file is held OLDEST first, so sorting the caller's list directly would
	 * leave every tie ascending by date. The most common tie is the whole table: a Poker run
	 * is always a full deck, so tapping Count on the Poker stats screen ties all 52-item rows
	 * at once and would flip the entire table to oldest-first, which reads to the user as
	 * "it sorted by date ascending" rather than as a tie. Numbers ties the same way whenever
	 * the count has not been changed, and Right and Wrong tie constantly. Ties keep the base
	 * order instead, which is newest first, matching what [DEFAULT_SORT] promises.
	 */
	fun order(runs: List<DrillRun>, sort: SortSpec): List<DrillRun> =
		runs.sortedByDescending { it.startedAt }.sortedWith(comparatorFor(sort))

	/**
	 * Every stored run of [kind] as one finished table.
	 *
	 * [highlightEvery] is the caller's TableSettings value, passed through rather than read
	 * here, for the same reason [zone] is: this object touches neither settings nor the clock.
	 *
	 * An empty [runs] renders the eight columns with no rows. A drill nobody has run yet is a
	 * normal state, not an error, and an empty table still has to draw its headers.
	 */
	fun render(
		runs: List<DrillRun>,
		kind: DrillKind,
		sort: SortSpec,
		highlightEvery: Int,
		zone: ZoneId = ZoneId.systemDefault()
	): RenderedTable {
		val ordered = order(runs, sort)
		// The display number is assigned HERE, from the position in the ordered list, and
		// never carried on the run. Numbering before the sort would number storage order, so
		// "#" would read 3, 1, 2 down a table sorted on anything but the default - and worse,
		// it would look like a stable row identity that it is not.
		val rows = ordered.mapIndexed { index, run ->
			COLUMNS.map { column -> column.cell(run, index + 1, zone) }
		}
		return RenderedTable(
			viewId = viewId(kind),
			// The sort actually applied, not the one asked for. The page draws its sort
			// arrow from this, so reporting the request would leave an arrow on a column the
			// rows are not in the order of.
			sort = resolveSort(sort),
			columns = COLUMNS.map { it.rendered },
			rows = rows,
			highlightEvery = highlightEvery,
			visibleRowCount = rows.size,
			// Nothing here can go wrong the way a view can: the columns are fixed, so there
			// is no unknown column id and no formula to fail.
			warnings = emptyList(),
			// The same fixed columns seen from the page's side. With no views.json behind
			// them there is nowhere for the header menu's Hide, Freeze/Unfreeze or two Moves
			// to persist, so each would revert on the next render - and Unfreeze would do
			// lasting damage before it did, for the reason [COLUMNS] gives about the frozen
			// pair. Decided here and not by the screen that hosts the table: [render] is what
			// FIXES these columns, so it is the only place that knows the four have nowhere
			// to go, and no caller of it could sensibly ask for true. Sorting is untouched -
			// see RenderedTable.viewEditable, which spells out the whole of what is suppressed.
			viewEditable = false
		)
	}

	/**
	 * The sort a tap on [columnId] produces, given the sort [current]ly applied.
	 *
	 * Tapping a column sorts it DESCENDING; tapping the one already sorted reverses it. That
	 * is the opposite of the history table's rule in TableGestures.nextSort, and deliberately
	 * so: every sortable column here is a quantity whose interesting end is its LARGEST, which
	 * is not always its best. The first tap on When means newest run, on Accuracy means best
	 * run, on Count means longest set - and on Sec/Item it means the run that dragged the most
	 * per item, which is the worst one and exactly the one worth looking at. Ascending-first
	 * would make the first tap on When show the user's very first drill.
	 *
	 * A column that cannot be ordered - [ID_INDEX], or an id the page reported that this table
	 * does not have - falls back to [DEFAULT_SORT] rather than to SortSpec(columnId, ...),
	 * which would leave the table pinned to a sort no comparator can honour and an arrow the
	 * next tap would merely flip.
	 */
	fun nextSort(current: SortSpec, columnId: String): SortSpec {
		if (!isSortable(columnId)) return DEFAULT_SORT
		if (current.column != columnId) return SortSpec(columnId, SortDir.DESC)
		return SortSpec(columnId, if (current.dir == SortDir.DESC) SortDir.ASC else SortDir.DESC)
	}

	// ---------------------------------------------------------------------------
	// Columns
	// ---------------------------------------------------------------------------

	/**
	 * One fixed column: how its header is drawn, how one cell is filled, and how it sorts.
	 *
	 * [comparator] is the single source of truth for sortability - [RenderedColumn.sortable]
	 * is derived from it below - so what the header offers and what [order] can actually
	 * deliver cannot drift into disagreeing.
	 *
	 * @param comparator null for [ID_INDEX] alone, which describes a row's position in the
	 *   finished table rather than anything about the run, and so has nothing to order by.
	 * @param cell takes the DISPLAY number rather than reading one off the run, since no run
	 *   has one; see the note in [render].
	 */
	private class StatsColumn(
		val id: String,
		width: Int,
		frozen: Boolean,
		type: ColumnType,
		val comparator: ((SortDir) -> Comparator<DrillRun>)?,
		val cell: (DrillRun, Int, ZoneId) -> String
	) {
		/**
		 * Title spelled from the id, because these tables have no views.json and so no place
		 * for a stored title the two could differ by.
		 */
		val rendered = RenderedColumn(
			id = id,
			title = id,
			width = width,
			frozen = frozen,
			type = type,
			sortable = comparator != null
		)
	}

	/**
	 * The eight columns, in display order. Defined in Kotlin, never in config.
	 *
	 * "#" is frozen as well as "When", and it has to be: Tabulator collects left-frozen
	 * columns by scanning from the left and switches to the RIGHT edge at the first unfrozen
	 * one, so freezing "When" alone would pin it to the far side of the table - the opposite
	 * of keeping it in view. The two together are 196dp of a row that totals 706dp, so the
	 * rest scrolls sideways under them.
	 *
	 * [ColumnType] reaches no renderer - toPayloadJson does not send it - so each is set to
	 * what the column honestly holds and nothing reads it back.
	 */
	private val COLUMNS: List<StatsColumn> = listOf(
		StatsColumn(
			id = ID_INDEX,
			width = 56,
			frozen = true,
			type = ColumnType.NUMBER,
			comparator = null,
			cell = { _, display, _ -> display.toString() }
		),
		StatsColumn(
			id = ID_WHEN,
			width = 140,
			frozen = true,
			type = ColumnType.TIME,
			comparator = ::whenComparator,
			cell = { run, _, zone -> whenText(run, zone) }
		),
		StatsColumn(
			id = ID_TIME,
			width = 80,
			frozen = false,
			type = ColumnType.TEXT,
			comparator = sortsBy { it.seconds },
			cell = { run, _, _ -> DrillOps.minutesSeconds(run.seconds) }
		),
		StatsColumn(
			id = ID_COUNT,
			width = 80,
			frozen = false,
			type = ColumnType.NUMBER,
			comparator = sortsBy { it.count },
			cell = { run, _, _ -> run.count.toString() }
		),
		StatsColumn(
			id = ID_RIGHT,
			width = 80,
			frozen = false,
			type = ColumnType.NUMBER,
			comparator = sortsBy { it.right },
			cell = { run, _, _ -> run.right.toString() }
		),
		StatsColumn(
			id = ID_WRONG,
			width = 80,
			frozen = false,
			type = ColumnType.NUMBER,
			comparator = sortsBy { it.wrong },
			cell = { run, _, _ -> run.wrong.toString() }
		),
		StatsColumn(
			id = ID_ACCURACY,
			width = 100,
			frozen = false,
			type = ColumnType.NUMBER,
			comparator = sortsBy { it.accuracy },
			cell = { run, _, _ -> accuracyText(run) }
		),
		StatsColumn(
			id = ID_SEC_PER_ITEM,
			width = 90,
			frozen = false,
			type = ColumnType.NUMBER,
			comparator = sortsBy { it.secondsPerItem },
			cell = { run, _, _ -> twoDecimals(run.secondsPerItem) }
		)
	)

	private val BY_ID: Map<String, StatsColumn> = COLUMNS.associateBy { it.id }

	// ---------------------------------------------------------------------------
	// Sorting
	// ---------------------------------------------------------------------------

	/** Whether a sort on [columnId] can be honoured, which is the one fact [nextSort] needs. */
	private fun isSortable(columnId: String): Boolean = BY_ID[columnId]?.comparator != null

	/** The sort [render] reports, which is [sort] only when [order] can actually apply it. */
	private fun resolveSort(sort: SortSpec): SortSpec =
		if (isSortable(sort.column)) sort else DEFAULT_SORT

	/**
	 * The comparator [order] uses, falling back to [DEFAULT_SORT]'s for an unorderable column.
	 *
	 * The fallback is spelled with [DEFAULT_SORT]'s own direction and not with [sort]'s, so
	 * that the rows land in the order [resolveSort] says they are in. Taking sort.dir here
	 * would sort a bad request's rows ascending while the header claimed When descending.
	 */
	private fun comparatorFor(sort: SortSpec): Comparator<DrillRun> {
		val factory = BY_ID[sort.column]?.comparator
			?: return whenComparator(DEFAULT_SORT.dir)
		return factory(sort.dir)
	}

	/**
	 * Named rather than inlined into [COLUMNS] so [comparatorFor]'s fallback can reach it too.
	 *
	 * A fun and not a val, which is the half worth saying: an object's properties initialise in
	 * declaration order, so a val declared below [COLUMNS] would still be null while COLUMNS
	 * was building itself and the When column would be handed a null comparator - reported
	 * unsortable, silently, for the one column the default sort names.
	 */
	private fun whenComparator(dir: SortDir): Comparator<DrillRun> = nullsLast(dir) { it.startedAt }

	/**
	 * Defers [nullsLast] until a direction is known, so a column can be defined by the value
	 * it sorts on alone and [nullsLast] stays the only place a null's position is decided.
	 */
	private fun <T : Comparable<T>> sortsBy(
		key: (DrillRun) -> T?
	): (SortDir) -> Comparator<DrillRun> = { dir -> nullsLast(dir, key) }

	/**
	 * Orders by [key], with null keys last in BOTH directions.
	 *
	 * Reversing the whole comparator instead would drag an empty run - accuracy and
	 * seconds-per-item are null for one, see DrillRun - to the top of a descending Accuracy
	 * sort, where a row whose cells read "-" would sit exactly where the user's best session
	 * belongs. A run with nothing to say has no claim on either end of the table.
	 *
	 * The same rule TableEngine.nullsLast applies, written out again rather than shared: that
	 * one is private to an object hard-wired to HistoryEntry, and prising it out to be reused
	 * would mean editing the engine to serve a table it does not render.
	 */
	private fun <T : Comparable<T>> nullsLast(
		dir: SortDir,
		key: (DrillRun) -> T?
	): Comparator<DrillRun> = Comparator { left, right ->
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

	// ---------------------------------------------------------------------------
	// Formatting
	// ---------------------------------------------------------------------------

	/**
	 * A COPY of the pattern TableEngine's When column uses, so the two tables date a row alike.
	 *
	 * A copy and not a reference: TableEngine's formatter is private to it. Nothing makes these
	 * two drift together, so changing either means changing both by hand.
	 */
	private val WHEN_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

	/**
	 * When [run] started, as the [ID_WHEN] column draws it.
	 *
	 * Internal rather than private because the run picker dates its lines with it too, and the
	 * picker exists to find a run the user already spotted in this table: the two have to read
	 * the same or the line and the row stop being recognisable as one run. A second hand-written
	 * copy of the pattern would agree today and drift the first time either was touched, which
	 * is the same argument this object makes for [order] serving both the rows and the row-tap
	 * mapping.
	 *
	 * The zone still arrives from the caller, so this object keeps no clock of its own; the
	 * default is here only so a caller with no opinion does not have to fetch one.
	 */
	internal fun whenText(run: DrillRun, zone: ZoneId = ZoneId.systemDefault()): String =
		WHEN_FORMATTER.withZone(zone).format(Instant.ofEpochMilli(run.startedAt))

	/**
	 * How [run] scored, as the [ID_ACCURACY] column draws it: a whole percentage, or the dash
	 * for a run with no accuracy at all.
	 *
	 * A fraction on the way in and a percentage on the way out, because rendering the stored 0.8
	 * would read as eight tenths of one percent under a header saying Accuracy.
	 *
	 * No decimal place, unlike TableEngine's PERCENT format: an accuracy over 52 cards moves in
	 * steps of about two points, so a tenth of a percent is a digit that carries no information
	 * and costs the column width to show.
	 *
	 * Internal for the reason [whenText] gives - the picker scores its lines with it too.
	 */
	internal fun accuracyText(run: DrillRun): String {
		val fraction = run.accuracy ?: return TableEngine.EMPTY_CELL
		return "%.0f%%".format(Locale.ROOT, fraction * 100)
	}

	/**
	 * Two decimals, or the dash. Private where [accuracyText] is internal, because [ID_SEC_PER_ITEM]
	 * is a column of this table and of nothing else.
	 */
	private fun twoDecimals(value: Float?): String =
		if (value == null) TableEngine.EMPTY_CELL else "%.2f".format(Locale.ROOT, value)

	/**
	 * The view id the payload carries, one per drill.
	 *
	 * Named after the drill and not shared, because TestMode's dump.json reports it and a
	 * single "drill_stats" would make a Numbers dump and a Poker one indistinguishable.
	 */
	private fun viewId(kind: DrillKind): String = kind.name.lowercase(Locale.ROOT) + "_stats"
}
