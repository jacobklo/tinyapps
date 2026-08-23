/*
 * The table destination: which view is showing, and where its edits are stored.
 *
 * Extracted from MainActivity for the reason AnkiDrawer.kt was: MainActivity sits close
 * enough to its line ceiling that the seven view-lifecycle callbacks would not fit beside
 * the game loop. Everything here was in the `is Screen.Table ->` branch.
 */
package net.jacoblo.simpleanki

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.ViewsFile
import net.jacoblo.simpleanki.data.delete
import net.jacoblo.simpleanki.data.rename
import net.jacoblo.simpleanki.data.saveAsNew
import net.jacoblo.simpleanki.table.TableScreen
import java.io.IOException

/**
 * Shows the view [viewId] names and autosaves every edit to it.
 *
 * There is no explicit save anywhere in this flow. A header drag, a visibility toggle, a
 * rename - each rebuilds the stored file and writes it immediately, so what is on screen
 * and what is on disk never disagree.
 *
 * @param onViewsFile hands the rebuilt file back to the caller, which holds it.
 * @param onSelect switches the drawer's selection, which a save-as-new and a delete both
 *   have to do because the view that was showing is no longer the one to show.
 */
@Composable
fun TableRoute(
	container: AppContainer,
	viewsFile: ViewsFile,
	viewId: String,
	history: List<HistoryEntry>,
	deckQuestions: Set<String>,
	sheetOpen: Boolean,
	onViewsFile: (ViewsFile) -> Unit,
	onSelect: (String) -> Unit,
	onDismissSheet: () -> Unit
) {
	val context = LocalContext.current
	val views = viewsFile.views
	// A stored id naming no view falls back to the first, and re-points the selection so
	// the drawer highlights what is actually showing.
	val view = views.firstOrNull { it.id == viewId } ?: views.firstOrNull()
	LaunchedEffect(view?.id) {
		if (view != null && view.id != viewId) onSelect(view.id)
	}
	if (view == null) {
		Text("No views to show.")
		return
	}

	// Held and written in one step, so a failed write cannot leave the screen showing an
	// edit the file does not have.
	fun store(updated: ViewsFile) {
		onViewsFile(updated)
		try {
			container.viewsRepository.save(updated)
		} catch (e: IOException) {
			Toast.makeText(
				context,
				"Could not save views.json - check file permission or free space",
				Toast.LENGTH_SHORT
			).show()
		}
	}

	TableScreen(
		history = history,
		deckQuestions = deckQuestions,
		view = view,
		sheetOpen = sheetOpen,
		onViewChanged = { changed ->
			// The same view, rebuilt; store it under the same id.
			store(viewsFile.copy(views = views.map { if (it.id == changed.id) changed else it }))
		},
		onRendered = container::dumpRendered,
		onSaveAsNew = { name ->
			val updated = viewsFile.saveAsNew(view.id, name)
			store(updated)
			onSelect(updated.activeViewId)
			onDismissSheet()
		},
		onRename = { name -> store(viewsFile.rename(view.id, name)) },
		onDelete = {
			// activeViewId is pinned to what is on screen first, so that "falls back to
			// the first remaining view" is measured from the view being deleted. The
			// drawer selects by screen rather than by activeViewId, so the two can
			// otherwise disagree and the fallback would land somewhere arbitrary.
			val updated = viewsFile.copy(activeViewId = view.id).delete(view.id)
			if (updated == null) {
				Toast.makeText(context, "Cannot delete the last view", Toast.LENGTH_SHORT).show()
			} else {
				store(updated)
				onSelect(updated.activeViewId)
				onDismissSheet()
			}
		},
		onResetDefaults = {
			store(container.viewsRepository.resetBuiltIns(viewsFile, container.settings.table))
		},
		onDismissSheet = onDismissSheet
	)
}
