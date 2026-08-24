/*
 * The two drill destinations: which runs they show, and where every change to one is stored.
 *
 * [DrillRoute] is to the drill screens what TableRoute is to the table screen - it owns the
 * loaded runs, persists every change, and turns a storage failure into a toast - and it keeps
 * that file's "screen first, then disk" rule unchanged. What it adds is a debounce, because a
 * drill autosaves an order of magnitude more often than a table does; see [DrillAutosave].
 *
 * [DrillStatsRoute] is the same ownership of the same file with nothing to write, so the two
 * share the loader and nothing else.
 */
package net.jacoblo.simpleanki

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.DrillRunsRepository
import net.jacoblo.simpleanki.data.JsonStore
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillScreen
import net.jacoblo.simpleanki.drill.DrillStatsScreen
import net.jacoblo.simpleanki.drill.RunPicker
import net.jacoblo.simpleanki.drill.runsFile

/**
 * One drill, with its stored runs behind it.
 *
 * @param openRunId the id of the stored run being re-scored, or null for a live drill. See
 *   [Screen.Drill] for why it is navigation state rather than this route's own.
 * @param onSelect switches the screen. Used for the two moves this route makes itself: opening
 *   a stored run from the picker, and closing the one that is open.
 */
@Composable
fun DrillRoute(
	container: AppContainer,
	kind: DrillKind,
	openRunId: String?,
	onSelect: (Screen) -> Unit
) {
	val context = LocalContext.current
	var runs by rememberStoredRuns(container, kind)
	// Keyed on [kind] for the reason the loader is: both drills arrive through ONE call site, so
	// an unkeyed slot would hand Poker the coalescer holding Numbers' pending write.
	val autosave = remember(kind) { DrillAutosave(container.drillRunsRepository(kind)) }
	// Bumped on every scoring tap and never reset. It counts nothing anybody reads - it is the
	// debounce's key, and all that is asked of it is that a tap changes it.
	var tapCount by remember(kind) { mutableStateOf(0) }
	var pickerOpen by remember(kind) { mutableStateOf(false) }

	/**
	 * Raises the toast for a write that failed, if one is waiting.
	 *
	 * Asked of the coalescer rather than read off a return value, because the flush that fails is
	 * often not the call that can report it - see DrillAutosave.takeFailure.
	 */
	fun reportFailure() {
		if (autosave.takeFailure() != null) reportSaveFailure(context, container, kind)
	}

	/**
	 * Writes whatever is pending, here and now, on the calling thread.
	 *
	 * Synchronous on purpose at every one of its five call sites. ON_PAUSE and onDispose are
	 * moments where a coroutine is about to be cancelled or about to lose its process, so handing
	 * the write to one is precisely how the last marks get lost; Done, New and a pick from the
	 * picker all want the file settled before the next thing reads it, or before the run they
	 * belong to stops being the one on screen.
	 *
	 * New raises onCloseRun in every state, so that call site is the ordinary "start another set"
	 * path as much as it is a run closing. It costs nothing when nothing is pending.
	 *
	 * A whole-file write on the main thread is what this app already does on every card flip and
	 * every column resize. The debounce exists to keep it off the per-TAP path, which is the one
	 * that runs fifty times a minute, and not to ban it outright.
	 */
	fun flushNow() {
		autosave.flush()
		reportFailure()
	}

	// The debounce. Keyed on the tap counter, so every tap cancels the delay the tap before it
	// started and only a gap in the tapping reaches the write. Keyed on [kind] as well, so
	// switching drills cannot leave a delay running against the coalescer it was started for.
	LaunchedEffect(kind, tapCount) {
		// Nothing has been tapped yet, so there is nothing to wait out. Entering a drill would
		// otherwise dispatch a pointless quarter-second and an IO hop on the way in.
		if (tapCount == 0) return@LaunchedEffect
		delay(AUTOSAVE_QUIET_MILLIS)
		// OFF the main thread, which is the whole point: this is a whole-file rewrite, and at the
		// retention cap that is megabytes of JSON. withContext resumes on the dispatcher it was
		// called from, so the report below happens back on main, where a Toast may be raised at
		// all - and when the screen leaves mid-write there is no resume at all, which is exactly
		// why a failure is HELD for the flush on the way out rather than returned to here.
		withContext(Dispatchers.IO) { autosave.flush() }
		reportFailure()
	}

	// The two moments a pending write must not wait out its quiet period: the app going away,
	// which may be the last of this process, and the screen going away, which cancels the
	// coroutine above mid-delay.
	//
	// Keyed on [kind] as well as the owner, so switching drills disposes this effect and flushes
	// the drill being LEFT - [autosave] is rebuilt for the new kind, and the old one's pending
	// write would otherwise be dropped along with the object holding it. onDispose runs against
	// the composition it was created in, so the coalescer it flushes is the old one.
	val lifecycleOwner = LocalLifecycleOwner.current
	DisposableEffect(lifecycleOwner, kind) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_PAUSE) flushNow()
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
			flushNow()
		}
	}

	// Non-null ONLY when the user explicitly opened a stored run, which is what [openRunId]
	// records and what nothing but the picker and a stats row tap ever sets.
	//
	// Pointedly NOT the run onDone has just stored. DrillScreen adopts an openRun through
	// LaunchedEffect(openRun?.id), so feeding the finished run back would re-key that effect
	// null -> id and shove the screen out of FINISHED into PAST_RUN the instant Done was
	// pressed - with the Edit button then toggling a "past" run the user is still standing in.
	//
	// An id naming no stored run resolves to null, and null is a fresh live drill rather than an
	// empty grid. That is where a run removed by a hand-edit, and a stats row tapped as the file
	// changed underneath it, both land.
	val openRun = if (openRunId == null) null else runs.firstOrNull { it.id == openRunId }

	DrillScreen(
		kind = kind,
		settings = container.settings,
		openRun = openRun,
		onDone = { run ->
			// Stored the moment the clock stops and before a single cell is scored, so a run the
			// user never gets round to marking is still a run they did. Immediately rather than
			// on the debounce: this is once per run, not once per tap, and it is the record the
			// scoring that follows will be rewriting.
			val updated = DrillRunsRepository.upsert(runs, run)
			runs = updated
			autosave.schedule(updated)
			flushNow()
		},
		onItemsChanged = { run ->
			// The screen first, then the disk, exactly as TableRoute has it: the tap is applied
			// now, and a write that fails leaves the screen ahead of the file until the next
			// load - the toast being the only thing that says so.
			//
			// upsert and not a replace by hand, because it is the one that keeps a re-scored run
			// in POSITION; see its own note for what a run that climbed to the end would evict.
			val updated = DrillRunsRepository.upsert(runs, run)
			runs = updated
			autosave.schedule(updated)
			tapCount++
		},
		onOpenPicker = { pickerOpen = true },
		onCloseRun = {
			// New pressed. Raised whether or not a run was open, so this is also the ordinary
			// "start another set" path - flushNow costs nothing when nothing is pending.
			flushNow()
			onSelect(Screen.Drill(kind))
		}
	)

	if (pickerOpen) {
		RunPicker(
			runs = runs,
			onPick = { run ->
				// The dialog does not close itself on a pick - see its own note - so a pick owes
				// it the close a dismiss would have given it.
				pickerOpen = false
				// Nothing would be LOST without this: what is pending is the whole run list, not
				// the run being closed, so a later flush would still carry its marks. It goes out
				// here so that a run the user has finished with is on disk before the next one is
				// opened on top of it, which is the same promise Done makes.
				flushNow()
				onSelect(Screen.Drill(kind, run.id))
			},
			onDismiss = { pickerOpen = false }
		)
	}
}

/**
 * One drill's stats table, with a row tap opening that run on the drill screen.
 *
 * Nothing here writes, so there is no coalescer and no flush. The only thing this route owns is
 * the same load [DrillRoute] does, of the same file, at the same two moments - and it must be
 * the same load, because a run scored on the drill screen has to be in this table by the time
 * the user gets here.
 */
@Composable
fun DrillStatsRoute(container: AppContainer, kind: DrillKind, onSelect: (Screen) -> Unit) {
	val runs by rememberStoredRuns(container, kind)
	DrillStatsScreen(
		kind = kind,
		runs = runs,
		tableSettings = container.settings.table,
		onOpenRun = { run -> onSelect(Screen.Drill(kind, run.id)) },
		onRendered = container::dumpRendered
	)
}

/**
 * [kind]'s stored runs, reloaded on entering the screen and on every resume.
 *
 * The reload matters as much as the first read. These files exist to be hand-edited, so a run
 * scored, flushed and then reopened has to come back to the marks that were written rather than
 * to whatever this state was holding, and a stats table or a run picker looked at after an adb
 * edit has to show what the file now says.
 *
 * ONE case is deliberately outside that promise, because it is not this state's to keep: a run
 * already OPEN on the drill screen, hand-edited in a way that KEEPS its id. DrillScreen adopts
 * through LaunchedEffect(openRun?.id), which does not re-key when the id has not changed, so the
 * grid keeps the items it is holding and the next scoring tap upserts them back over the edit.
 * Rewriting the run you are standing in is the one edit that does not take; every other route to
 * a run - the picker, a stats row - re-enters the screen and so re-reads.
 *
 * DELETING that run is a different case and does take. It resolves [openRun] to null, which DOES
 * re-key the effect, and DrillScreen answers a null by falling back to a fresh live drill rather
 * than by holding a run the file no longer has. That is the same null the drawer hands back when
 * the drill you are already on is re-selected, and it is what the note on openRun above means by
 * an unresolvable id being a fresh drill - in place, and not only on the way in.
 *
 * Read from the lifecycle observer ALONE, with no separate first load. Adding an observer to an
 * owner that is already resumed dispatches ON_RESUME to it there and then, so entering the
 * screen IS that dispatch; a read before addObserver would only parse the file a second time,
 * which at the retention cap is megabytes for nothing. MainActivity's own observer relies on the
 * same dispatch.
 */
@Composable
private fun rememberStoredRuns(
	container: AppContainer,
	kind: DrillKind
): MutableState<List<DrillRun>> {
	val context = LocalContext.current
	// Keyed on [kind]: both drills reach this through one call site, the drawer changing the
	// argument rather than the position, so an unkeyed slot would open Poker on Numbers' runs -
	// and the first scoring tap would then upsert a Poker run into the Numbers file.
	val state = remember(kind) { mutableStateOf(emptyList<DrillRun>()) }
	val lifecycleOwner = LocalLifecycleOwner.current
	DisposableEffect(lifecycleOwner, kind) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) state.value = loadRuns(container, kind, context)
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}
	return state
}

/**
 * [kind]'s stored runs, with an unreadable file REPORTED rather than passed off as an empty one.
 *
 * DrillRunsRepository.load never throws: an unreadable file reads as no runs, deliberately, and
 * the argument for that is made there. What it costs is exactly this - a caller cannot tell "the
 * user has not drilled yet" from "the runs could not be read" - so without this check a stats
 * screen over a file the app cannot open would sit silently empty until the save at the end of
 * the next drill finally threw, several minutes of drilling later.
 *
 * The readability is therefore asked separately, through the same cheap access check that
 * repository's own save() refuses on. It is approximate in one direction - see
 * [JsonStore.isUnreadable] - so what it catches is something at the path that is not a readable
 * file, a directory left there by a botched sync being the case that actually happens. It does
 * not catch a read that fails part way through, and it does not catch a denied stat either: that
 * reads as absence rather than as a file, and so passes silently. Both are the residue the
 * repository already accepts, and neither is made worse here.
 *
 * An ABSENT file is not an unreadable one and raises nothing, which is what keeps a fresh install
 * from toasting at a user who has simply never drilled.
 */
private fun loadRuns(container: AppContainer, kind: DrillKind, context: Context): List<DrillRun> {
	val file = kind.runsFile(container.paths)
	if (JsonStore(file).isUnreadable()) {
		Toast.makeText(
			context,
			"Could not read ${file.name} - check file permission",
			Toast.LENGTH_LONG
		).show()
	}
	return container.drillRunsRepository(kind).load()
}

/**
 * The failed-write toast, worded as TableRoute's is and raised for the same reason: the screen is
 * deliberately left ahead of the file, so this is the only thing that says the file did not
 * follow.
 */
private fun reportSaveFailure(context: Context, container: AppContainer, kind: DrillKind) {
	Toast.makeText(
		context,
		"Could not save ${kind.runsFile(container.paths).name} - check file permission or free space",
		Toast.LENGTH_SHORT
	).show()
}

/**
 * How quiet a drill has to go before a pending write is sent.
 *
 * Long enough that a burst of scoring taps coalesces into one write, and short enough that the
 * window in which a process kill costs a mark is not one a user would ever be sitting inside.
 */
private const val AUTOSAVE_QUIET_MILLIS = 400L
