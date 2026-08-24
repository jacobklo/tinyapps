/*
 * The Runs button's picker: one drill's recent runs, one tappable line each.
 *
 * The ordering is asked of DrillStatsTable.order under that object's own default sort rather
 * than spelled out here. "Newest first" is one rule with one tie-break, and a second copy of it
 * would agree today and drift the first time either was touched - leaving the picker listing
 * runs in an order the stats table contradicts, which is not a disagreement anybody would think
 * to go looking for.
 *
 * The cap is the whole difference between this and the stats screen: this lists the last few
 * runs so one can be reopened quickly, that one holds every run there is.
 */
package net.jacoblo.simpleanki.drill

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.table.TableEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How many runs the picker lists.
 *
 * A dialog is the shortcut back to a run the user remembers finishing, and fifty lines is
 * already more scrolling than that. Nothing is hidden by the cap: every older run is one drawer
 * entry away in the stats table, which caps nothing.
 */
const val PICKER_LIMIT = 50

/**
 * The dialog the Runs button opens - [runs] newest first, one tappable line each.
 *
 * @param runs every stored run of the drill, in whatever order storage holds them, which is
 *   oldest first. Ordered and capped in here rather than by the caller, so no caller can list
 *   them the wrong way up or forget the cap.
 * @param onPick raised with the run that was tapped. This dialog does NOT close itself on a
 *   pick - whether it is showing at all is the caller's state, not its own - so the caller owes
 *   a pick the same close it would give [onDismiss].
 * @param onDismiss a tap outside the dialog, the back gesture, or Close.
 */
@Composable
fun RunPicker(
	runs: List<DrillRun>,
	onPick: (DrillRun) -> Unit,
	onDismiss: () -> Unit
) {
	val recent = remember(runs) {
		DrillStatsTable.order(runs, DrillStatsTable.DEFAULT_SORT).take(PICKER_LIMIT)
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
		title = { Text("Runs") },
		text = {
			if (recent.isEmpty()) {
				// A drill nobody has run yet is a normal state, not an error - but a dialog
				// holding a title, a Close button and nothing in between reads as one that
				// failed to load, so it says so instead.
				Text(text = "No runs yet.", style = MaterialTheme.typography.bodyMedium)
			} else {
				// A scrolling Column and not a LazyColumn. Fifty short lines are nothing to lay
				// out at once, and a lazy list needs a bounded height that a dialog's content
				// slot does not promise: given an unbounded one it throws, where this merely
				// stops needing to scroll.
				Column(Modifier.verticalScroll(rememberScrollState())) {
					for (run in recent) {
						RunLine(run = run, onPick = onPick)
					}
				}
			}
		}
	)
}

/**
 * One run's line.
 *
 * The whole width is the tap target and it is padded to the 48dp Material minimum rather than
 * left at the height of its own text: these lines sit directly against one another, so a target
 * shorter than a fingertip is one the user lands on the wrong side of, and the cost of that is
 * opening the wrong run.
 */
@Composable
private fun RunLine(run: DrillRun, onPick: (DrillRun) -> Unit) {
	Text(
		text = lineFor(run),
		// clickable BEFORE padding, so the padding is inside the tap target rather than a dead
		// margin around it.
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onPick(run) }
			.padding(vertical = 14.dp),
		style = MaterialTheme.typography.bodyMedium
	)
}

/**
 * `08-24 14:32:07   01:12   86%` - when the run was, how long it took, and how it scored.
 *
 * Dated with the pattern DrillStatsTable's When column uses and timed through the very same
 * [DrillOps.minutesSeconds], so a line here and that run's row in the stats table read
 * identically. That is what lets a user who spotted a run in the table pick it out of this
 * list. The date pattern is a third copy of the one TableEngine holds - DrillStatsTable's own
 * note explains why the second copy exists - and nothing but hand keeps the three in step.
 *
 * The accuracy is a whole percentage, matching that Accuracy column rather than the tally on
 * the drill screen, or [TableEngine.EMPTY_CELL] for the empty run that only a hand-edited file
 * can produce. See [DrillRun.accuracy] for why that case is a dash and not a zero.
 */
private fun lineFor(run: DrillRun): String {
	val started = WHEN_FORMATTER.withZone(ZoneId.systemDefault())
		.format(Instant.ofEpochMilli(run.startedAt))
	val accuracy = run.accuracy?.let { "%.0f%%".format(Locale.ROOT, it * 100) }
		?: TableEngine.EMPTY_CELL
	return "$started   ${DrillOps.minutesSeconds(run.seconds)}   $accuracy"
}

/**
 * The zone is applied per call rather than baked in here, so a device that crosses a timezone
 * while the app is alive dates the next line it draws in the zone it is now in.
 */
private val WHEN_FORMATTER: DateTimeFormatter =
	DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)
