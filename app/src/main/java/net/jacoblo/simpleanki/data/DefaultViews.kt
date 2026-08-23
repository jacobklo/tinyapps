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

import net.jacoblo.simpleanki.table.TableEngine

object DefaultViews {

	/** One row per question, showing the most recent attempt at it. */
	fun statsView(tableSettings: TableSettings): TableView = TableView(
		id = "stats",
		name = "Stats",
		filterToCurrentDeck = true,
		collapseDuplicatesOn = TableEngine.ID_QUESTION,
		highlightEvery = tableSettings.highlightEvery,
		defaultSort = SortSpec(TableEngine.ID_QUESTION, SortDir.ASC),
		// Base columns only. Best, Avg, Med, Attempts, and Accuracy are aggregates, and
		// the pivot engine that can express them does not exist until Task 13; a
		// stand-in implementation here would only have to be torn back out.
		columns = listOf(
			ColumnSpec(TableEngine.ID_QUESTION, "Question", width = 220, frozen = true),
			ColumnSpec(TableEngine.ID_SECONDS, "Last", width = 100)
		)
	)

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
