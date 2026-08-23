/*
 * Navigation: what the screens are, and the drawer that switches between them.
 *
 * Extracted from MainActivity, which sits close enough to its line ceiling that the
 * drawer body and the chrome around it would not fit beside the game loop.
 */
package net.jacoblo.simpleanki

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jacoblo.simpleanki.data.TableView

/**
 * Where the app can be.
 *
 * Sealed rather than an enum because the table entries are data driven: one screen per
 * view in the view list, which the user edits from Task 8 onward, so the set of them is
 * not known at compile time.
 */
sealed interface Screen {
	data object FlipCards : Screen
	data class Table(val viewId: String) : Screen
}

/**
 * The chrome every screen sits inside: the top bar, the drawer, and the drawer's
 * open/close behaviour.
 *
 * [content] receives the Scaffold's inner padding, exactly as if it had been passed to
 * Scaffold directly.
 */
@Composable
fun AnkiNavShell(
	lifetimeReviews: Int,
	views: List<TableView>,
	current: Screen,
	onSelect: (Screen) -> Unit,
	content: @Composable (PaddingValues) -> Unit
) {
	val drawerState = rememberDrawerState(DrawerValue.Closed)
	val scope = rememberCoroutineScope()
	// Material3 1.3 does not give the drawer a back handler of its own, so without this
	// back leaves the app while a modal surface is covering it.
	BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
	ModalNavigationDrawer(
		drawerState = drawerState,
		// Only while open. Material3 1.3 hangs the horizontal drag detector on the Box
		// wrapping BOTH the sheet and the content, so an enabled detector spans the whole
		// screen rather than the left edge and swallows the History view's sideways
		// scroll - measured at 4px of travel against 304px with this off. Closing by
		// swipe still works; opening is the hamburger's job, and Material3 cannot scope
		// an edge gesture anyway.
		gesturesEnabled = drawerState.isOpen,
		drawerContent = {
			AnkiDrawer(views, current) {
				onSelect(it)
				scope.launch { drawerState.close() }
			}
		}
	) {
		Scaffold(
			topBar = {
				AnkiTopBar(lifetimeReviews, onOpenDrawer = { scope.launch { drawerState.open() } })
			},
			content = content
		)
	}
}

/**
 * The drawer body: the game, then one entry per view, then the room Task 15's metronome
 * switch goes in.
 *
 * Entries are written out names rather than icons because the view list is open ended -
 * a user defined view has no icon to give it, and nothing sensible could be guessed.
 */
@Composable
private fun AnkiDrawer(views: List<TableView>, current: Screen, onSelect: (Screen) -> Unit) {
	ModalDrawerSheet {
		// Inside the sheet, not on it: a modifier passed to ModalDrawerSheet lands
		// outside its Surface and above M3's own fillMaxHeight, and verticalScroll
		// measures with an infinite maxHeight, which turns that fillMaxHeight into a
		// no-op and shrinks the sheet to wrap its entries - 945px of sheet and 1455px
		// of bare scrim on a 2400px screen.
		Column(Modifier.verticalScroll(rememberScrollState())) {
			Text(
				text = "Simple Anki",
				modifier = Modifier.padding(16.dp),
				style = MaterialTheme.typography.titleLarge
			)
			HorizontalDivider()
			DrawerEntry("Flip Cards", current == Screen.FlipCards) { onSelect(Screen.FlipCards) }
			HorizontalDivider()
			for (view in views) {
				DrawerEntry(view.name, current is Screen.Table && current.viewId == view.id) {
					onSelect(Screen.Table(view.id))
				}
			}
			// Task 15 adds a third divider and the metronome switch here.
		}
	}
}

@Composable
private fun DrawerEntry(label: String, selected: Boolean, onClick: () -> Unit) {
	NavigationDrawerItem(
		label = { Text(label) },
		selected = selected,
		onClick = onClick,
		modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
	)
}
