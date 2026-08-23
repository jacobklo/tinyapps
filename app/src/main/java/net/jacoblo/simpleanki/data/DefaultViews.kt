/*
 * The three views the app ships with.
 *
 * Not what the app shows. [ViewsRepository] writes [DefaultViews.all] out as the seed
 * content of views.json the first time that file is missing or unusable, and the stored
 * copy wins from then on; these are the seed and the factory reset.
 *
 * Free of Android imports - TableEngine, whose column ids these name, is pure Kotlin -
 * so JVM tests can build and assert on them with no emulator.
 */
package net.jacoblo.simpleanki.data

import net.jacoblo.simpleanki.table.FormulaParser
import net.jacoblo.simpleanki.table.FormulaWriter
import net.jacoblo.simpleanki.table.TableEngine

object DefaultViews {

	/** Attempts a per-card figure looks at, matching the ten the retired StatsScreen kept. */
	private const val RECENT_ATTEMPTS = 10

	/** Every member, which is what a lifetime total wants. */
	private const val NO_LIMIT = 0

	/** Spelled short so the column list below still reads as the table it is. */
	private const val SECONDS = TableEngine.ID_SECONDS

	/** Shared by all five stats aggregates, so they cost two partition passes, not five. */
	private val PER_QUESTION = Partition.Group(TableEngine.ID_QUESTION)

	/**
	 * One row per question, showing the most recent attempt at it beside five aggregates
	 * over that question's history.
	 *
	 * Best, Avg and Med read the newest [RECENT_ATTEMPTS] attempts and drop the timed-out
	 * ones from that window, which is precisely what the retired StatsScreen did; Attempts
	 * and Accuracy read every attempt, since a timeout is what Accuracy is counting.
	 *
	 * The aggregates are computed before the collapse on Question, so each surviving row
	 * carries its whole question's figures rather than its own single attempt's.
	 */
	fun statsView(tableSettings: TableSettings): TableView = TableView(
		id = "stats",
		name = "Stats",
		filterToCurrentDeck = true,
		collapseDuplicatesOn = TableEngine.ID_QUESTION,
		highlightEvery = tableSettings.highlightEvery,
		defaultSort = SortSpec(TableEngine.ID_QUESTION, SortDir.ASC),
		columns = listOf(
			ColumnSpec(TableEngine.ID_QUESTION, "Question", width = 220, frozen = true),
			ColumnSpec(TableEngine.ID_SECONDS, "Last", width = 100),
			computed("Best", 90, CellFormat.TWO_DP, Aggregate.MIN, SECONDS, RECENT_ATTEMPTS),
			computed("Avg", 90, CellFormat.TWO_DP, Aggregate.AVG, SECONDS, RECENT_ATTEMPTS),
			computed("Med", 90, CellFormat.TWO_DP, Aggregate.MEDIAN, SECONDS, RECENT_ATTEMPTS),
			// The wildcard source, and the one place it is legal: COUNT reads the size of
			// the member set and never looks at a value.
			computed("Attempts", 90, CellFormat.INT, Aggregate.COUNT, FormulaParser.WILDCARD, NO_LIMIT),
			computed("Accuracy", 100, CellFormat.PERCENT, Aggregate.ACCURACY, SECONDS, NO_LIMIT)
		)
	)

	/**
	 * One computed column, with its formula mirror written from the struct.
	 *
	 * The struct is canonical and the string is what a user reads in views.json;
	 * [ViewsRepository] regenerates the mirror from the struct on every save, and writing
	 * it here too means the seed is byte for byte what the first save would have produced
	 * rather than something the next round-trip quietly rewrites.
	 */
	private fun computed(
		id: String,
		width: Int,
		format: CellFormat,
		aggregate: Aggregate,
		source: String,
		limit: Int
	): ColumnSpec {
		val spec = ComputedSpec(aggregate, source, PER_QUESTION, limit)
		return ColumnSpec(
			id = id,
			title = id,
			width = width,
			format = format,
			computed = spec,
			formula = FormulaWriter.write(spec)
		)
	}

	/** Every attempt, newest first. Wider than any phone, so it scrolls sideways. */
	fun historyView(tableSettings: TableSettings): TableView = TableView(
		id = "history",
		name = "History",
		filterToCurrentDeck = true,
		collapseDuplicatesOn = null,
		highlightEvery = tableSettings.highlightEvery,
		defaultSort = SortSpec(TableEngine.ID_WHEN, SortDir.DESC),
		columns = listOf(
			ColumnSpec(TableEngine.ID_INDEX, "#", width = 56, frozen = true),
			ColumnSpec(TableEngine.ID_WHEN, "When", width = 140),
			ColumnSpec(TableEngine.ID_QUESTION, "Question", width = 200),
			ColumnSpec(TableEngine.ID_ANSWER, "Answer", width = 200),
			ColumnSpec(TableEngine.ID_SECONDS, "Seconds", width = 90),
			ColumnSpec(TableEngine.ID_TIMED_OUT, "TimedOut", width = 90)
		)
	)

	/**
	 * The bare question list, newest first.
	 *
	 * What is left of the retired tile grid: the same questions in the same order, as a
	 * two column table rather than as cards.
	 */
	fun listRowsView(tableSettings: TableSettings): TableView = TableView(
		id = "list_rows",
		name = "List Rows",
		filterToCurrentDeck = true,
		collapseDuplicatesOn = null,
		highlightEvery = tableSettings.highlightEvery,
		defaultSort = SortSpec(TableEngine.ID_WHEN, SortDir.DESC),
		columns = listOf(
			ColumnSpec(TableEngine.ID_INDEX, "#", width = 56),
			ColumnSpec(TableEngine.ID_QUESTION, "Question", width = 260)
		)
	)

	/** Drawer order, which is also the order views.json is seeded in. */
	fun all(tableSettings: TableSettings): List<TableView> =
		listOf(statsView(tableSettings), historyView(tableSettings), listRowsView(tableSettings))
}
