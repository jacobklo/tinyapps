/*
 * The settings screen: every preference settings.json holds, except the one that deletes
 * data when it is lowered.
 *
 * There is no save button and no cancel, exactly as in the column sheet: a field that
 * parses is applied and written on the keystroke that made it parse, and a field that
 * does not parse shows why and writes nothing. Closing the screen therefore discards
 * nothing, because nothing was ever pending.
 *
 * Every rule about what a field will accept lives in data/SettingsOps.kt. This file
 * collects text and shows refusals; it decides nothing.
 */
package net.jacoblo.simpleanki

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.FieldResult
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.parseCellSizeDp
import net.jacoblo.simpleanki.data.parseColumnCount
import net.jacoblo.simpleanki.data.parseDefaultLimit
import net.jacoblo.simpleanki.data.parseHexColor
import net.jacoblo.simpleanki.data.parseHighlightEvery
import net.jacoblo.simpleanki.data.parseIntervalSeconds
import net.jacoblo.simpleanki.data.parseItemCount
import net.jacoblo.simpleanki.data.parseWindowSize
import net.jacoblo.simpleanki.data.soundPathOrNull

/**
 * Shows and edits [settings], raising [onSettings] with the whole updated document every
 * time a field is accepted.
 *
 * The whole document rather than one changed field, because [Settings] is what
 * SettingsRepository saves and the caller would only have to reassemble it. The repository
 * merges onto what is on disk, so a key this build does not know about survives the write.
 */
@Composable
fun SettingsScreen(
	settings: Settings,
	onSettings: (Settings) -> Unit,
	modifier: Modifier = Modifier
) {
	Column(
		modifier
			.verticalScroll(rememberScrollState())
			.imePadding()
			.padding(horizontal = 16.dp)
			.padding(bottom = 24.dp)
	) {
		SectionHeader("Metronome")
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text("Enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
			Switch(
				checked = settings.metronome.enabled,
				onCheckedChange = { on ->
					onSettings(settings.copy(metronome = settings.metronome.copy(enabled = on)))
				}
			)
		}
		ValidatedField(
			label = "Interval (seconds)",
			initial = settings.metronome.intervalSeconds.toString(),
			keyboardType = KeyboardType.Decimal,
			parse = ::parseIntervalSeconds
		) { seconds ->
			onSettings(settings.copy(metronome = settings.metronome.copy(intervalSeconds = seconds)))
		}
		// No validation, and none to do: any text is a path, and whether it names a
		// readable sound is not something this screen can answer. ClickPlayer raises a
		// Toast and falls back to the bundled click at the first tick, which is the only
		// point at which the answer is actually known.
		PlainField(
			label = "Sound path",
			initial = settings.metronome.soundPath.orEmpty(),
			supporting = "Leave empty for the bundled click"
		) { typed ->
			onSettings(settings.copy(metronome = settings.metronome.copy(soundPath = soundPathOrNull(typed))))
		}

		SectionHeader("Table")
		ValidatedField(
			label = "Highlight every",
			initial = settings.table.highlightEvery.toString(),
			keyboardType = KeyboardType.Number,
			// The band interval belongs to each view, not to the app, so this seeds new
			// ones rather than restyling the views already in views.json. Saying so here
			// is the difference between "nothing happened" and "not to those views".
			supporting = "Rows between tints, 0 for none. Applies to views created or reset from here on",
			parse = ::parseHighlightEvery
		) { every ->
			onSettings(settings.copy(table = settings.table.copy(highlightEvery = every)))
		}
		ColorField(
			label = "Row tint (light theme)",
			initial = settings.table.highlightColorLight
		) { color ->
			onSettings(settings.copy(table = settings.table.copy(highlightColorLight = color)))
		}
		ColorField(
			label = "Row tint (dark theme)",
			initial = settings.table.highlightColorDark
		) { color ->
			onSettings(settings.copy(table = settings.table.copy(highlightColorDark = color)))
		}
		ValidatedField(
			label = "Default window size",
			initial = settings.table.defaultWindowSize.toString(),
			keyboardType = KeyboardType.Number,
			supporting = "Rows a new bucket or rolling column aggregates over",
			parse = ::parseWindowSize
		) { size ->
			onSettings(settings.copy(table = settings.table.copy(defaultWindowSize = size)))
		}
		ValidatedField(
			label = "Default limit",
			initial = settings.table.defaultLimit.toString(),
			keyboardType = KeyboardType.Number,
			supporting = "Attempts a new grouped column keeps, 0 for all of them",
			parse = ::parseDefaultLimit
		) { limit ->
			onSettings(settings.copy(table = settings.table.copy(defaultLimit = limit)))
		}

		SectionHeader("Numbers")
		ValidatedField(
			label = "Items",
			initial = settings.numbers.count.toString(),
			keyboardType = KeyboardType.Number,
			supporting = "How many numbers a fresh set holds",
			parse = ::parseItemCount
		) { count ->
			onSettings(settings.copy(numbers = settings.numbers.copy(count = count)))
		}
		ValidatedField(
			label = "Columns",
			initial = settings.numbers.columns.toString(),
			keyboardType = KeyboardType.Number,
			supporting = COLUMNS_HINT,
			parse = ::parseColumnCount
		) { columns ->
			onSettings(settings.copy(numbers = settings.numbers.copy(columns = columns)))
		}
		ValidatedField(
			label = "Cell width (dp)",
			initial = settings.numbers.cellWidthDp.toString(),
			keyboardType = KeyboardType.Number,
			parse = ::parseCellSizeDp
		) { width ->
			onSettings(settings.copy(numbers = settings.numbers.copy(cellWidthDp = width)))
		}
		ValidatedField(
			label = "Cell height (dp)",
			initial = settings.numbers.cellHeightDp.toString(),
			keyboardType = KeyboardType.Number,
			parse = ::parseCellSizeDp
		) { height ->
			onSettings(settings.copy(numbers = settings.numbers.copy(cellHeightDp = height)))
		}

		SectionHeader("Poker")
		// Geometry only, and no item count anywhere below. A Poker set is one full deck and
		// DrillKind.itemCount answers DECK_SIZE for it without reading settings at all, so a
		// count field here would be a control that writes a key nothing ever reads - a setting
		// the user can change and then watch do nothing.
		ValidatedField(
			label = "Columns",
			initial = settings.poker.columns.toString(),
			keyboardType = KeyboardType.Number,
			supporting = COLUMNS_HINT,
			parse = ::parseColumnCount
		) { columns ->
			onSettings(settings.copy(poker = settings.poker.copy(columns = columns)))
		}
		ValidatedField(
			label = "Cell width (dp)",
			initial = settings.poker.cellWidthDp.toString(),
			keyboardType = KeyboardType.Number,
			parse = ::parseCellSizeDp
		) { width ->
			onSettings(settings.copy(poker = settings.poker.copy(cellWidthDp = width)))
		}
		ValidatedField(
			label = "Cell height (dp)",
			initial = settings.poker.cellHeightDp.toString(),
			keyboardType = KeyboardType.Number,
			parse = ::parseCellSizeDp
		) { height ->
			onSettings(settings.copy(poker = settings.poker.copy(cellHeightDp = height)))
		}

		SectionHeader("History")
		// Read-only on purpose, and disabled rather than merely uneditable so there is no
		// field to focus and no cursor to put in it. Lowering this truncates history.json
		// on the next flip and the discarded attempts are gone for good - a number that
		// destructive should cost more than a mistyped digit on a phone.
		OutlinedTextField(
			value = settings.history.maxEntries.toString(),
			onValueChange = {},
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 8.dp),
			enabled = false,
			readOnly = true,
			label = { Text("Max entries") },
			singleLine = true
		)
		Text(
			text = "Edited in settings.json only. Lowering it permanently deletes the oldest practice records on the next card flip.",
			modifier = Modifier.padding(top = 4.dp, start = 16.dp),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

/**
 * Said under both column fields, because the grid scrolling is what makes a number that does not
 * fit look like a bug rather than like the size that was asked for.
 */
private const val COLUMNS_HINT = "Cells per row. The grid scrolls when a row is wider than the screen"

@Composable
private fun SectionHeader(title: String) {
	HorizontalDivider(Modifier.padding(top = 16.dp))
	Text(
		text = title,
		modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
		style = MaterialTheme.typography.titleMedium,
		color = MaterialTheme.colorScheme.primary
	)
}

/**
 * A field whose text is parsed on every keystroke, raising [onAccepted] only when it
 * parses and showing the refusal inline when it does not.
 *
 * The text is remembered here rather than derived from the caller's value, because the
 * two disagree on purpose while a field is being retyped: clearing "10" to type "25"
 * passes through the empty string, which no parser accepts and which must therefore stay
 * on screen rather than being replaced by the last accepted value.
 *
 * [initial] is read once, when the screen is entered. It is deliberately not a remember
 * key: keying on it would reset the field on the very keystroke that was accepted, since
 * accepting writes back through the caller and changes [initial].
 */
@Composable
private fun <T> ValidatedField(
	label: String,
	initial: String,
	keyboardType: KeyboardType,
	supporting: String? = null,
	parse: (String) -> FieldResult<T>,
	trailing: @Composable ((current: String) -> Unit)? = null,
	onAccepted: (T) -> Unit
) {
	var text by remember { mutableStateOf(initial) }
	val parsed = parse(text)
	OutlinedTextField(
		value = text,
		onValueChange = { typed ->
			text = typed
			val result = parse(typed)
			if (result is FieldResult.Ok) onAccepted(result.value)
		},
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
		label = { Text(label) },
		// Handed the live text rather than the accepted value, so a swatch tracks what is
		// being typed instead of lagging a keystroke behind it.
		trailingIcon = trailing?.let { draw -> { draw(text) } },
		supportingText = {
			// The refusal replaces the hint rather than joining it: a field showing why it
			// was rejected has nothing to gain from also explaining what it is for.
			val message = (parsed as? FieldResult.Err)?.message ?: supporting
			if (message != null) Text(message)
		},
		isError = parsed is FieldResult.Err,
		keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
		singleLine = true
	)
}

/** A field that accepts anything, for a value that has no wrong spelling. */
@Composable
private fun PlainField(
	label: String,
	initial: String,
	supporting: String,
	onChange: (String) -> Unit
) {
	var text by remember { mutableStateOf(initial) }
	OutlinedTextField(
		value = text,
		onValueChange = { typed ->
			text = typed
			onChange(typed)
		},
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp),
		label = { Text(label) },
		supportingText = { Text(supporting) },
		singleLine = true
	)
}

/** A hex colour field with a live swatch of whatever it currently holds. */
@Composable
private fun ColorField(label: String, initial: String, onAccepted: (String) -> Unit) {
	ValidatedField(
		label = label,
		initial = initial,
		keyboardType = KeyboardType.Ascii,
		parse = ::parseHexColor,
		// Nothing while the text is malformed, deliberately: a swatch left showing the last
		// good colour would claim the typed one had been applied when it had not.
		trailing = { current -> (parseHexColor(current) as? FieldResult.Ok)?.let { Swatch(it.value) } },
		onAccepted = onAccepted
	)
}

/**
 * A block of [hex], which must already have passed [parseHexColor].
 *
 * Bordered because the tints are close to their surface by design - that is what a row
 * band is - and an unbordered swatch of one would be invisible against the other.
 */
@Composable
private fun Swatch(hex: String) {
	Box(
		Modifier
			.size(28.dp)
			.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
			.background(Color(("FF" + hex.removePrefix("#")).toLong(16)), RoundedCornerShape(4.dp))
	)
}
