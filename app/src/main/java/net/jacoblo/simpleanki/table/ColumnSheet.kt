/*
 * The column sheet: which columns a view shows, and the view's own lifecycle.
 *
 * Deliberately NOT where a column is resized or reordered. Both are header drags,
 * Tabulator already reports them over the bridge, and rebuilding either as a Compose list
 * gesture would be a great deal of pointer maths for a worse result.
 *
 * Every edit here is applied by a pure function in ViewOps.kt and autosaved by the
 * caller. There is no save button and no cancel: closing the sheet discards nothing
 * because nothing was pending.
 */
package net.jacoblo.simpleanki.table

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.NEW_COLUMN_WIDTH
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.TableView

/**
 * Shows and hides columns, builds computed ones, and creates, renames, and deletes views.
 *
 * @param warnings the last render's problems, one line each. This is the only place a
 *   "#ERR" column's explanation is shown - the page renders the marker and nothing else,
 *   and putting the text there would mean putting user-derived text into markup.
 * @param onToggleVisible fires for a base column the view carries no spec for as well, in
 *   which case the caller ADDS the spec; see ViewOps.toggleColumn.
 * @param onAddComputed receives a spec whose id is the user's title verbatim. The caller
 *   derives the real id, since only it knows what is already taken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnSheet(
	view: TableView,
	warnings: List<String>,
	onToggleVisible: (columnId: String) -> Unit,
	onAddComputed: (ColumnSpec) -> Unit,
	onRemoveColumn: (columnId: String) -> Unit,
	onSaveAsNew: (name: String) -> Unit,
	onRename: (name: String) -> Unit,
	onDelete: () -> Unit,
	onResetDefaults: () -> Unit,
	onDismiss: () -> Unit
) {
	// Keyed on the view so switching views - which a save-as-new or a delete does from
	// inside this sheet - closes whatever editor was open rather than carrying it over
	// onto a view it was not started against.
	var naming by remember(view.id) { mutableStateOf(NameMode.NONE) }
	var building by remember(view.id) { mutableStateOf(false) }

	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(
			Modifier
				.verticalScroll(rememberScrollState())
				.imePadding()
				.padding(horizontal = 16.dp)
				.padding(bottom = 24.dp)
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = view.name,
					modifier = Modifier.weight(1f),
					style = MaterialTheme.typography.titleLarge
				)
				IconButton(onClick = { naming = NameMode.RENAME }) {
					Icon(Icons.Default.Edit, contentDescription = "Rename this view")
				}
			}
			if (naming != NameMode.NONE) {
				NameEditor(
					label = if (naming == NameMode.RENAME) "New name" else "Name of the copy",
					initial = if (naming == NameMode.RENAME) view.name else "${view.name} copy",
					onCancel = { naming = NameMode.NONE },
					onConfirm = { name ->
						if (naming == NameMode.RENAME) onRename(name) else onSaveAsNew(name)
						naming = NameMode.NONE
					}
				)
			}

			HorizontalDivider(Modifier.padding(vertical = 8.dp))
			for (entry in remember(view) { entriesOf(view) }) {
				ColumnRow(entry, onToggleVisible, onRemoveColumn)
			}

			if (warnings.isNotEmpty()) {
				HorizontalDivider(Modifier.padding(vertical = 8.dp))
				for (warning in warnings) {
					Text(
						text = warning,
						modifier = Modifier.padding(vertical = 2.dp),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error
					)
				}
			}

			HorizontalDivider(Modifier.padding(vertical = 8.dp))
			if (building) {
				ComputedBuilder(
					onCancel = { building = false },
					onAdd = { spec ->
						onAddComputed(spec)
						building = false
					}
				)
			} else {
				TextButton(onClick = { building = true }) {
					Icon(Icons.Default.Add, contentDescription = null)
					Text("Add computed column", Modifier.padding(start = 8.dp))
				}
			}

			HorizontalDivider(Modifier.padding(vertical = 8.dp))
			TextButton(onClick = { naming = NameMode.SAVE_AS }) { Text("Save as new view") }
			TextButton(onClick = onResetDefaults) { Text("Reset to defaults") }
			TextButton(onClick = onDelete) {
				Text("Delete view", color = MaterialTheme.colorScheme.error)
			}
		}
	}
}

/** Which name the [NameEditor] is collecting, or [NONE] when it is not showing. */
private enum class NameMode { NONE, RENAME, SAVE_AS }

/** One line of the checkbox list. [detail] is null for a base column. */
private data class ColumnEntry(
	val id: String,
	val title: String,
	val detail: String?,
	val visible: Boolean,
	val removable: Boolean
)

/**
 * Every column the sheet offers: the view's own, then any base column it carries no spec
 * for, shown unticked.
 *
 * The tail is what makes all eight base columns always available. The Stats view carries
 * two specs, so without it there would be no way to put Answer back on screen short of
 * hand-editing views.json.
 */
private fun entriesOf(view: TableView): List<ColumnEntry> {
	val carried = view.columns.map { it.id }.toSet()
	val listed = view.columns.map { spec ->
		ColumnEntry(
			id = spec.id,
			title = spec.title,
			// Task 12 generates the formula mirror; until it exists the struct is what
			// there is to show, and it says the same thing in more words.
			detail = spec.formula ?: spec.computed?.let { describe(it) },
			visible = spec.visible,
			// Base columns only ever hide; see ViewOps.removeColumn for why.
			removable = TableEngine.baseColumn(spec.id) == null
		)
	}
	val missing = TableEngine.BASE_COLUMNS
		.filter { it.id !in carried }
		.map { ColumnEntry(it.id, it.id, null, visible = false, removable = false) }
	return listed + missing
}

/** A [ComputedSpec] in words, standing in for the formula Task 12 will generate. */
private fun describe(spec: ComputedSpec): String {
	val over = when (val partition = spec.partition) {
		is Partition.Group ->
			"grouped by ${partition.by}" + if (spec.limit > 0) ", last ${spec.limit}" else ""
		is Partition.Bucket -> "in buckets of ${partition.size}"
		is Partition.Rolling -> "rolling ${partition.size}"
	}
	return "${spec.aggregate.name} of ${spec.source}, $over"
}

@Composable
private fun ColumnRow(
	entry: ColumnEntry,
	onToggleVisible: (String) -> Unit,
	onRemoveColumn: (String) -> Unit
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			// toggleable rather than clickable, and on the row rather than on the
			// checkbox: it makes the whole line the target, and it is what puts the
			// ticked/unticked state into the semantics tree. A bare clickable row around
			// a Checkbox reads to a screen reader - and to uiautomator - as a button with
			// no state at all.
			.toggleable(
				value = entry.visible,
				role = Role.Checkbox,
				onValueChange = { onToggleVisible(entry.id) }
			),
		verticalAlignment = Alignment.CenterVertically
	) {
		// Null rather than a second handler: the row owns the toggle, and a separately
		// clickable checkbox inside it would fire twice on the taps that land on both.
		Checkbox(checked = entry.visible, onCheckedChange = null)
		Column(
			Modifier
				.weight(1f)
				.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
		) {
			Text(entry.title, style = MaterialTheme.typography.bodyLarge)
			if (entry.detail != null) {
				Text(
					text = entry.detail,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		if (entry.removable) {
			IconButton(onClick = { onRemoveColumn(entry.id) }) {
				Icon(Icons.Default.Delete, contentDescription = "Delete column ${entry.title}")
			}
		}
	}
}

/** Collects one name. Confirming is refused while the field is blank. */
@Composable
private fun NameEditor(
	label: String,
	initial: String,
	onCancel: () -> Unit,
	onConfirm: (String) -> Unit
) {
	var text by remember(label, initial) { mutableStateOf(initial) }
	OutlinedTextField(
		value = text,
		onValueChange = { text = it },
		modifier = Modifier.fillMaxWidth(),
		label = { Text(label) },
		singleLine = true
	)
	Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
		TextButton(onClick = onCancel) { Text("Cancel") }
		TextButton(
			onClick = { onConfirm(text.trim()) },
			enabled = text.isNotBlank()
		) { Text("OK") }
	}
}

/**
 * Builds a [ComputedSpec] from pickers rather than from typed text.
 *
 * The user never writes a formula here. Task 12 adds the formula mirror and its parser;
 * this collects the struct the engine actually reads, which cannot be malformed.
 */
@Composable
private fun ComputedBuilder(onCancel: () -> Unit, onAdd: (ColumnSpec) -> Unit) {
	// Every base column but "#", which names a row's position in the finished table rather
	// than anything about the row. It is neither a value to aggregate - TableEngine.
	// numericSource returns NaN for it - nor a key worth grouping on, since every row
	// would answer the same. Dropped from the picker so the easy path cannot build one;
	// the engine covers the hand-edited path.
	val sources = remember { TableEngine.BASE_COLUMNS.map { it.id } - TableEngine.ID_INDEX }
	var aggregate by remember { mutableStateOf(Aggregate.AVG) }
	var source by remember { mutableStateOf(TableEngine.ID_SECONDS) }
	var mode by remember { mutableStateOf(MODE_GROUP) }
	var by by remember { mutableStateOf(TableEngine.ID_QUESTION) }
	// Text rather than Int so the field can be empty while it is being retyped. Task 10
	// defines what a size or a limit of zero means; nothing reads either yet.
	var size by remember { mutableStateOf("10") }
	var limit by remember { mutableStateOf("10") }
	var title by remember { mutableStateOf("") }

	val generated = "${aggregate.name} $source"
	Column(Modifier.fillMaxWidth()) {
		Picker("Aggregate", aggregate.name, remember { Aggregate.entries.map { it.name } }) { picked ->
			aggregate = Aggregate.valueOf(picked)
		}
		Picker("Of", source, sources) { source = it }
		Picker("Partition", mode, remember { listOf(MODE_GROUP, MODE_BUCKET, MODE_ROLLING) }) {
			mode = it
		}
		if (mode == MODE_GROUP) {
			Picker("By", by, sources) { by = it }
			NumberField("Limit", limit) { limit = it }
		} else {
			NumberField("Size", size) { size = it }
		}
		OutlinedTextField(
			value = title,
			onValueChange = { title = it },
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 8.dp),
			label = { Text("Title") },
			placeholder = { Text(generated) },
			singleLine = true
		)
		Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
			TextButton(onClick = onCancel) { Text("Cancel") }
			Button(onClick = {
				val name = title.trim().ifEmpty { generated }
				onAdd(
					ColumnSpec(
						// The user's title verbatim; ViewOps.addComputed derives the id it
						// is actually stored under.
						id = name,
						title = name,
						width = NEW_COLUMN_WIDTH,
						computed = ComputedSpec(
							aggregate = aggregate,
							source = source,
							partition = when (mode) {
								MODE_GROUP -> Partition.Group(by)
								MODE_BUCKET -> Partition.Bucket(size.toIntOrNull() ?: 0)
								else -> Partition.Rolling(size.toIntOrNull() ?: 0)
							},
							limit = if (mode == MODE_GROUP) limit.toIntOrNull() ?: 0 else 0
						)
					)
				)
			}) { Text("Add") }
		}
	}
}

private const val MODE_GROUP = "group"
private const val MODE_BUCKET = "bucket"
private const val MODE_ROLLING = "rolling"

/** One choice out of [options], as a button that opens a menu. */
@Composable
private fun Picker(
	label: String,
	value: String,
	options: List<String>,
	onPick: (String) -> Unit
) {
	var open by remember { mutableStateOf(false) }
	Box(Modifier.padding(top = 8.dp)) {
		OutlinedButton(onClick = { open = true }) { Text("$label: $value") }
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			for (option in options) {
				DropdownMenuItem(
					text = { Text(option) },
					onClick = {
						open = false
						onPick(option)
					}
				)
			}
		}
	}
}

/** Digits only, so the value always parses or is empty. */
@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
	OutlinedTextField(
		value = value,
		onValueChange = { typed -> onChange(typed.filter { it.isDigit() }) },
		modifier = Modifier.padding(top = 8.dp),
		label = { Text(label) },
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		singleLine = true
	)
}
