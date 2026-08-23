/*
 * The one screen every table view is shown through.
 *
 * It owns exactly one piece of state, the sort the user currently has applied. Column
 * widths and column order are not state here: they belong to the view, so a resize or a
 * reorder rebuilds the view and hands it back to the caller, which is what lets Task 8
 * autosave them without this file learning about storage.
 */
package net.jacoblo.simpleanki.table

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView

private const val LOG_TAG = "SimpleAnkiTable"

/**
 * Renders [view] over [history].
 *
 * @param onViewChanged raised when a header drag changed the view - a new column width
 *   or a new column order. Task 8 wires it to autosave.
 * @param onRendered fired after each render, with the table that was produced. Task 7
 *   wires this to TestMode.writeDump.
 */
@Composable
fun TableScreen(
	history: List<HistoryEntry>,
	deckQuestions: Set<String>,
	view: TableView,
	onViewChanged: (TableView) -> Unit,
	onRendered: (RenderedTable) -> Unit,
	modifier: Modifier = Modifier
) {
	// One state object for the life of the screen, reset when the drawer switches to a
	// different view. `remember(view.id) { mutableStateOf(...) }` would look tidier but
	// would hand out a NEW state object on every switch, and the bridge below - captured
	// once by the WebView - would keep writing sorts into the discarded one.
	//
	// A resize or reorder rebuilds the view under the same id, so neither disturbs the
	// sort the user just chose.
	val sortState = remember { mutableStateOf(view.defaultSort) }
	remember(view.id) { sortState.value = view.defaultSort }
	var sort by sortState
	val table = remember(history, deckQuestions, view, sort) {
		TableEngine.render(history, deckQuestions, view, sort)
	}

	// The bridge is captured once by the WebView, so it must outlive recomposition. These
	// keep its callbacks reading the current arguments rather than the ones in force when
	// it was built.
	val currentView by rememberUpdatedState(view)
	val currentOnViewChanged by rememberUpdatedState(onViewChanged)
	val currentOnRendered by rememberUpdatedState(onRendered)

	// Fires on the engine render rather than on the page's paint: a dead render process
	// must not be able to withhold the dump Task 7 writes from here.
	LaunchedEffect(table) { currentOnRendered(table) }

	val bridge = remember {
		TableBridge(
			// Tapping a column sorts it ascending; tapping the one already sorted
			// reverses it.
			onSort = { columnId ->
				sort = if (sort.column == columnId) {
					SortSpec(columnId, if (sort.dir == SortDir.ASC) SortDir.DESC else SortDir.ASC)
				} else {
					SortSpec(columnId, SortDir.ASC)
				}
				// The applied sort, not the tapped column: which of the two branches
				// above ran is the only thing a header tap can get wrong, and it is
				// invisible from outside the WebView without this.
				Log.d(LOG_TAG, "sort ${sort.column} ${sort.dir}")
			},
			onResize = { columnId, width ->
				currentOnViewChanged(currentView.withWidth(columnId, width))
			},
			onReorder = { columnIds ->
				currentOnViewChanged(currentView.reordered(columnIds))
			},
			onRenderComplete = { rowCount -> Log.d(LOG_TAG, "rendered $rowCount rows") }
		)
	}

	TableWebView(table, bridge, modifier.fillMaxSize())
}

/** A copy of this view with [columnId]'s width replaced, or the same view if it has no such column. */
private fun TableView.withWidth(columnId: String, width: Int): TableView =
	copy(columns = columns.map { if (it.id == columnId) it.copy(width = width) else it })

/**
 * A copy of this view with its columns in the order [columnIds] names.
 *
 * The page only ever reports the columns it drew, so any column it could not draw -
 * hidden today, and from Task 9 a computed one whose formula failed - is missing from
 * that list. Those are appended in their existing relative order rather than dropped,
 * since dropping them would delete a column the user still owns.
 */
private fun TableView.reordered(columnIds: List<String>): TableView {
	val byId = columns.associateBy { it.id }
	val named = columnIds.toSet()
	val moved = columnIds.mapNotNull { byId[it] }
	val rest = columns.filter { it.id !in named }
	return copy(columns = moved + rest)
}
