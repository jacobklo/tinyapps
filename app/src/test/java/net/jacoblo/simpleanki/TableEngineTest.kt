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
	fun aComputedColumnRendersDashesWithoutWarning() {
		val computed = avgSeconds()
		val history = listOf(entry("a", timestamp = 10L))
		val view = view(column("Question"), column("Avg", computed = computed))

		val table = render(history, view)

		assertEquals(listOf(listOf("a", "-")), table.rows)
		assertTrue(table.warnings.isEmpty())
		val avg = table.columns[1]
		assertEquals(ColumnType.NUMBER, avg.type)
		assertTrue(avg.sortable)
		assertNull(avg.error)
	}

	@Test
	fun sortingByANotYetComputedColumnLeavesTheBaseOrderAlone() {
		val computed = avgSeconds()
		val history = listOf(entry("old", timestamp = 10L), entry("new", timestamp = 20L))
		val view = view(column("Question"), column("Avg", computed = computed))

		val table = render(history, view, sort = SortSpec("Avg", SortDir.ASC))

		assertEquals(SortSpec("Avg", SortDir.ASC), table.sort)
		assertEquals(listOf("new", "old"), table.rows.map { it[0] })
		assertTrue(table.warnings.isEmpty())
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

	private fun avgSeconds() =
		ComputedSpec(Aggregate.AVG, "Seconds", Partition.Group("Question"), limit = 10)

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
