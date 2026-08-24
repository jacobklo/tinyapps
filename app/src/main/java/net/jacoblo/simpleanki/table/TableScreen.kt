/*
 * The one screen every table view is shown through.
 *
 * It owns exactly one piece of state, the sort the user currently has applied. Column
 * widths, column order, and column visibility are not state here: they belong to the
 * view, so a resize, a reorder, or a checkbox in the sheet rebuilds the view and hands it
 * back to the caller, which is what lets TableRoute autosave every one of them without
 * this file learning about storage.
 *
 * The sheet's width field and its move buttons go out through withWidth and reordered,
 * the very functions the page's resize and reorder events land on. One edit, one rule,
 * whichever surface asked for it - and the header context menu is the third surface to
 * ask, hiding a column through the sheet's own toggleColumn.
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
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.data.addComputed
import net.jacoblo.simpleanki.data.collapseOn
import net.jacoblo.simpleanki.data.moveColumn
import net.jacoblo.simpleanki.data.moveVisibleColumn
import net.jacoblo.simpleanki.data.removeColumn
import net.jacoblo.simpleanki.data.replaceComputed
import net.jacoblo.simpleanki.data.toggleColumn
import net.jacoblo.simpleanki.data.toggleFrozen

private const val LOG_TAG = "SimpleAnkiTable"

/**
 * Renders [view] over [history], with the column sheet over the top of it.
 *
 * The sheet is hosted here rather than beside the caller because this is where the render
 * happens, and the sheet's warnings are the render's own. Its column edits are ordinary
 * view edits and go out through [onViewChanged] like a resize does; the four that create
 * or destroy a VIEW cannot be expressed that way and are passed in.
 *
 * @param sheetOpen whether the column sheet is showing. Hoisted because the action that
 *   opens it lives in the top bar, which is above this screen.
 * @param tableSettings passed straight through to the sheet, which seeds the
 *   computed-column builder's window size and limit from it.
 * @param onViewChanged raised whenever the view changed - any of the sheet's column
 *   edits, or a width or order reported by the page. The caller saves it to views.json.
 * @param onRendered fired after each render, with the table that was produced. Wired to
 *   TestMode.writeDump.
 */
@Composable
fun TableScreen(
	history: List<HistoryEntry>,
	deckQuestions: Set<String>,
	view: TableView,
	tableSettings: TableSettings,
	sheetOpen: Boolean,
	onViewChanged: (TableView) -> Unit,
	onRendered: (RenderedTable) -> Unit,
	onSaveAsNew: (name: String) -> Unit,
	onRename: (name: String) -> Unit,
	onDelete: () -> Unit,
	onResetDefaults: () -> Unit,
	onDismissSheet: () -> Unit,
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
	// must not be able to withhold the test-mode dump written from here.
	LaunchedEffect(table) { currentOnRendered(table) }

	val bridge = remember {
		TableBridge(
			// Tapping a column sorts it ascending; tapping the one already sorted
			// reverses it.
			onSort = { columnId ->
				sort = nextSort(sort, columnId)
				// The applied sort, not the tapped column: which branch of nextSort ran
				// is the only thing a header tap can get wrong, and it is invisible from
				// outside the WebView without this.
				Log.d(LOG_TAG, "sort ${sort.column} ${sort.dir}")
			},
			onResize = { columnId, width ->
				currentOnViewChanged(currentView.withWidth(columnId, width))
			},
			onReorder = { columnIds ->
				currentOnViewChanged(currentView.reordered(columnIds))
			},
			// The three header-menu items that change the view. Each one is an ordinary
			// view edit and leaves through onViewChanged exactly as a resize does, which
			// is what makes it survive a resume: the menu never edits the page's own copy
			// of anything, because Kotlin re-pushes the whole payload on the next render
			// and would overwrite it.
			//
			// Hide routes to toggleColumn, the sheet's own checkbox rule, rather than to a
			// second rule spelling out visible = false. The menu cannot be opened on a
			// column that is already hidden - a hidden column has no header to press - so
			// the toggle can only ever hide from here. See TableBridge.hide.
			onHide = { columnId -> currentOnViewChanged(currentView.toggleColumn(columnId)) },
			onFreeze = { columnId -> currentOnViewChanged(currentView.toggleFrozen(columnId)) },
			// moveVisibleColumn rather than the sheet's moveColumn: the page counts in the
			// columns it drew, and only the visible ones were drawn.
			onMove = { columnId, delta ->
				currentOnViewChanged(currentView.moveVisibleColumn(columnId, delta))
			},
			// Deliberately empty, not unfinished. A row in Stats, History or List Rows is a
			// history record and this app has no screen that shows one, so a tap here has
			// nothing to open.
			onRowTap = {},
			onRenderComplete = { rowCount -> Log.d(LOG_TAG, "rendered $rowCount rows") }
		)
	}

	TableWebView(table, bridge, tableSettings, modifier.fillMaxSize())

	if (sheetOpen) {
		ColumnSheet(
			view = view,
			tableSettings = tableSettings,
			warnings = table.warnings,
			onToggleVisible = { columnId -> onViewChanged(view.toggleColumn(columnId)) },
			onCollapseOn = { columnId -> onViewChanged(view.collapseOn(columnId)) },
			onMoveColumn = { columnId, delta -> onViewChanged(view.moveColumn(columnId, delta)) },
			// withWidth, the same function the bridge's onResize above lands on.
			onSetWidth = { columnId, width -> onViewChanged(view.withWidth(columnId, width)) },
			onAddComputed = { spec -> onViewChanged(view.addComputed(spec)) },
			onEditComputed = { columnId, spec ->
				onViewChanged(view.replaceComputed(columnId, spec))
			},
			onRemoveColumn = { columnId -> onViewChanged(view.removeColumn(columnId)) },
			onSaveAsNew = onSaveAsNew,
			onRename = onRename,
			onDelete = onDelete,
			onResetDefaults = onResetDefaults,
			onDismiss = onDismissSheet
		)
	}
}
