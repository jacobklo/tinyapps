package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme

class TriggersActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			AutoClickerTheme {
				TriggersScreen(onBack = { finish() })
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersScreen(onBack: () -> Unit) {
	val revision by TriggerStore.revision.collectAsState()
	val triggers = remember(revision) { TriggerStore.list() }
	val recordings = remember(revision) { RecordingManager.getRecordings().map { it.name } }
	var editing by remember { mutableStateOf<Trigger?>(null) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Triggers") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
					}
				}
			)
		},
		floatingActionButton = {
			if (recordings.isNotEmpty()) {
				FloatingActionButton(onClick = {
					editing = Trigger(
						id = TriggerStore.nextId(),
						type = TriggerType.APP_OPENED,
						parameter = "",
						recording = recordings.first()
					)
				}) {
					Icon(Icons.Default.Add, contentDescription = "Add trigger")
				}
			}
		}
	) { innerPadding ->
		Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

			if (!AppSettings.useRoot) {
				Text(
					"Triggers need root. Turn on Use Root in Settings.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(16.dp)
				)
			}

			if (recordings.isEmpty()) {
				Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					Text(
						"Record something first -- a trigger has to have a recording to run.",
						style = MaterialTheme.typography.bodyMedium,
						textAlign = TextAlign.Center,
						modifier = Modifier.padding(32.dp)
					)
				}
				return@Column
			}

			if (triggers.isEmpty()) {
				Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						modifier = Modifier.padding(32.dp)
					) {
						Text("No triggers yet", style = MaterialTheme.typography.titleMedium)
						Spacer(modifier = Modifier.height(8.dp))
						Text(
							"Add one to run a recording when an app opens, the screen changes, or a notification arrives.",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textAlign = TextAlign.Center
						)
					}
				}
				return@Column
			}

			LazyColumn(modifier = Modifier.fillMaxSize()) {
				items(triggers, key = { it.id }) { trigger ->
					TriggerRow(
						trigger = trigger,
						onToggle = { TriggerStore.upsert(trigger.copy(enabled = !trigger.enabled)) },
						onEdit = { editing = trigger },
						onDelete = { TriggerStore.delete(trigger.id) }
					)
					HorizontalDivider()
				}
			}
		}
	}

	val target = editing
	if (target != null) {
		TriggerEditor(
			trigger = target,
			recordings = recordings,
			onDismiss = { editing = null },
			onSave = {
				TriggerStore.upsert(it)
				editing = null
			}
		)
	}
}

@Composable
private fun TriggerRow(
	trigger: Trigger,
	onToggle: () -> Unit,
	onEdit: () -> Unit,
	onDelete: () -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onEdit() }
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = if (trigger.type.takesParameter) {
					"${trigger.type.label}: ${trigger.parameter.ifBlank { "(not set)" }}"
				} else {
					trigger.type.label
				},
				style = MaterialTheme.typography.bodyLarge
			)
			Text(
				text = "runs ${trigger.recording.removeSuffix(".json")}",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		Switch(checked = trigger.enabled, onCheckedChange = { onToggle() })
		IconButton(onClick = onDelete) {
			Icon(Icons.Default.Delete, contentDescription = "Delete")
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerEditor(
	trigger: Trigger,
	recordings: List<String>,
	onDismiss: () -> Unit,
	onSave: (Trigger) -> Unit
) {
	var type by remember { mutableStateOf(trigger.type) }
	var parameter by remember { mutableStateOf(trigger.parameter) }
	var recording by remember { mutableStateOf(trigger.recording) }
	var typeMenu by remember { mutableStateOf(false) }
	var recordingMenu by remember { mutableStateOf(false) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Trigger") },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				ExposedDropdownMenuBox(
					expanded = typeMenu,
					onExpandedChange = { typeMenu = !typeMenu }
				) {
					OutlinedTextField(
						value = type.label,
						onValueChange = {},
						readOnly = true,
						label = { Text("When") },
						modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
					)
					ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
						TriggerType.entries.forEach { option ->
							DropdownMenuItem(
								text = { Text(option.label) },
								onClick = {
									type = option
									typeMenu = false
								}
							)
						}
					}
				}

				if (type.takesParameter) {
					Spacer(modifier = Modifier.height(8.dp))
					OutlinedTextField(
						value = parameter,
						onValueChange = { parameter = it },
						label = { Text(type.parameterLabel) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth()
					)
				}

				Spacer(modifier = Modifier.height(8.dp))
				ExposedDropdownMenuBox(
					expanded = recordingMenu,
					onExpandedChange = { recordingMenu = !recordingMenu }
				) {
					OutlinedTextField(
						value = recording.removeSuffix(".json"),
						onValueChange = {},
						readOnly = true,
						label = { Text("Run") },
						modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
					)
					ExposedDropdownMenu(
						expanded = recordingMenu,
						onDismissRequest = { recordingMenu = false }
					) {
						recordings.forEach { option ->
							DropdownMenuItem(
								text = { Text(option.removeSuffix(".json")) },
								onClick = {
									recording = option
									recordingMenu = false
								}
							)
						}
					}
				}
			}
		},
		confirmButton = {
			Button(
				onClick = { onSave(trigger.copy(type = type, parameter = parameter, recording = recording)) },
				enabled = !type.takesParameter || parameter.isNotBlank()
			) {
				Text("Save")
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text("Cancel") }
		}
	)
}
