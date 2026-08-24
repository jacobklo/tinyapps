/*
 * The Runs button's picker: one drill's recent runs, one tappable line each.
 *
 * Holds layout and nothing else. The ordering is asked of DrillStatsTable.order under that
 * object's own default sort, and each of the three figures on a line is asked of the very
 * function the matching stats column is drawn with. "Newest first", "when a run was" and "how
 * it scored" are one rule each, and a second copy of any of them would agree today and drift the
 * first time either was touched - leaving the picker contradicting the table it exists to help
 * the user get back to, which is not a disagreement anybody would think to go looking for.
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.DrillRun
import java.time.ZoneId

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
				// A scrolling Column and not a LazyColumn, and not for want of a bounded
				// height: AlertDialog gives its text slot a weighted Box, so the constraint
				// here is bounded and a lazy list would work perfectly well. It is a size
				// judgement. [PICKER_LIMIT] lines of one Text each are nothing to lay out at
				// once, so recycling would buy nothing and cost the stable keys that a lazy
				// list needs to not reuse one run's row for another.
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
		// Monospaced, which is the only thing that makes the three figures line up into columns:
		// the separators in [lineFor] are literal spaces, and in a proportional face an
		// accuracy of "-", "86%" or "100%" would leave fifty lines ragged. The content is
		// digits, colons and a percent sign, so nothing here suffers for the face.
		style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
	)
}

/**
 * `08-24 14:32:07   01:12   86%` - when the run was, how long it took, and how it scored.
 *
 * Every one of the three is asked of the function the matching stats column is drawn with, so a
 * line here and that run's row in the table are the same characters by construction rather than
 * by promise. That is what lets a user who spotted a run in the table pick it back out of this
 * list, and it is why none of the three is formatted here: a hand copy would read identically
 * on the day it was written, which is exactly what makes a later drift invisible.
 *
 * The accuracy comes back as [net.jacoblo.simpleanki.table.TableEngine.EMPTY_CELL] for the empty
 * run only a hand-edited file can produce - see [DrillRun.accuracy] for why that case is a dash
 * and not a zero.
 *
 * The zone is fetched per call rather than held, so a device that crosses a timezone while the
 * app is alive dates the next line it draws in the zone it is now in.
 */
private fun lineFor(run: DrillRun): String {
	val started = DrillStatsTable.whenText(run, ZoneId.systemDefault())
	return "$started   ${DrillOps.minutesSeconds(run.seconds)}   ${DrillStatsTable.accuracyText(run)}"
}
