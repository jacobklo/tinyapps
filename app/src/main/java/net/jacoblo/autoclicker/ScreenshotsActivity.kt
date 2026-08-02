package net.jacoblo.autoclicker

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme

class ScreenshotsActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			AutoClickerTheme {
				ScreenshotsScreen(onBack = { finish() })
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotsScreen(onBack: () -> Unit) {
	val revision by ScreenshotStore.revision.collectAsState()
	val shots = remember(revision) { ScreenshotStore.list() }
	var selected by remember { mutableStateOf(setOf<String>()) }
	var renaming by remember { mutableStateOf<Screenshot?>(null) }
	var confirmDelete by remember { mutableStateOf(false) }

	// A deleted or renamed entry must not stay selected.
	LaunchedEffect(revision) {
		selected = selected.intersect(shots.map { it.name }.toSet())
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(if (selected.isEmpty()) "Screen areas" else "${selected.size} selected") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
					}
				},
				actions = {
					if (selected.size == 1) {
						IconButton(onClick = { renaming = shots.firstOrNull { it.name in selected } }) {
							Icon(Icons.Default.Edit, contentDescription = "Rename")
						}
					}
					if (selected.isNotEmpty()) {
						IconButton(onClick = { confirmDelete = true }) {
							Icon(Icons.Default.Delete, contentDescription = "Delete")
						}
					}
				}
			)
		}
	) { innerPadding ->
		if (shots.isEmpty()) {
			Box(
				modifier = Modifier.padding(innerPadding).fillMaxSize(),
				contentAlignment = Alignment.Center
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					modifier = Modifier.padding(32.dp)
				) {
					Text("No screen areas yet", style = MaterialTheme.typography.titleMedium)
					Spacer(modifier = Modifier.height(8.dp))
					Text(
						"Tap the yellow button on the bubble and drag out a region to capture one.",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center
					)
				}
			}
			return@Scaffold
		}

		LazyVerticalGrid(
			columns = GridCells.Adaptive(minSize = 160.dp),
			modifier = Modifier.padding(innerPadding).fillMaxSize(),
			contentPadding = PaddingValues(8.dp),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			items(shots, key = { it.name }) { shot ->
				ScreenshotCell(
					shot = shot,
					isSelected = shot.name in selected,
					onToggle = {
						selected = if (shot.name in selected) selected - shot.name else selected + shot.name
					}
				)
			}
		}
	}

	if (confirmDelete) {
		AlertDialog(
			onDismissRequest = { confirmDelete = false },
			title = { Text("Delete ${selected.size} area${if (selected.size == 1) "" else "s"}?") },
			text = { Text("Any condition referring to them by name will stop matching.") },
			confirmButton = {
				Button(onClick = {
					ScreenshotStore.delete(selected)
					selected = emptySet()
					confirmDelete = false
				}) {
					Text("Delete")
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
			}
		)
	}

	val target = renaming
	if (target != null) {
		RenameScreenshotDialog(
			current = target.name,
			onDismiss = { renaming = null },
			onConfirm = { newName ->
				val ok = ScreenshotStore.rename(target.name, newName)
				if (ok) selected = emptySet()
				renaming = null
				ok
			}
		)
	}
}

@Composable
private fun ScreenshotCell(shot: Screenshot, isSelected: Boolean, onToggle: () -> Unit) {
	// Decoding is cheap for these crops but still off the critical path of the
	// first frame; keyed by name so a rename reloads it.
	val thumbnail: Bitmap? = remember(shot.name, shot.fileName) { ScreenshotStore.thumbnail(shot) }

	Column(
		modifier = Modifier
			.clickable { onToggle() }
			.border(
				width = if (isSelected) 3.dp else 1.dp,
				color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
			)
			.padding(4.dp)
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(120.dp)
				.background(MaterialTheme.colorScheme.surfaceVariant),
			contentAlignment = Alignment.Center
		) {
			if (thumbnail != null) {
				Image(
					bitmap = thumbnail.asImageBitmap(),
					contentDescription = shot.name,
					contentScale = ContentScale.Fit,
					modifier = Modifier.fillMaxSize()
				)
			} else {
				Text("(unreadable)", style = MaterialTheme.typography.bodySmall)
			}
		}
		Text(
			text = shot.name,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 1,
			modifier = Modifier.padding(top = 4.dp)
		)
		Text(
			text = "${shot.width}x${shot.height} at ${shot.left},${shot.top}",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

@Composable
private fun RenameScreenshotDialog(
	current: String,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Boolean
) {
	var text by remember { mutableStateOf(current) }
	var failed by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Rename area") },
		text = {
			Column {
				OutlinedTextField(
					value = text,
					onValueChange = {
						text = it
						failed = false
					},
					label = { Text("Name") },
					isError = failed,
					supportingText = if (failed) {
						{ Text("That name is already taken") }
					} else {
						{ Text("Scripts refer to areas by this name") }
					},
					singleLine = true
				)
			}
		},
		confirmButton = {
			Button(
				onClick = { if (!onConfirm(text)) failed = true },
				enabled = text.isNotBlank()
			) {
				Text("Rename")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("Cancel") }
		}
	)
}
