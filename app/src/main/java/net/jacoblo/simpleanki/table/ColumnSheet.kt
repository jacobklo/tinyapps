/*
 * The column sheet: which columns a view shows, how each one is sized and ordered, and
 * the view's own lifecycle.
 *
 * Order and width used to be header drags on the page, and are buttons and a field here
 * instead; table.html has both drags switched off to match. A drag inside a grid that
 * scrolls in both directions is a gesture the user has to win a fight with, and neither
 * drag could reach a column that was hidden or off the right-hand edge. The page's
 * columnMoved and columnResized handlers are left in place, because they are how any
 * later header affordance would report the same two edits, and both land on the same
 * pure functions the buttons and the field below do.
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.BuildResult
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.FieldResult
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.data.buildComputedSpec
import net.jacoblo.simpleanki.data.builderSeed
import net.jacoblo.simpleanki.data.generatedTitle
import net.jacoblo.simpleanki.data.parseColumnWidth

/**
 * Shows, hides, orders, sizes and builds columns, and creates, renames and deletes views.
 *
 * @param warnings the last render's problems, one line each. This is the only place a
 *   "#ERR" column's explanation is shown - the page renders the marker and nothing else,
 *   and putting the text there would mean putting user-derived text into markup.
 * @param onToggleVisible fires for a base column the view carries no spec for as well, in
 *   which case the caller ADDS the spec; see ViewOps.toggleColumn.
 * @param onCollapseOn names the base column to fold duplicate rows on, or null to show
 *   every row. Only base columns are offered; see ViewOps.collapseOn for why.
 * @param onMoveColumn moves a column one place along the view's own column list, hidden
 *   columns counted. The buttons at either end are disabled rather than no-ops.
 * @param onSetWidth fires only on a width that parsed; a refused one shows inline and is
 *   not written.
 * @param onAddComputed receives a spec whose id is the user's title verbatim. The caller
 *   derives the real id, since only it knows what is already taken.
 * @param onEditComputed receives the id of an existing computed column and its rebuilt
 *   spec. The caller replaces it in place, id and position kept.
 * @param tableSettings supplies the window size and limit the computed-column builder
 *   opens at, which is the whole of "default 100 and user settable" on the UI side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnSheet(
	view: TableView,
	tableSettings: TableSettings,
	warnings: List<String>,
	onToggleVisible: (columnId: String) -> Unit,
	onCollapseOn: (columnId: String?) -> Unit,
	onMoveColumn: (columnId: String, delta: Int) -> Unit,
	onSetWidth: (columnId: String, width: Int) -> Unit,
	onAddComputed: (ColumnSpec) -> Unit,
	onEditComputed: (columnId: String, spec: ColumnSpec) -> Unit,
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
	// The computed column whose builder is open, or null for none. One at a time, and the
	// adder counts as one: two open builders on a phone-height sheet is two sets of
	// pickers with nothing on screen saying which column either belongs to.
	var editing by remember(view.id) { mutableStateOf<String?>(null) }

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
			// The eight base columns and nothing else. A computed column cannot be a
			// collapse key and an unknown one is a typo; both only warn, so neither is
			// worth offering. TableEngine still covers a hand-edited views.json.
			Picker(
				label = "Rows",
				value = view.collapseDuplicatesOn ?: SHOW_ALL_ROWS,
				options = remember { listOf(SHOW_ALL_ROWS) + TableEngine.BASE_COLUMNS.map { it.id } }
			) { picked -> onCollapseOn(picked.takeIf { it != SHOW_ALL_ROWS }) }
			Text(
				text = "Pick a column to keep only the first row of each of its values.",
				modifier = Modifier.padding(top = 4.dp),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)

			HorizontalDivider(Modifier.padding(vertical = 8.dp))
			for (entry in remember(view) { entriesOf(view) }) {
				// Keyed on the id rather than left positional: the move buttons reorder
				// this very list, and a positionally remembered width field would stay
				// behind and start editing whichever column slid into its place.
				key(entry.id) {
					ColumnRow(
						entry = entry,
						editing = editing == entry.id,
						onToggleVisible = onToggleVisible,
						onMove = onMoveColumn,
						onSetWidth = onSetWidth,
						onEdit = { id ->
							editing = id
							if (id != null) building = false
						},
						onRemoveColumn = onRemoveColumn
					)
					if (editing == entry.id && entry.computed != null) {
						ComputedBuilder(
							tableSettings = tableSettings,
							initial = entry.computed,
							initialTitle = entry.title,
							confirmLabel = "Save",
							onCancel = { editing = null },
							onConfirm = { spec ->
								onEditComputed(entry.id, spec)
								editing = null
							}
						)
					}
				}
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
					tableSettings = tableSettings,
					initial = null,
					initialTitle = "",
					confirmLabel = "Add",
					onCancel = { building = false },
					onConfirm = { spec ->
						onAddComputed(spec)
						building = false
					}
				)
			} else {
				TextButton(onClick = {
					building = true
					editing = null
				}) {
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

/** The collapse dropdown's entry for "do not collapse at all", which stores a null. */
private const val SHOW_ALL_ROWS = "Show all rows"

/**
 * One line of the checkbox list.
 *
 * [detail] is null for a base column. [width] is null for a base column the view carries
 * no spec for: it is in the list to be ticked and has no position, no width, and nothing
 * to move.
 */
private data class ColumnEntry(
	val id: String,
	val title: String,
	val detail: String?,
	val visible: Boolean,
	val removable: Boolean,
	val width: Int?,
	val canMoveUp: Boolean,
	val canMoveDown: Boolean,
	val computed: ComputedSpec?
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
	val last = view.columns.size - 1
	val listed = view.columns.mapIndexed { index, spec ->
		val base = TableEngine.baseColumn(spec.id)
		ColumnEntry(
			id = spec.id,
			title = spec.title,
			// The formula mirror, which every computed column carries: the builder writes
			// one, ViewsRepository regenerates one from the struct on the way in, and a
			// hand-written column is nothing but one. Null only for a plain column.
			detail = spec.formula,
			visible = spec.visible,
			// Base columns only ever hide; see ViewOps.removeColumn for why.
			removable = base == null,
			width = spec.width,
			canMoveUp = index > 0,
			canMoveDown = index < last,
			// The struct, not the mirror. A column carrying only a formula that failed to
			// parse has no struct, and there is nothing for the pickers to open at - the
			// builder cannot express a formula it could not read.
			//
			// Null for a base column whatever it carries, for the reason it is never
			// removable: TableEngine reads it as the base column regardless, so a struct
			// hand-edited onto one is not what renders. ViewOps.replaceComputed refuses
			// it too; this is what keeps the button that would be refused off the row.
			computed = if (base == null) spec.computed else null
		)
	}
	val missing = TableEngine.BASE_COLUMNS
		.filter { it.id !in carried }
		.map {
			ColumnEntry(
				id = it.id,
				title = it.id,
				detail = null,
				visible = false,
				removable = false,
				width = null,
				canMoveUp = false,
				canMoveDown = false,
				computed = null
			)
		}
	return listed + missing
}

@Composable
private fun ColumnRow(
	entry: ColumnEntry,
	editing: Boolean,
	onToggleVisible: (String) -> Unit,
	onMove: (columnId: String, delta: Int) -> Unit,
	onSetWidth: (columnId: String, width: Int) -> Unit,
	onEdit: (columnId: String?) -> Unit,
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
		if (entry.computed != null) {
			// A toggle rather than an open: the builder appears directly under this row,
			// so the button that opened it is the obvious one to shut it again.
			IconButton(onClick = { onEdit(if (editing) null else entry.id) }) {
				Icon(Icons.Default.Edit, contentDescription = "Edit column ${entry.title}")
			}
		}
		if (entry.removable) {
			IconButton(onClick = { onRemoveColumn(entry.id) }) {
				Icon(Icons.Default.Delete, contentDescription = "Delete column ${entry.title}")
			}
		}
	}
	// Outside the toggleable row above, deliberately. A tap anywhere in that row flips
	// the checkbox, and a width field or an arrow living inside it would either hide the
	// column it was aimed at or fight the parent for the gesture.
	if (entry.width != null) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 24.dp, bottom = 8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			IconButton(onClick = { onMove(entry.id, -1) }, enabled = entry.canMoveUp) {
				Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${entry.title} up")
			}
			IconButton(onClick = { onMove(entry.id, 1) }, enabled = entry.canMoveDown) {
				Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${entry.title} down")
			}
			WidthField(entry.width) { width -> onSetWidth(entry.id, width) }
		}
	}
}

/**
 * The column's width in pixels, applied on the keystroke that made it parse.
 *
 * The text is remembered here rather than derived from [initial], because the two
 * disagree on purpose while the field is being retyped: clearing "120" to type "80"
 * passes through the empty string, which is no width and must stay on screen rather than
 * being replaced by the last accepted number.
 *
 * [initial] is read once, when the row is composed, and is deliberately not a remember
 * key. Keying on it would reset the field on the very keystroke that was accepted, since
 * accepting writes back through the caller and changes [initial].
 */
@Composable
private fun WidthField(initial: Int, onAccepted: (Int) -> Unit) {
	var text by remember { mutableStateOf(initial.toString()) }
	val parsed = parseColumnWidth(text)
	OutlinedTextField(
		value = text,
		onValueChange = { typed ->
			text = typed
			val result = parseColumnWidth(typed)
			if (result is FieldResult.Ok) onAccepted(result.value)
		},
		modifier = Modifier
			.width(150.dp)
			.padding(start = 8.dp),
		label = { Text("Width") },
		// Nothing at all while the field is fine, so an untouched row costs one line
		// rather than two. The refusal is the only thing worth the height.
		supportingText = (parsed as? FieldResult.Err)?.let { error -> { Text(error.message) } },
		isError = parsed is FieldResult.Err,
		keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
		singleLine = true
	)
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
 * Builds a computed column from pickers rather than from typed text, or edits one that
 * already exists.
 *
 * The two are the same screen because they must accept the same things. [initial] is the
 * struct being edited, and null when adding; every picker opens on it, so an edit that
 * changes nothing rebuilds the column it started from.
 *
 * The user never writes a formula here, so nothing they can pick is malformed - but the
 * pickers are independent, so the PAIRING still can be. Everything except collecting the
 * picks belongs to [buildComputedSpec], which validates it; this only shows the refusal
 * and disables the button, so no invalid spec is ever handed on.
 */
@Composable
private fun ComputedBuilder(
	tableSettings: TableSettings,
	initial: ComputedSpec?,
	initialTitle: String,
	confirmLabel: String,
	onCancel: () -> Unit,
	onConfirm: (ColumnSpec) -> Unit
) {
	// Every base column but "#", which names a row's position in the finished table rather
	// than anything about the row. It is neither a value to aggregate - TableEngine.
	// numericSource returns NaN for it - nor a key worth grouping on, since every row
	// would answer the same. Dropped from the picker so the easy path cannot build one;
	// the engine covers the hand-edited path.
	val sources = remember { TableEngine.BASE_COLUMNS.map { it.id } - TableEngine.ID_INDEX }
	// Every opening value, including which of the two number fields gets the column's own
	// number and which gets a default. See ViewOps.builderSeed.
	val seed = remember(initial) { builderSeed(initial, tableSettings) }
	var aggregate by remember { mutableStateOf(seed.aggregate) }
	var source by remember { mutableStateOf(seed.source) }
	var mode by remember { mutableStateOf(modeOf(seed.partition)) }
	var by by remember { mutableStateOf(seed.groupKey) }
	// Text rather than Int so the fields can be empty while they are being retyped, which
	// is what a zero here means. buildComputedSpec settles what a zero size becomes; a
	// zero limit is the struct's own spelling of "every member".
	var size by remember { mutableStateOf(seed.size.toString()) }
	var limit by remember { mutableStateOf(seed.limit.toString()) }
	// The stored title verbatim, not the generated one. A column the user named keeps its
	// name across an edit, and one they did not still shows the generated title, which is
	// what the placeholder underneath it says it would get anyway.
	var title by remember { mutableStateOf(initialTitle) }

	val built = buildComputedSpec(
		aggregate = aggregate,
		source = source,
		partition = when (mode) {
			MODE_GROUP -> Partition.Group(by)
			MODE_BUCKET -> Partition.Bucket(size.toIntOrNull() ?: 0)
			else -> Partition.Rolling(size.toIntOrNull() ?: 0)
		},
		limit = if (mode == MODE_GROUP) limit.toIntOrNull() ?: 0 else 0,
		title = title,
		tableSettings = tableSettings
	)
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
			placeholder = { Text(generatedTitle(aggregate, source)) },
			singleLine = true
		)
		if (built is BuildResult.Err) {
			// Shown the same way a render warning above is, and carrying the very message
			// the typed formula would have given. The button is disabled rather than left
			// live, so the pairing cannot be stored at all - a column that renders "-" in
			// every cell is not worth offering, and this text says why it is refused.
			Text(
				text = built.message,
				modifier = Modifier.padding(top = 8.dp),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error
			)
		}
		Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
			TextButton(onClick = onCancel) { Text("Cancel") }
			Button(
				onClick = { if (built is BuildResult.Ok) onConfirm(built.spec) },
				enabled = built is BuildResult.Ok
			) { Text(confirmLabel) }
		}
	}
}

private const val MODE_GROUP = "group"
private const val MODE_BUCKET = "bucket"
private const val MODE_ROLLING = "rolling"

/** Which of the three partition buttons [partition] shows as pressed. */
private fun modeOf(partition: Partition): String = when (partition) {
	is Partition.Group -> MODE_GROUP
	is Partition.Bucket -> MODE_BUCKET
	is Partition.Rolling -> MODE_ROLLING
}

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
