/*
 * The three built-in views, hardcoded, plus the one screen that shows one of them.
 *
 * Scaffolding for Task 5 only. Task 6 lifts the three views into DefaultViews.kt behind
 * the navigation drawer and deletes [HardcodedHistoryTable] along with this file, so
 * nothing here is worth generalising.
 */
package net.jacoblo.simpleanki.table

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView

private const val LOG_TAG = "SimpleAnkiTable"

/** One row per question, showing the most recent attempt at it. */
internal val STATS_VIEW = TableView(
	id = "stats",
	name = "Stats",
	filterToCurrentDeck = true,
	collapseDuplicatesOn = TableEngine.ID_QUESTION,
	highlightEvery = 5,
	defaultSort = SortSpec(TableEngine.ID_QUESTION, SortDir.ASC),
	columns = listOf(
		ColumnSpec(TableEngine.ID_QUESTION, "Question", width = 220, frozen = true),
		ColumnSpec(TableEngine.ID_SECONDS, "Last", width = 100)
	)
)

/** Every attempt, newest first. Wider than any phone, so it scrolls sideways. */
internal val HISTORY_VIEW = TableView(
	id = "history",
	name = "History",
	filterToCurrentDeck = true,
	collapseDuplicatesOn = null,
	highlightEvery = 5,
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

/** The bare question list. */
internal val LIST_ROWS_VIEW = TableView(
	id = "list_rows",
	name = "Questions",
	filterToCurrentDeck = true,
	collapseDuplicatesOn = null,
	highlightEvery = 5,
	defaultSort = SortSpec(TableEngine.ID_WHEN, SortDir.DESC),
	columns = listOf(
		ColumnSpec(TableEngine.ID_INDEX, "#", width = 56),
		ColumnSpec(TableEngine.ID_QUESTION, "Question", width = 260)
	)
)

/**
 * Wires [HISTORY_VIEW] to a live table so the transport can be exercised on a device.
 *
 * A composable rather than a block inlined into MainActivity, which is close enough to
 * its line ceiling that Task 6 needs the room.
 *
 * Sort is the only interaction handled: it is the one that proves the round trip, since
 * a header tap has to reach Kotlin, re-render, and come back as new rows. Resize and
 * reorder are logged rather than applied, because the views they would be saved into do
 * not exist until Task 8.
 */
@Composable
internal fun HardcodedHistoryTable(history: List<HistoryEntry>, deckQuestions: Set<String>) {
	var sort by remember { mutableStateOf(HISTORY_VIEW.defaultSort) }
	val table = remember(history, deckQuestions, sort) {
		TableEngine.render(history, deckQuestions, HISTORY_VIEW, sort)
	}
	val bridge = remember {
		TableBridge(
			onSort = { columnId ->
				// Tapping the sorted column flips it; any other column starts descending.
				sort = if (sort.column == columnId && sort.dir == SortDir.DESC) {
					SortSpec(columnId, SortDir.ASC)
				} else {
					SortSpec(columnId, SortDir.DESC)
				}
			},
			onResize = { columnId, width -> Log.d(LOG_TAG, "resize $columnId to $width") },
			onReorder = { columnIds -> Log.d(LOG_TAG, "reorder ${columnIds.joinToString(",")}") },
			onRenderComplete = { rowCount -> Log.d(LOG_TAG, "rendered $rowCount rows") }
		)
	}
	TableWebView(table, bridge, Modifier.fillMaxSize())
}
