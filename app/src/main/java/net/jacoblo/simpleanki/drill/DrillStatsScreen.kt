/*
 * One drill's stored runs, shown through the same Tabulator page every table view uses.
 *
 * A host and not a renderer. The table comes from DrillStatsTable.render and the page from
 * TableWebView, both untouched, so the whole of this file is one piece of state - the sort the
 * user has applied - and one rule: which run a row tap stands for. Both of those are asked of
 * DrillStatsTable, which is where the JVM tests already are.
 *
 * Deliberately NOT TableScreen with a second row type. That screen renders a TableView over
 * history entries and hosts the column sheet, and a drill stats table has neither a view nor a
 * sheet: its eight columns are fixed in Kotlin. See the note at the top of DrillStatsTable.
 */
package net.jacoblo.simpleanki.drill

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
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.TableBridge
import net.jacoblo.simpleanki.table.TableWebView

private const val LOG_TAG = "SimpleAnkiTable"

/**
 * The stats table for [kind], with a row tap reopening the run that row was drawn from.
 *
 * @param runs every stored run of [kind], in storage order. Ordering is this screen's business
 *   and not the caller's, because the order the rows are drawn in and the order a tap is
 *   resolved against have to be the same one.
 * @param tableSettings read here for the row-banding interval, and handed on to the page for the
 *   highlight colour the way TableScreen hands it on. The interval is read HERE and not there,
 *   because TableScreen takes it off the view it is rendering and a fixed table has no view.
 * @param onOpenRun raised by a row tap with the run that row stands for. The caller shows it on
 *   the drill screen.
 * @param onRendered fired after each render with the table that was produced. Wired to
 *   AppContainer.dumpRendered, which is a no-op outside test mode.
 */
@Composable
fun DrillStatsScreen(
	kind: DrillKind,
	runs: List<DrillRun>,
	tableSettings: TableSettings,
	onOpenRun: (DrillRun) -> Unit,
	onRendered: (RenderedTable) -> Unit,
	modifier: Modifier = Modifier
) {
	// One state object for the life of the screen, reset when the drawer switches to the other
	// drill. `remember(kind) { mutableStateOf(...) }` would read more tidily and would hand out
	// a NEW state object on every switch, leaving the bridge below - captured once by the
	// WebView - writing sorts into the discarded one. The shape TableScreen uses, keyed on the
	// drill for the reason DrillScreen gives: both drills are headed for one call site.
	val sortState = remember { mutableStateOf(DrillStatsTable.DEFAULT_SORT) }
	remember(kind) { sortState.value = DrillStatsTable.DEFAULT_SORT }
	var sort by sortState

	// The table already carries viewEditable = false, so the page leaves the header menu's four
	// view items off by itself and this screen does nothing to suppress them - see the note at
	// that argument in DrillStatsTable.render.
	val table = remember(runs, kind, sort, tableSettings.highlightEvery) {
		DrillStatsTable.render(runs, kind, sort, tableSettings.highlightEvery)
	}

	// The bridge is captured once by the WebView, so it must outlive recomposition - and [runs]
	// is a parameter that arrives as a different list every time, so without this the row-tap
	// handler would go on mapping taps against whichever list was in force when it was built.
	//
	// The sort needs no such treatment and deliberately does not get one: `sort` delegates to
	// [sortState], which IS remembered once, so reading it inside a callback reads it live.
	// Wrapping it too would add a second thing to keep current for no gain.
	val currentRuns by rememberUpdatedState(runs)
	val currentOnOpenRun by rememberUpdatedState(onOpenRun)
	val currentOnRendered by rememberUpdatedState(onRendered)

	// Fires on the render rather than on the page's paint, for TableScreen's reason: a dead
	// render process must not be able to withhold the test-mode dump written from here.
	LaunchedEffect(table) { currentOnRendered(table) }

	val bridge = remember {
		TableBridge(
			// Live, and it must be: viewEditable = false suppresses the menu's four view items
			// and nothing else, and table.html wires headerClick unconditionally. A header tap
			// still sorts this table, which is the whole reason DrillStatsTable.nextSort exists.
			onSort = { columnId ->
				sort = DrillStatsTable.nextSort(sort, columnId)
				// The applied sort, not the tapped column: which branch of nextSort ran is the
				// only thing a header tap can get wrong here, and an unsortable column falling
				// back to the default is invisible from outside the WebView without this.
				Log.d(LOG_TAG, "drill stats sort ${sort.column} ${sort.dir}")
			},
			// The five view-editing callbacks, empty because they are UNREACHABLE and not
			// because they are unfinished. The table above carries viewEditable = false, so the
			// page never puts Hide, Freeze, Move left or Move right on a header menu; resize
			// and reorder come off header DRAGS, which no table in this app enables.
			//
			// Empty rather than a throw: "unreachable" means the page as it stands raises none
			// of them, and a page bug should not take the screen down with it. There would be
			// nothing to do with the answer in any case - these columns are fixed, so every one
			// of these edits would be undone by the next render.
			onResize = { _, _ -> },
			onReorder = { },
			onHide = { },
			onFreeze = { },
			onMove = { _, _ -> },
			onRowTap = { index ->
				// Mapped through order() with the sort IN FORCE, which is the one thing this
				// screen can get wrong. The rows were rendered from that same call, so index
				// for index the two lists are the same one whenever the page's payload is
				// current - which is every case but a tap that crosses a render, see below.
				// Mapping against [runs] itself would instead open a neighbour of the tapped
				// row under every sort but the default, and under the default it would look
				// perfectly correct.
				val run = DrillStatsTable.order(currentRuns, sort).getOrNull(index)
				if (run == null) {
					// The page reports an index into the payload it currently holds, and a tap
					// can cross a render that shortened the table. Ignored rather than
					// range-checked into a crash: there is no run to open, and the next render
					// puts the page and this list back in step by itself.
					//
					// The same window has a quieter half that nothing here closes: an index
					// still IN range after such a render resolves to a real but different run.
					// Closing it would mean tracking which payload the page is actually showing,
					// which is renderComplete threaded through this screen for a race the user
					// has to tap inside a single frame to reach. TableScreen lives with the
					// same one.
					Log.w(LOG_TAG, "row tap $index outside ${currentRuns.size} runs")
				} else {
					// The id, because the failure this screen risks is opening the wrong run,
					// and the id is the only thing that tells a correct mapping from an
					// off-by-one when both rows look plausible.
					Log.d(LOG_TAG, "opening run ${run.id}")
					currentOnOpenRun(run)
				}
			},
			onRenderComplete = { rowCount -> Log.d(LOG_TAG, "rendered $rowCount runs") }
		)
	}

	TableWebView(table, bridge, tableSettings, modifier.fillMaxSize())
}
