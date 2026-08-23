package net.jacoblo.simpleanki

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app bar: a hamburger opening the navigation drawer, and the lifetime review count.
 *
 * One button rather than one per screen, because from Task 8 the user can add views and
 * there is no fixed number of destinations to give a button each.
 *
 * [lifetimeReviews] is every card ever shown, timeouts included. It is passed in rather
 * than read from the container so the bar stays a pure function of its arguments.
 *
 * [onOpenColumns] opens the column sheet, and is null on a screen that has no columns to
 * configure - a null hides the action rather than disabling it, since a permanently dead
 * button on the game screen would only ever be noise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiTopBar(lifetimeReviews: Int, onOpenColumns: (() -> Unit)?, onOpenDrawer: () -> Unit) {
	TopAppBar(
		title = { Text("Simple Anki") },
		navigationIcon = {
			IconButton(onClick = onOpenDrawer) {
				Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
			}
		},
		actions = {
			if (onOpenColumns != null) {
				IconButton(onClick = onOpenColumns) {
					Icon(Icons.Default.ViewColumn, contentDescription = "Columns and views")
				}
			}
			// 2) Lifetime review counter
			Text(
				text = "$lifetimeReviews",
				modifier = Modifier
					.align(Alignment.CenterVertically)
					.padding(end = 16.dp),
				style = MaterialTheme.typography.titleMedium
			)
		}
	)
}
