package net.jacoblo.simpleanki

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Style
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
 * The app bar: the lifetime review count, then one button per screen.
 *
 * [lifetimeReviews] is every card ever shown, timeouts included. It is passed in rather
 * than read from the container so the bar stays a pure function of its arguments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiTopBar(lifetimeReviews: Int, onNavigate: (Screen) -> Unit) {
	TopAppBar(
		title = { Text("Simple Anki") },
		actions = {
			// 2) Lifetime review counter
			Text(
				text = "$lifetimeReviews",
				modifier = Modifier
					.align(Alignment.CenterVertically)
					.padding(end = 8.dp),
				style = MaterialTheme.typography.titleMedium
			)

			// 6.4) Navigation Icons
			IconButton(onClick = { onNavigate(Screen.HOME) }) {
				Icon(Icons.Default.Home, contentDescription = "Home")
			}
			IconButton(onClick = { onNavigate(Screen.STATS) }) {
				Icon(Icons.Default.List, contentDescription = "Stats")
			}
			IconButton(onClick = { onNavigate(Screen.HISTORY) }) {
				Icon(Icons.Default.DateRange, contentDescription = "History")
			}
			IconButton(onClick = { onNavigate(Screen.QUESTIONS) }) {
				Icon(Icons.Default.Style, contentDescription = "Questions")
			}
		}
	)
}
