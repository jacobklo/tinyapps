package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.CellFormat
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.table.TableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Covers the render pipeline end to end.
 *
 * Every case that renders a calendar column pins the zone, so no assertion here depends
 * on the machine the tests run on.
 */
class TableEngineTest {

	private val utc = ZoneId.of("UTC")
	private val tokyo = ZoneId.of("Asia/Tokyo")

	/** 2024-03-05T14:07:09Z, chosen so UTC and Tokyo disagree on the hour but not the day. */
	private val fixedMillis = Instant.parse("2024-03-05T14:07:09Z").toEpochMilli()

	@Test
	fun emptyHistoryKeepsTheColumnsAndYieldsNoRows() {
		val table = render(emptyList(), view(column("#"), column("Question")))

		assertEquals(listOf("#", "Question"), table.columns.map { it.id })
		assertTrue(table.rows.isEmpty())
		assertEquals(0, table.visibleRowCount)
		assertTrue(table.warnings.isEmpty())
	}

	@Test
	fun deckFilterRemovingEveryRowLeavesTheColumnsStanding() {
		val history = listOf(entry("retired", timestamp = 10L))
		val table = render(
			history,
			view(column("Question"), filterToCurrentDeck = true),
			deckQuestions = setOf("current")
		)

		assertTrue(table.rows.isEmpty())
		assertEquals(listOf("Question"), table.columns.map { it.id })
	}

	@Test
	fun deckFilterKeepsOnlyQuestionsInTheCurrentDeck() {
		val history = listOf(
			entry("kept", timestamp = 10L),
			entry("dropped", timestamp = 20L),
			entry("kept", timestamp = 30L)
		)
		val table = render(
			history,
			view(column("Question"), filterToCurrentDeck = true),
			deckQuestions = setOf("kept")
		)

		assertEquals(listOf(listOf("kept"), listOf("kept")), table.rows)
	}

	@Test
	fun deckFilterIsSkippedWhenTheViewDoesNotAskForIt() {
		val history = listOf(entry("anything", timestamp = 10L))
		val table = render(history, view(column("Question")), deckQuestions = emptySet())

		assertEquals(1, table.visibleRowCount)
	}

	@Test
	fun theBaseOrderIsWhenDescending() {
		val history = listOf(
			entry("oldest", timestamp = 100L),
			entry("newest", timestamp = 300L),
			entry("middle", timestamp = 200L)
		)
		val table = render(history, view(column("Question")))

		assertEquals(listOf("newest", "middle", "oldest"), table.rows.map { it[0] })
	}

	@Test
	fun aStableSortLeavesTheNewestAttemptAsTheCollapseSurvivor() {
		val history = listOf(
			entry("beta", answer = "beta-old", timestamp = 50L),
			entry("alpha", answer = "alpha-old", timestamp = 100L),
			entry("alpha", answer = "alpha-new", timestamp = 200L),
			entry("beta", answer = "beta-new", timestamp = 300L)
		)
		val view = view(
			column("Question"),
			column("Answer"),
			collapseDuplicatesOn = "Question"
		)

		val table = render(history, view, sort = SortSpec("Question", SortDir.ASC))

		// Ties on Question fall back to the base order, which is newest first.
		assertEquals(
			listOf(listOf("alpha", "alpha-new"), listOf("beta", "beta-new")),
			table.rows
		)
	}

	@Test
	fun collapsingRenumbersTheIndexColumn() {
		val history = listOf(
			entry("a", timestamp = 10L),
			entry("b", timestamp = 20L),
			entry("a", timestamp = 30L),
			entry("c", timestamp = 40L),
			entry("b", timestamp = 50L)
		)
		val view = view(column("#"), column("Question"), collapseDuplicatesOn = "Question")

		val table = render(history, view)

		assertEquals(
			listOf(listOf("1", "b"), listOf("2", "c"), listOf("3", "a")),
			table.rows
		)
		assertEquals(3, table.visibleRowCount)
	}

	@Test
	fun collapsingOnAKeyWithOneDistinctValueLeavesASingleRow() {
		val history = (1..4).map { entry("same", timestamp = it.toLong()) }
		val view = view(column("#"), column("Question"), collapseDuplicatesOn = "Question")

		val table = render(history, view)

		assertEquals(listOf(listOf("1", "same")), table.rows)
	}

	@Test
	fun collapsingOnAnUnknownColumnIsIgnoredAndWarns() {
		val history = listOf(entry("a", timestamp = 10L), entry("a", timestamp = 20L))
		val view = view(column("Question"), collapseDuplicatesOn = "Qeustion")

		val table = render(history, view)

		assertEquals(2, table.visibleRowCount)
		assertEquals(listOf("cannot collapse duplicates on unknown column \"Qeustion\""), table.warnings)
	}

	@Test
	fun timedOutRowsSortLastAscending() {
		val table = render(
			timedOutFixture(),
			view(column("Question")),
			sort = SortSpec("Seconds", SortDir.ASC)
		)

		assertEquals(listOf("fast", "slow", "missed"), table.rows.map { it[0] })
	}

	@Test
	fun timedOutRowsSortLastDescending() {
		val table = render(
			timedOutFixture(),
			view(column("Question")),
			sort = SortSpec("Seconds", SortDir.DESC)
		)

		assertEquals(listOf("slow", "fast", "missed"), table.rows.map { it[0] })
	}

	@Test
	fun timedOutRowsHideTheirStoredTimeAndFlagThemselves() {
		val history = listOf(entry("missed", seconds = 12.5f, timestamp = 10L, timedOut = true))
		val table = render(history, view(column("Seconds"), column("TimedOut")))

		assertEquals(listOf(listOf("-", "x")), table.rows)
	}

	@Test
	fun answeredRowsRenderAnEmptyTimedOutCell() {
		val history = listOf(entry("done", seconds = 1.5f, timestamp = 10L))
		val table = render(history, view(column("Seconds"), column("TimedOut")))

		assertEquals(listOf(listOf("1.50", "")), table.rows)
	}

	@Test
	fun textSortsCaseInsensitively() {
		val history = listOf(
			entry("Banana", timestamp = 10L),
			entry("apple", timestamp = 20L),
			entry("Cherry", timestamp = 30L)
		)
		val table = render(history, view(column("Question")), sort = SortSpec("Question", SortDir.ASC))

		assertEquals(listOf("apple", "Banana", "Cherry"), table.rows.map { it[0] })
	}

	@Test
	fun boolSortsFalseBeforeTrue() {
		val table = render(
			boolFixture(),
			view(column("Question")),
			sort = SortSpec("TimedOut", SortDir.ASC)
		)

		// Within each group the base order survives, newest first.
		assertEquals(listOf("in1", "in2", "out1", "out2"), table.rows.map { it[0] })
	}

	@Test
	fun boolSortsTrueFirstWhenDescending() {
		val table = render(
			boolFixture(),
			view(column("Question")),
			sort = SortSpec("TimedOut", SortDir.DESC)
		)

		assertEquals(listOf("out1", "out2", "in1", "in2"), table.rows.map { it[0] })
	}

	@Test
	fun columnsComeBackInViewOrderRatherThanBaseOrder() {
		// Listed against the order of BASE_COLUMNS on purpose: an engine that emitted
		// columns by base index would answer Question, Seconds here.
		val history = listOf(entry("a", seconds = 2.0f, timestamp = 10L))
		val table = render(history, view(column("Seconds"), column("Question")))

		assertEquals(listOf("Seconds", "Question"), table.columns.map { it.id })
		assertEquals(listOf(listOf("2.00", "a")), table.rows)
	}

	@Test
	fun whenSortsOnEpochMillisRatherThanTheRenderedText() {
		// December of one year and January of the next render as "12-.." and "01-..", so a
		// sort on the formatted MM-dd text would put them the wrong way round.
		val december = Instant.parse("2023-12-31T23:00:00Z").toEpochMilli()
		val january = Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()
		val history = listOf(entry("jan", timestamp = january), entry("dec", timestamp = december))

		val table = render(history, view(column("Question")), sort = SortSpec("When", SortDir.ASC))

		assertEquals(listOf("dec", "jan"), table.rows.map { it[0] })
	}

	@Test
	fun aHiddenColumnIsExcludedFromTheOutputButStillSorts() {
		val history = listOf(
			entry("b", seconds = 1.0f, timestamp = 10L),
			entry("a", seconds = 9.0f, timestamp = 20L)
		)
		val view = view(column("Question"), column("Seconds", visible = false))

		val table = render(history, view, sort = SortSpec("Seconds", SortDir.ASC))

		assertEquals(listOf("Question"), table.columns.map { it.id })
		assertEquals(listOf("b", "a"), table.rows.map { it[0] })
		assertTrue(table.warnings.isEmpty())
	}

	@Test
	fun aSortNamingAMissingColumnFallsBackToWhenDescending() {
		val history = listOf(entry("old", timestamp = 10L), entry("new", timestamp = 20L))
		val table = render(history, view(column("Question")), sort = SortSpec("Whn", SortDir.ASC))

		assertEquals(TableEngine.FALLBACK_SORT, table.sort)
		assertEquals(listOf("new", "old"), table.rows.map { it[0] })
		assertEquals(listOf("unknown sort column \"Whn\", sorted by When descending"), table.warnings)
	}

	@Test
	fun aSortNamingTheIndexColumnFallsBackToWhenDescending() {
		val history = listOf(entry("old", timestamp = 10L), entry("new", timestamp = 20L))
		val table = render(history, view(column("#")), sort = SortSpec("#", SortDir.ASC))

		assertEquals(TableEngine.FALLBACK_SORT, table.sort)
		assertEquals(
			listOf("column \"#\" cannot be sorted, sorted by When descending"),
			table.warnings
		)
	}

	@Test
	fun anUnknownColumnIsSkippedWithAWarning() {
		val history = listOf(entry("a", seconds = 2.0f, timestamp = 10L))
		val view = view(column("Question"), column("Secnods"), column("Seconds"))

		val table = render(history, view)

		assertEquals(listOf("Question", "Seconds"), table.columns.map { it.id })
		assertEquals(listOf(listOf("a", "2.00")), table.rows)
		assertEquals(listOf("unknown column \"Secnods\""), table.warnings)
	}

	@Test
	fun aComputedColumnRendersItsFigureWithoutWarning() {
		val computed = avgSeconds()
		val history = listOf(entry("a", seconds = 3.0f, timestamp = 10L))
		val view = view(column("Question"), column("Avg", computed = computed))

		val table = render(history, view)

		assertEquals(listOf(listOf("a", "3.00")), table.rows)
		assertTrue(table.warnings.isEmpty())
		val avg = table.columns[1]
		assertEquals(ColumnType.NUMBER, avg.type)
		assertTrue(avg.sortable)
		assertNull(avg.error)
	}

	@Test
	fun aGroupPivotComputesOneFigurePerQuestionAndBroadcastsItToEveryRow() {
		// q1 has three answered attempts; q2 has one answered and one timeout. MIN and AVG
		// read the answered members only, COUNT and ACCURACY read every member, and all
		// four columns share one partition key, so this also asserts that a shared pass
		// serves four different aggregates correctly.
		val history = listOf(
			entry("q1", seconds = 2.0f, timestamp = 10L),
			entry("q2", seconds = 1.0f, timestamp = 15L),
			entry("q1", seconds = 4.0f, timestamp = 20L),
			entry("q2", seconds = 9.0f, timestamp = 25L, timedOut = true),
			entry("q1", seconds = 6.0f, timestamp = 30L)
		)
		val view = view(
			column("Question"),
			column("Best", computed = group(Aggregate.MIN)),
			column("Avg", computed = group(Aggregate.AVG)),
			column("Attempts", format = CellFormat.INT, computed = group(Aggregate.COUNT, source = "*")),
			column("Accuracy", format = CellFormat.PERCENT, computed = group(Aggregate.ACCURACY))
		)

		val table = render(history, view)

		// Base order is When descending: q1, q2, q1, q2, q1.
		val q1 = listOf("q1", "2.00", "4.00", "3", "100.0%")
		val q2 = listOf("q2", "1.00", "1.00", "2", "50.0%")
		assertEquals(listOf(q1, q2, q1, q2, q1), table.rows)
	}

	@Test
	fun theSpecWorkedExampleForQ03PinsTheLimitSemantics() {
		// Straight from the design spec, where it was worked by hand long before any of
		// this existed: Q03 with attempts 2.4, 8.0, 1.0, 0.5 newest first.
		//
		// It also pins the order of the pipeline. The view collapses on Question, so if
		// the aggregates were computed after the collapse the sole surviving row would be
		// a partition of one and every figure here would read 2.40.
		val history = listOf(
			entry("Q03", seconds = 0.5f, timestamp = 10L),
			entry("Q03", seconds = 1.0f, timestamp = 20L),
			entry("Q03", seconds = 8.0f, timestamp = 30L),
			entry("Q03", seconds = 2.4f, timestamp = 40L)
		)

		assertEquals(listOf(listOf("Q03", "0.50", "2.98", "1.70")), workedExample(history, limit = 0))
		assertEquals(listOf(listOf("Q03", "2.40", "5.20", "5.20")), workedExample(history, limit = 2))
	}

	@Test
	fun aBucketColumnBlocksBySortPositionAndLeavesTheLastBlockShort() {
		val view = view(column("Seconds"), column("Block", computed = bucket(Aggregate.SUM, size = 2)))

		val table = render(ladder(), view)

		// Base order is 1.0 through 5.0, so blocks of two are [1,2], [3,4] and a short [5].
		assertEquals(listOf("1.00", "2.00", "3.00", "4.00", "5.00"), table.rows.map { it[0] })
		assertEquals(listOf("3.00", "3.00", "7.00", "7.00", "5.00"), table.rows.map { it[1] })
	}

	@Test
	fun aRollingColumnClampsItsWindowAtTheTopOfTheTable() {
		val view = view(column("Seconds"), column("Trailing", computed = rolling(Aggregate.AVG, size = 3)))

		val table = render(ladder(), view)

		// Windows are [1], [1,2], [1,2,3], [2,3,4], [3,4,5] - short ones at the top are
		// averaged over what exists rather than left blank.
		assertEquals(listOf("1.00", "1.50", "2.00", "3.00", "4.00"), table.rows.map { it[1] })
	}

	@Test
	fun aRollingSizePastTheRowCountIsStillARunningCumulative() {
		// The documented spelling of a running cumulative, and the size the engine caps at
		// the row count. Capping must not turn it into a grand total, which is what the
		// same size on a bucket would give.
		val view = view(column("Seconds"), column("SoFar", computed = rolling(Aggregate.AVG, size = 999999)))

		val table = render(ladder(), view)

		assertEquals(listOf("1.00", "1.50", "2.00", "2.50", "3.00"), table.rows.map { it[1] })
	}

	@Test
	fun sortingByAComputedColumnReordersTheRowsWithNullsLastInBothDirections() {
		val history = listOf(
			entry("a", seconds = 5.0f, timestamp = 10L),
			entry("b", seconds = 1.0f, timestamp = 20L),
			entry("c", seconds = 3.0f, timestamp = 30L),
			entry("d", seconds = 9.0f, timestamp = 40L, timedOut = true)
		)
		val view = view(column("Question"), column("Avg", computed = group(Aggregate.AVG)))

		val ascending = render(history, view, sort = SortSpec("Avg", SortDir.ASC))
		assertEquals(SortSpec("Avg", SortDir.ASC), ascending.sort)
		assertEquals(listOf("b", "c", "a", "d"), ascending.rows.map { it[0] })
		assertEquals(listOf("1.00", "3.00", "5.00", "-"), ascending.rows.map { it[1] })

		val descending = render(history, view, sort = SortSpec("Avg", SortDir.DESC))
		assertEquals(listOf("a", "c", "b", "d"), descending.rows.map { it[0] })
		assertEquals(listOf("5.00", "3.00", "1.00", "-"), descending.rows.map { it[1] })
	}

	@Test
	fun aHiddenComputedColumnIsExcludedFromTheOutputButStillSorts() {
		val history = listOf(
			entry("a", seconds = 5.0f, timestamp = 10L),
			entry("b", seconds = 1.0f, timestamp = 20L),
			entry("c", seconds = 3.0f, timestamp = 30L)
		)
		val view = view(
			column("Question"),
			column("Avg", visible = false, computed = group(Aggregate.AVG))
		)

		val table = render(history, view, sort = SortSpec("Avg", SortDir.ASC))

		assertEquals(listOf("Question"), table.columns.map { it.id })
		assertEquals(listOf("b", "c", "a"), table.rows.map { it[0] })
		assertTrue(table.warnings.isEmpty())
	}

	@Test
	fun reSortingRecomputesARollingColumnButLeavesAGroupOneWithItsRow() {
		val history = listOf(
			entry("a", seconds = 4.0f, timestamp = 30L),
			entry("b", seconds = 2.0f, timestamp = 20L),
			entry("c", seconds = 6.0f, timestamp = 10L)
		)
		val view = view(
			column("Question"),
			column("PerCard", computed = group(Aggregate.AVG)),
			column("Trailing", computed = rolling(Aggregate.AVG, size = 2))
		)

		val byWhen = render(history, view)
		assertEquals(listOf("a", "b", "c"), byWhen.rows.map { it[0] })
		assertEquals(listOf("4.00", "2.00", "6.00"), byWhen.rows.map { it[1] })
		assertEquals(listOf("4.00", "3.00", "4.00"), byWhen.rows.map { it[2] })

		val bySeconds = render(history, view, sort = SortSpec("Seconds", SortDir.ASC))
		assertEquals(listOf("b", "a", "c"), bySeconds.rows.map { it[0] })
		// The group figure travelled with its row; the trailing window was recomputed from
		// the new positions, so every row of it changed and b - now at the top of the
		// table - went from an average of two rows to an average of one.
		assertEquals(listOf("2.00", "4.00", "6.00"), bySeconds.rows.map { it[1] })
		assertEquals(listOf("2.00", "3.00", "5.00"), bySeconds.rows.map { it[2] })
	}

	@Test
	fun aNonNumericSourceIsExcludedRatherThanCountedAsZero() {
		// Nothing validates this pairing. ColumnSheet builds the aggregate and the source
		// from two independent pickers, so AVG over Question is two taps away and persists
		// with no error anywhere; feeding 0.0 would answer "0.00" and look like a figure.
		// COUNT never reads a value, so it is unaffected by the same source.
		val history = listOf(
			entry("a", seconds = 2.0f, timestamp = 10L),
			entry("a", seconds = 4.0f, timestamp = 20L)
		)
		val view = view(
			column("Question"),
			column("AvgText", computed = group(Aggregate.AVG, source = "Question")),
			column("MinFlag", computed = group(Aggregate.MIN, source = "TimedOut")),
			column("Attempts", format = CellFormat.INT, computed = group(Aggregate.COUNT, source = "Question"))
		)

		val table = render(history, view)

		assertEquals(listOf("-", "-"), table.rows.map { it[1] })
		assertEquals(listOf("-", "-"), table.rows.map { it[2] })
		assertEquals(listOf("2", "2"), table.rows.map { it[3] })
	}

	@Test
	fun theIndexColumnIsNotAnAggregateSource() {
		// "#" passes a type check - it IS a NUMBER column - but its value is the display
		// number, which is not assigned until after the collapse below the pivot. Reading
		// it at aggregation time makes every member 0.0 and renders a plausible "0.00"
		// exactly where a "-" belongs, with nothing in warnings to say so.
		val view = view(
			column("Seconds"),
			column("AvgIndex", computed = bucket(Aggregate.AVG, size = 2, source = "#")),
			column("SumIndex", computed = rolling(Aggregate.SUM, size = 3, source = "#")),
			column("GroupIndex", computed = group(Aggregate.MEDIAN, source = "#"))
		)

		val table = render(ladder(), view)

		assertEquals(List(5) { "-" }, table.rows.map { it[1] })
		assertEquals(List(5) { "-" }, table.rows.map { it[2] })
		assertEquals(List(5) { "-" }, table.rows.map { it[3] })
	}

	@Test
	fun aQuestionWhoseEveryAttemptTimedOutRendersADashRatherThanZero() {
		val history = listOf(
			entry("missed", seconds = 9.0f, timestamp = 10L, timedOut = true),
			entry("missed", seconds = 9.0f, timestamp = 20L, timedOut = true)
		)
		val view = view(
			column("Question"),
			column("Best", computed = group(Aggregate.MIN)),
			column("Attempts", format = CellFormat.INT, computed = group(Aggregate.COUNT, source = "*")),
			column("Accuracy", format = CellFormat.PERCENT, computed = group(Aggregate.ACCURACY)),
			collapseDuplicatesOn = "Question"
		)

		val table = render(history, view)

		assertEquals(listOf(listOf("missed", "-", "2", "0.0%")), table.rows)
	}

	@Test
	fun sortingByAColumnWithAFormulaButNoStructFallsBackRatherThanDoingNothing() {
		// The one shape that can call itself computed while having nothing to sort by. It
		// must not report itself as sorted: that draws a sort arrow on a column whose order
		// never changed, which reads as the sort having been applied and found the rows
		// already in order.
		val history = listOf(entry("old", timestamp = 10L), entry("new", timestamp = 20L))
		val view = view(column("Question"), column("Ghost", formula = "Best / Avg"))

		val table = render(history, view, sort = SortSpec("Ghost", SortDir.ASC))

		assertEquals(TableEngine.FALLBACK_SORT, table.sort)
		assertEquals(listOf("new", "old"), table.rows.map { it[0] })
		assertEquals(
			listOf("column \"Ghost\" cannot be sorted, sorted by When descending"),
			table.warnings
		)
	}

	@Test
	fun aLimitIsIgnoredByABucketAndDoesNotChangeItsFigures() {
		// forPartition reads a limit for a group only, so these two columns describe the
		// same member sets and must render the same thing - which is also what lets them
		// share one partition pass.
		val view = view(
			column("Seconds"),
			column("Plain", computed = bucket(Aggregate.SUM, size = 2)),
			column("Limited", computed = ComputedSpec(Aggregate.SUM, "Seconds", Partition.Bucket(2), limit = 1))
		)

		val table = render(ladder(), view)

		val expected = listOf("3.00", "3.00", "7.00", "7.00", "5.00")
		assertEquals(expected, table.rows.map { it[1] })
		assertEquals(expected, table.rows.map { it[2] })
	}

	@Test
	fun collapsingOnAComputedColumnSaysSoRatherThanCallingItUnknown() {
		val history = listOf(entry("a", timestamp = 10L), entry("a", timestamp = 20L))
		val view = view(
			column("Question"),
			column("Avg", computed = avgSeconds()),
			collapseDuplicatesOn = "Avg"
		)

		val table = render(history, view)

		assertEquals(2, table.visibleRowCount)
		assertEquals(listOf("cannot collapse duplicates on computed column \"Avg\""), table.warnings)
	}

	@Test
	fun anErroredColumnRendersErrWhileItsNeighboursRenderNormally() {
		val history = listOf(
			entry("a", seconds = 2.0f, timestamp = 10L),
			entry("b", seconds = 4.0f, timestamp = 20L)
		)
		val view = view(
			column("Question"),
			column("Broken", computed = avgSeconds(), formulaError = "unknown function \"AVERAGE\""),
			column("Avg", computed = group(Aggregate.AVG)),
			column("Seconds")
		)

		val table = render(history, view)

		assertEquals(
			listOf(listOf("b", "#ERR", "4.00", "4.00"), listOf("a", "#ERR", "2.00", "2.00")),
			table.rows
		)
		assertFalse(table.columns[1].sortable)
		assertTrue(table.columns[2].sortable)
		assertEquals(listOf("column \"Broken\" failed: unknown function \"AVERAGE\""), table.warnings)
	}

	@Test
	fun aColumnWithAFormulaErrorRendersErrInEveryCell() {
		val history = listOf(entry("a", timestamp = 10L), entry("b", timestamp = 20L))
		val view = view(
			column("Question"),
			column("Ratio", computed = avgSeconds(), formulaError = "divide by zero")
		)

		val table = render(history, view)

		assertEquals(listOf("#ERR", "#ERR"), table.rows.map { it[1] })
		assertEquals("divide by zero", table.columns[1].error)
	}

	@Test
	fun aFormulaOnlyColumnIsKeptAndItsFailureIsReported() {
		// A formula that failed to parse leaves computed null, which is exactly the shape
		// that must not be mistaken for a typo and dropped.
		val history = listOf(entry("a", timestamp = 10L), entry("b", timestamp = 20L))
		val view = view(
			column("Question"),
			column("Ratio", formula = "Best / ", formulaError = "unexpected end of formula")
		)

		val table = render(history, view)

		assertEquals(listOf("Question", "Ratio"), table.columns.map { it.id })
		assertEquals(listOf("#ERR", "#ERR"), table.rows.map { it[1] })
		val ratio = table.columns[1]
		assertEquals("unexpected end of formula", ratio.error)
		assertFalse(ratio.sortable)
		assertEquals(
			listOf("column \"Ratio\" failed: unexpected end of formula"),
			table.warnings
		)
	}

	@Test
	fun aHiddenColumnStillReportsItsFailure() {
		// The error check deliberately sits above the visibility check: the column sheet
		// lists hidden columns too, so one that broke still has to say so.
		val history = listOf(entry("a", timestamp = 10L))
		val view = view(
			column("Question"),
			column(
				"Ratio",
				visible = false,
				formula = "Best / ",
				formulaError = "unexpected end of formula"
			)
		)

		val table = render(history, view)

		assertEquals(listOf("Question"), table.columns.map { it.id })
		assertEquals(
			listOf("column \"Ratio\" failed: unexpected end of formula"),
			table.warnings
		)
	}

	@Test
	fun aFormulaColumnThatParsedIsKeptWithoutAWarning() {
		val history = listOf(entry("a", timestamp = 10L))
		val view = view(column("Question"), column("Ratio", formula = "Best / Avg"))

		val table = render(history, view)

		assertEquals(listOf("Question", "Ratio"), table.columns.map { it.id })
		assertEquals(listOf(listOf("a", "-")), table.rows)
		assertTrue(table.columns[1].sortable)
		assertTrue(table.warnings.isEmpty())
	}

	@Test
	fun sortingByAnErroredColumnFallsBackToWhenDescending() {
		val history = listOf(entry("old", timestamp = 10L), entry("new", timestamp = 20L))
		val view = view(
			column("Question"),
			column("Ratio", formula = "Best / ", formulaError = "unexpected end of formula")
		)

		val table = render(history, view, sort = SortSpec("Ratio", SortDir.ASC))

		assertEquals(TableEngine.FALLBACK_SORT, table.sort)
		assertEquals(listOf("new", "old"), table.rows.map { it[0] })
		assertTrue(
			table.warnings.contains("column \"Ratio\" cannot be sorted, sorted by When descending")
		)
	}

	@Test
	fun aViewWithNoVisibleColumnsStillCountsItsRows() {
		val history = listOf(entry("a", timestamp = 10L), entry("b", timestamp = 20L))
		val table = render(history, view(column("Question", visible = false)))

		assertTrue(table.columns.isEmpty())
		assertEquals(listOf(emptyList<String>(), emptyList<String>()), table.rows)
		assertEquals(2, table.visibleRowCount)
	}

	@Test
	fun columnMetadataAndViewSettingsArePassedThrough() {
		val view = TableView(
			id = "stats",
			name = "Stats",
			filterToCurrentDeck = false,
			collapseDuplicatesOn = null,
			highlightEvery = 7,
			defaultSort = SortSpec("Question", SortDir.ASC),
			columns = listOf(
				ColumnSpec(id = "Question", title = "Card", width = 240, frozen = true),
				ColumnSpec(id = "Seconds", title = "Last", width = 90)
			)
		)

		val table = render(emptyList(), view)

		assertEquals("stats", table.viewId)
		assertEquals(7, table.highlightEvery)
		assertEquals(SortSpec("Question", SortDir.ASC), table.sort)
		val question = table.columns[0]
		assertEquals("Card", question.title)
		assertEquals(240, question.width)
		assertTrue(question.frozen)
		assertEquals(ColumnType.TEXT, question.type)
		val seconds = table.columns[1]
		assertEquals("Last", seconds.title)
		assertFalse(seconds.frozen)
		assertEquals(ColumnType.NUMBER, seconds.type)
	}

	@Test
	fun aColumnFormatOverridesTheBaseFormat() {
		val history = listOf(entry("a", seconds = 2.345f, timestamp = 10L))
		val view = view(column("Seconds", format = CellFormat.ONE_DP))

		assertEquals(listOf(listOf("2.3")), render(history, view).rows)
	}

	@Test
	fun everyFormatRenders() {
		assertEquals("-", TableEngine.format(null, CellFormat.TEXT))
		assertEquals("-", TableEngine.format(null, CellFormat.TWO_DP))
		assertEquals("hello", TableEngine.format("hello", CellFormat.TEXT))
		assertEquals("x", TableEngine.format(true, CellFormat.TEXT))
		assertEquals("", TableEngine.format(false, CellFormat.TEXT))
		assertEquals("3", TableEngine.format(3, CellFormat.INT))
		assertEquals("4", TableEngine.format(3.6, CellFormat.INT))
		assertEquals("1.5", TableEngine.format(1.5, CellFormat.ONE_DP))
		assertEquals("1.50", TableEngine.format(1.5, CellFormat.TWO_DP))
		assertEquals("87.5%", TableEngine.format(87.5, CellFormat.PERCENT))
		assertEquals("03-05 14:07:09", TableEngine.format(fixedMillis, CellFormat.TIME, utc))
		assertEquals("abc", TableEngine.format("abc", CellFormat.TWO_DP))
		assertEquals("abc", TableEngine.format("abc", CellFormat.PERCENT))
		assertEquals("abc", TableEngine.format("abc", CellFormat.TIME, utc))
	}

	@Test
	fun theTimeFormatFollowsTheGivenZone() {
		assertEquals("03-05 23:07:09", TableEngine.format(fixedMillis, CellFormat.TIME, tokyo))
	}

	@Test
	fun theCalendarColumnsFollowTheGivenZone() {
		val history = listOf(entry("a", timestamp = fixedMillis))
		val view = view(column("When"), column("Date"), column("Time"))

		assertEquals(
			listOf(listOf("03-05 14:07:09", "2024-03-05", "14:07:09")),
			render(history, view).rows
		)
		assertEquals(
			listOf(listOf("03-05 23:07:09", "2024-03-05", "23:07:09")),
			render(history, view, zone = tokyo).rows
		)
	}

	@Test
	fun rawValueHidesTheStoredTimeOfATimedOutRow() {
		val missed = entry("missed", seconds = 11.0f, timestamp = 10L, timedOut = true)
		val answered = entry("done", seconds = 3.0f, timestamp = 10L)

		assertNull(TableEngine.rawValue(missed, "Seconds", 0))
		assertNull(TableEngine.rawValue(answered, "Nope", 0))
		assertEquals(
			"3.00",
			TableEngine.format(TableEngine.rawValue(answered, "Seconds", 0), CellFormat.TWO_DP)
		)
	}

	@Test
	fun baseColumnLooksUpEveryDeclaredIdAndNothingElse() {
		assertEquals(8, TableEngine.BASE_COLUMNS.size)
		TableEngine.BASE_COLUMNS.forEach { assertEquals(it, TableEngine.baseColumn(it.id)) }
		assertNull(TableEngine.baseColumn("Nope"))
		assertFalse(TableEngine.baseColumn("#")!!.sortable)
	}

	private fun timedOutFixture(): List<HistoryEntry> = listOf(
		entry("fast", seconds = 2.0f, timestamp = 10L),
		entry("missed", seconds = 11.0f, timestamp = 20L, timedOut = true),
		entry("slow", seconds = 5.0f, timestamp = 30L)
	)

	/**
	 * Interleaved so that neither sort direction can be reproduced by the base order:
	 * When descending is in1, out1, in2, out2, which is neither answer below.
	 */
	private fun boolFixture(): List<HistoryEntry> = listOf(
		entry("out2", timestamp = 10L, timedOut = true),
		entry("in2", timestamp = 20L),
		entry("out1", timestamp = 30L, timedOut = true),
		entry("in1", timestamp = 40L)
	)

	/** Five answered attempts at five questions, whose base order is 1.0 through 5.0. */
	private fun ladder(): List<HistoryEntry> = (1..5).map {
		entry("q$it", seconds = it.toFloat(), timestamp = (60 - it * 10).toLong())
	}

	/** The spec's Q03 table, rendered as Question, Best, Avg, Med at one [limit]. */
	private fun workedExample(history: List<HistoryEntry>, limit: Int): List<List<String>> = render(
		history,
		view(
			column("Question"),
			column("Best", computed = group(Aggregate.MIN, limit = limit)),
			column("Avg", computed = group(Aggregate.AVG, limit = limit)),
			column("Med", computed = group(Aggregate.MEDIAN, limit = limit)),
			collapseDuplicatesOn = "Question"
		)
	).rows

	private fun avgSeconds() =
		ComputedSpec(Aggregate.AVG, "Seconds", Partition.Group("Question"), limit = 10)

	private fun group(fn: Aggregate, source: String = "Seconds", limit: Int = 0, by: String = "Question") =
		ComputedSpec(fn, source, Partition.Group(by), limit)

	private fun bucket(fn: Aggregate, size: Int, source: String = "Seconds") =
		ComputedSpec(fn, source, Partition.Bucket(size), limit = 0)

	private fun rolling(fn: Aggregate, size: Int, source: String = "Seconds") =
		ComputedSpec(fn, source, Partition.Rolling(size), limit = 0)

	private fun render(
		history: List<HistoryEntry>,
		view: TableView,
		deckQuestions: Set<String> = emptySet(),
		sort: SortSpec = view.defaultSort,
		zone: ZoneId = utc
	) = TableEngine.render(history, deckQuestions, view, sort, zone)

	private fun entry(
		question: String,
		answer: String = "answer",
		seconds: Float = 1.0f,
		timestamp: Long,
		timedOut: Boolean = false
	) = HistoryEntry(question, answer, seconds, timestamp, timedOut)

	private fun column(
		id: String,
		title: String = id,
		visible: Boolean = true,
		format: CellFormat? = null,
		computed: ComputedSpec? = null,
		formula: String? = null,
		formulaError: String? = null
	) = ColumnSpec(
		id = id,
		title = title,
		width = 120,
		visible = visible,
		format = format,
		computed = computed,
		formula = formula,
		formulaError = formulaError
	)

	private fun view(
		vararg columns: ColumnSpec,
		filterToCurrentDeck: Boolean = false,
		collapseDuplicatesOn: String? = null,
		defaultSort: SortSpec = SortSpec("When", SortDir.DESC)
	) = TableView(
		id = "test",
		name = "Test",
		filterToCurrentDeck = filterToCurrentDeck,
		collapseDuplicatesOn = collapseDuplicatesOn,
		highlightEvery = 5,
		defaultSort = defaultSort,
		columns = columns.toList()
	)
}
