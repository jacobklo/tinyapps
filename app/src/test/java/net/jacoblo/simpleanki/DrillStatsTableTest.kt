package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.ItemStatus
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillStatsTable
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.TableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Covers the drill stats table end to end: what it renders, in what order, and what a header
 * tap does to that order.
 *
 * Every case touching the When column pins the zone, so no assertion here depends on the
 * machine the tests run on. The fixture instant is one UTC and Tokyo disagree on the hour
 * about, so a case that forgot to pin one would fail rather than pass by luck.
 *
 * Item values are two-digit Numbers cells, which are ASCII. No card is built anywhere here,
 * because a stats row reports a run's FIGURES and never one of its values - so the fixtures
 * vary the marks, which the figures are derived from, and never the values.
 */
class DrillStatsTableTest {

	private val utc = ZoneId.of("UTC")
	private val tokyo = ZoneId.of("Asia/Tokyo")

	/** 2024-03-05T14:07:09Z, an hour UTC and Tokyo disagree about inside the same day. */
	private val fixedMillis = Instant.parse("2024-03-05T14:07:09Z").toEpochMilli()

	// ---------------------------------------------------------------------------
	// Shape
	// ---------------------------------------------------------------------------

	@Test
	fun theEightColumnsAreFixedAndInOrder() {
		val table = render(emptyList())

		assertEquals(
			listOf("#", "When", "Time", "Count", "Right", "Wrong", "Accuracy", "Sec/Item"),
			table.columns.map { it.id }
		)
		// Title spelled from the id: no views.json stands behind this table, so there is no
		// stored title for the two to drift apart by.
		assertEquals(table.columns.map { it.id }, table.columns.map { it.title })
	}

	@Test
	fun anEmptyRunListStillDrawsItsHeaders() {
		val table = render(emptyList())

		// A drill nobody has run yet is a normal state and not an error. A table with no
		// columns would draw as a blank page the user cannot tell from a broken one.
		assertEquals(8, table.columns.size)
		assertTrue(table.rows.isEmpty())
		assertEquals(0, table.visibleRowCount)
		assertEquals(DrillStatsTable.DEFAULT_SORT, table.sort)
	}

	@Test
	fun theIndexColumnIsNotSortableAndTheSevenOthersAre() {
		val sortable = render(emptyList()).columns.associate { it.id to it.sortable }

		// "#" describes a row's position in the finished table rather than anything about the
		// run behind it, so a sort on it has nothing to order by. Asserted beside the count of
		// the seven that do sort, so this cannot pass for a table reporting nothing sortable.
		assertEquals(false, sortable["#"])
		assertEquals(7, sortable.values.count { it })
	}

	@Test
	fun whenIsFrozenSoItSurvivesASidewaysScroll() {
		val frozen = render(emptyList()).columns.filter { it.frozen }.map { it.id }

		// "#" is frozen too, and has to be: Tabulator gathers left-frozen columns by scanning
		// from the left and switches to the RIGHT edge at the first unfrozen one, so freezing
		// "When" alone would pin it to the far side of the table - the opposite of the intent.
		assertEquals(listOf("#", "When"), frozen)
	}

	@Test
	fun theViewIdNamesTheDrill() {
		// TestMode's dump.json reports the view id, so one shared id would leave a Numbers
		// dump and a Poker one indistinguishable.
		assertEquals("numbers_stats", render(emptyList(), kind = DrillKind.NUMBERS).viewId)
		assertEquals("poker_stats", render(emptyList(), kind = DrillKind.POKER).viewId)
	}

	@Test
	fun highlightEveryIsPassedThrough() {
		assertEquals(7, render(emptyList(), highlightEvery = 7).highlightEvery)
	}

	// ---------------------------------------------------------------------------
	// Formatting
	// ---------------------------------------------------------------------------

	@Test
	fun everyCellOfARunIsFormattedForDisplay() {
		val run = DrillRun("solo", fixedMillis, seconds = 83.4f, items = marks(right = 4, wrong = 1))

		// One row, spelled out whole: 5 items, 4 of them right, 83.4 seconds. Accuracy reads
		// 80% and not the stored 0.8, and Sec/Item is 16.68 to two places.
		assertEquals(
			listOf(listOf("1", "03-05 14:07:09", "01:23", "5", "4", "1", "80%", "16.68")),
			render(listOf(run)).rows
		)
	}

	@Test
	fun theWhenColumnIsRenderedInTheZoneItIsGiven() {
		val run = DrillRun("solo", fixedMillis, 1f, marks(right = 1, wrong = 0))

		// Tokyo is UTC+9, so the same instant reads 23:07 there. Without a pinned zone the
		// suite would pass or fail on the machine's timezone rather than on the code.
		assertEquals("03-05 14:07:09", cell(render(listOf(run), zone = utc), 0, "When"))
		assertEquals("03-05 23:07:09", cell(render(listOf(run), zone = tokyo), 0, "When"))
	}

	@Test
	fun anUnscoredItemCountsAgainstAccuracyExactlyAsAWrongOneDoes() {
		val items = listOf(DrillItem("07", ItemStatus.RIGHT), DrillItem("42"), DrillItem("91"))
		val table = render(listOf(DrillRun("solo", fixedMillis, 30f, items)))

		// 1 of 3, not 1 of 1 - see DrillRun.accuracy. Right and Wrong are asserted alongside,
		// so the row still shows that two of the three cells were never checked at all.
		assertEquals("33%", cell(table, 0, "Accuracy"))
		assertEquals("3", cell(table, 0, "Count"))
		assertEquals("1", cell(table, 0, "Right"))
		assertEquals("0", cell(table, 0, "Wrong"))
	}

	@Test
	fun aRunWithNoItemsRendersDashesRatherThanZeroes() {
		val table = render(listOf(DrillRun("empty", fixedMillis, 12f, items = emptyList())))

		// Only a hand-edited file produces one. 0% is a legitimate accuracy - every item
		// marked wrong - so printing it here would make "no data" read as a disastrous
		// session; see DrillRun.accuracy. The columns that CAN answer still do.
		assertEquals(TableEngine.EMPTY_CELL, cell(table, 0, "Accuracy"))
		assertEquals(TableEngine.EMPTY_CELL, cell(table, 0, "Sec/Item"))
		assertEquals("0", cell(table, 0, "Count"))
		assertEquals("00:12", cell(table, 0, "Time"))
	}

	@Test
	fun aRunPastAnHourKeepsItsMinutesInsteadOfWrappingAtSixty() {
		val long = DrillRun("solo", fixedMillis, seconds = 3900f, items = marks(1, 0))

		// 3900s is 65 minutes and renders "65:00". A minutes-modulo-60 would render "05:00",
		// which does not look broken at all - it looks like a brisk five minute session, so
		// the one figure showing the user walked away mid-set would be hidden behind it.
		assertEquals("65:00", cell(render(listOf(long)), 0, "Time"))
	}

	@Test
	fun timeTruncatesToWholeSecondsTheWayARunningStopwatchReads() {
		val run = DrillRun("solo", fixedMillis, seconds = 119.9f, items = marks(1, 0))

		// 01:59, never the 02:00 that rounding would give: a timer the user watched stop at
		// 1:59 must not file the run as two minutes.
		assertEquals("01:59", cell(render(listOf(run)), 0, "Time"))
	}

	// ---------------------------------------------------------------------------
	// Ordering
	// ---------------------------------------------------------------------------

	@Test
	fun theDefaultSortIsNewestFirst() {
		assertEquals(SortSpec("When", SortDir.DESC), DrillStatsTable.DEFAULT_SORT)

		// The run the user just finished is the one they came to the screen to look at.
		assertEquals(
			listOf("03-05 14:07:13", "03-05 14:07:12", "03-05 14:07:11", "03-05 14:07:10"),
			column(render(fourRuns()), "When")
		)
	}

	@Test
	fun renderFillsItsRowsFromOrderUnderANonDefaultSort() {
		// Accuracy ascending puts the runs in b, c, a, d - which is neither the storage order
		// nor the default one. Under the default sort an off-by-one against the unsorted list
		// is invisible, which is the whole reason this case is not written on it.
		val sort = SortSpec("Accuracy", SortDir.ASC)
		val runs = fourRuns()
		val ordered = DrillStatsTable.order(runs, sort)
		val table = render(runs, sort = sort)

		assertEquals(listOf("b", "c", "a", "d"), ordered.map { it.id })
		assertNotEquals(runs.map { it.id }, ordered.map { it.id })
		// The invariant a row tap depends on: the run at display position i is the run whose
		// figures fill row i. A render that sorted its own rows would satisfy every other
		// assertion in this file and still open the wrong run when a row was tapped. Count,
		// Right and Wrong together, because each fixture run has its own three.
		assertEquals(runs.size, table.rows.size)
		ordered.forEachIndexed { index, run ->
			assertEquals(run.count.toString(), cell(table, index, "Count"))
			assertEquals(run.right.toString(), cell(table, index, "Right"))
			assertEquals(run.wrong.toString(), cell(table, index, "Wrong"))
		}
	}

	@Test
	fun theIndexColumnNumbersDisplayPositionAndNotStoragePosition() {
		val table = render(fourRuns(), sort = SortSpec("Accuracy", SortDir.ASC))

		// 1, 2, 3, 4 down the page whatever the sort. Numbering before the sort would carry
		// each run's position in the FILE into the table, so this column would read 2, 3, 1, 4
		// here and look like a stable row identity that it is not.
		assertEquals(listOf("1", "2", "3", "4"), column(table, "#"))
		assertEquals(listOf("b", "c", "a", "d"), rowIds(table))
	}

	@Test
	fun everySortableColumnOrdersByItsOwnValue() {
		val runs = fourRuns()

		// Six DIFFERENT orders over six columns, no two alike and none of them the storage
		// order, so no column can pass here by leaving the list alone or by sorting on some
		// other column's value. See [fourRuns] for why three runs cannot manage that.
		assertEquals(listOf("a", "b", "c", "d"), ids(runs, "When", SortDir.ASC))
		assertEquals(listOf("b", "a", "d", "c"), ids(runs, "Time", SortDir.ASC))
		assertEquals(listOf("a", "d", "b", "c"), ids(runs, "Count", SortDir.ASC))
		assertEquals(listOf("a", "b", "d", "c"), ids(runs, "Right", SortDir.ASC))
		assertEquals(listOf("a", "d", "c", "b"), ids(runs, "Wrong", SortDir.ASC))
		assertEquals(listOf("b", "c", "a", "d"), ids(runs, "Accuracy", SortDir.ASC))
		assertEquals(listOf("b", "c", "d", "a"), ids(runs, "Sec/Item", SortDir.ASC))
	}

	@Test
	fun descendingIsTheExactReverseOfAscending() {
		val runs = fourRuns()

		assertEquals(listOf("d", "a", "c", "b"), ids(runs, "Accuracy", SortDir.DESC))
		assertEquals(listOf("a", "d", "c", "b"), ids(runs, "Sec/Item", SortDir.DESC))
		assertEquals(listOf("d", "c", "b", "a"), ids(runs, "When", SortDir.DESC))
	}

	@Test
	fun aRunWithNothingToSaySortsLastInBothDirections() {
		val runs = fourRuns() + DrillRun("empty", fixedMillis + 9000L, 5f, items = emptyList())

		// Accuracy and Sec/Item are both null for an empty run. Reversing the whole comparator
		// for DESC instead of holding nulls last would put a row of dashes at the top of a
		// descending Accuracy sort - exactly where the user's best session belongs.
		assertEquals(listOf("b", "c", "a", "d", "empty"), ids(runs, "Accuracy", SortDir.ASC))
		assertEquals(listOf("d", "a", "c", "b", "empty"), ids(runs, "Accuracy", SortDir.DESC))
		assertEquals(listOf("b", "c", "d", "a", "empty"), ids(runs, "Sec/Item", SortDir.ASC))
		assertEquals(listOf("a", "d", "c", "b", "empty"), ids(runs, "Sec/Item", SortDir.DESC))
	}

	@Test
	fun runsTiedOnTheSortedColumnKeepTheOrderTheFileGaveThem() {
		val runs = listOf(
			run("first", fixedMillis + 1000L, seconds = 10f, right = 1, wrong = 1),
			run("second", fixedMillis + 2000L, seconds = 20f, right = 2, wrong = 2),
			run("third", fixedMillis + 3000L, seconds = 30f, right = 3, wrong = 3)
		)

		// All three score exactly 50%, so nothing but stability decides the order. An unstable
		// sort would reshuffle rows on a tap the user expects to change nothing about them.
		assertEquals(listOf("first", "second", "third"), ids(runs, "Accuracy", SortDir.ASC))
		assertEquals(listOf("first", "second", "third"), ids(runs, "Accuracy", SortDir.DESC))
	}

	@Test
	fun aSortOnAnUnorderableColumnFallsBackToTheDefault() {
		val runs = fourRuns()

		// "#" is a real column that simply cannot be sorted; "Nonsense" is no column at all.
		// Both have to leave the rows in an order the header can honestly describe.
		for (bad in listOf(SortSpec("#", SortDir.ASC), SortSpec("Nonsense", SortDir.ASC))) {
			val table = render(runs, sort = bad)
			// The APPLIED sort, not the requested one: the page draws its arrow from this, so
			// echoing the request would mark a column the rows are not in the order of.
			assertEquals(DrillStatsTable.DEFAULT_SORT, table.sort)
			// Newest first, and NOT the ascending direction that was asked for - the fallback
			// carries its own direction or the rows contradict the arrow above them.
			assertEquals(listOf("d", "c", "b", "a"), rowIds(table))
			assertEquals(
				listOf("d", "c", "b", "a"),
				DrillStatsTable.order(runs, bad).map { it.id }
			)
		}
	}

	// ---------------------------------------------------------------------------
	// nextSort
	// ---------------------------------------------------------------------------

	@Test
	fun tappingAFreshColumnSortsItDescending() {
		// Descending first, the opposite of the history table's rule: the interesting end of
		// every column here is its top, so the first tap on Accuracy means best run. Ascending
		// first would make the first tap on When show the user's very first drill ever.
		assertEquals(
			SortSpec("Accuracy", SortDir.DESC),
			DrillStatsTable.nextSort(SortSpec("When", SortDir.DESC), "Accuracy")
		)
		// From an ascending sort too, so the direction in force is never carried across.
		assertEquals(
			SortSpec("Accuracy", SortDir.DESC),
			DrillStatsTable.nextSort(SortSpec("When", SortDir.ASC), "Accuracy")
		)
	}

	@Test
	fun tappingTheSortedColumnReversesIt() {
		val once = DrillStatsTable.nextSort(SortSpec("When", SortDir.DESC), "Count")
		val twice = DrillStatsTable.nextSort(once, "Count")
		val thrice = DrillStatsTable.nextSort(twice, "Count")

		assertEquals(SortSpec("Count", SortDir.DESC), once)
		assertEquals(SortSpec("Count", SortDir.ASC), twice)
		// Back where it started, so repeated taps cycle rather than stick at one direction.
		assertEquals(SortSpec("Count", SortDir.DESC), thrice)
	}

	@Test
	fun tappingAnUnsortableOrUnknownColumnFallsBackToTheDefault() {
		val current = SortSpec("Accuracy", SortDir.ASC)
		// The fallback has to be a real change, or these two cases would also pass for a
		// nextSort that simply handed the current sort back.
		assertNotEquals(DrillStatsTable.DEFAULT_SORT, current)

		// SortSpec("#", ...) would leave the table pinned to a sort no comparator can honour,
		// showing an arrow the next tap would merely flip.
		assertEquals(DrillStatsTable.DEFAULT_SORT, DrillStatsTable.nextSort(current, "#"))
		assertEquals(DrillStatsTable.DEFAULT_SORT, DrillStatsTable.nextSort(current, "Nonsense"))
	}

	// ---------------------------------------------------------------------------
	// Fixtures
	// ---------------------------------------------------------------------------

	private fun render(
		runs: List<DrillRun>,
		kind: DrillKind = DrillKind.NUMBERS,
		sort: SortSpec = DrillStatsTable.DEFAULT_SORT,
		highlightEvery: Int = 5,
		zone: ZoneId = utc
	): RenderedTable = DrillStatsTable.render(runs, kind, sort, highlightEvery, zone)

	/** One cell, looked up by column id, so no assertion here depends on column positions. */
	private fun cell(table: RenderedTable, row: Int, columnId: String): String =
		table.rows[row][table.columns.indexOfFirst { it.id == columnId }]

	private fun column(table: RenderedTable, columnId: String): List<String> =
		table.rows.indices.map { cell(table, it, columnId) }

	/** The display order of [runs] under one sort, as run ids. */
	private fun ids(runs: List<DrillRun>, columnId: String, dir: SortDir): List<String> =
		DrillStatsTable.order(runs, SortSpec(columnId, dir)).map { it.id }

	/**
	 * Which run filled each rendered row, recovered from its Count cell.
	 *
	 * A row carries no run id, so it has to be traced back through a cell - and [fourRuns]
	 * gives each of its runs a different item count precisely so that one can do it.
	 */
	private fun rowIds(table: RenderedTable): List<String> = column(table, "Count").map {
		when (it) {
			"2" -> "a"
			"8" -> "b"
			"9" -> "c"
			"5" -> "d"
			else -> it
		}
	}

	/**
	 * Four runs, in storage order - oldest first, as the runs file holds them.
	 *
	 * Built so that each of the six sortable columns puts them in a DIFFERENT order, and none
	 * of those six is the storage order. That is what lets a sort assertion tell "ordered by
	 * this column" apart from "left exactly as it arrived" and from "ordered by some other
	 * column's value", and it is why there are four runs here rather than three.
	 *
	 * Three is one too few, and not by a little: three runs have six orderings, one of which
	 * is the storage order, leaving five for six columns. Two columns must then collide, and
	 * a collision is a hole - with Time and Wrong sharing an order, a Wrong column that sorted
	 * by the run's SECONDS passed the whole suite. That mutation is what this fourth run
	 * closes, so do not trim it back.
	 *
	 *     id  when      Time   Count  Right  Wrong  Accuracy  Sec/Item
	 *     a   14:07:10  00:24  2      1      1      50%       12.00
	 *     b   14:07:11  00:20  8      2      6      25%       2.50
	 *     c   14:07:12  00:36  9      4      5      44%       4.00
	 *     d   14:07:13  00:30  5      3      2      60%       6.00
	 */
	private fun fourRuns(): List<DrillRun> = listOf(
		run("a", fixedMillis + 1000L, seconds = 24f, right = 1, wrong = 1),
		run("b", fixedMillis + 2000L, seconds = 20f, right = 2, wrong = 6),
		run("c", fixedMillis + 3000L, seconds = 36f, right = 4, wrong = 5),
		run("d", fixedMillis + 4000L, seconds = 30f, right = 3, wrong = 2)
	)

	private fun run(
		id: String,
		startedAt: Long,
		seconds: Float,
		right: Int,
		wrong: Int
	): DrillRun = DrillRun(id, startedAt, seconds, marks(right, wrong))

	/** [right] cells marked right then [wrong] marked wrong, all two-digit Numbers values. */
	private fun marks(right: Int, wrong: Int): List<DrillItem> =
		List(right) { DrillItem("07", ItemStatus.RIGHT) } +
			List(wrong) { DrillItem("42", ItemStatus.WRONG) }
}
