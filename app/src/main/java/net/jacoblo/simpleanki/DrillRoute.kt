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
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillScreen
import net.jacoblo.simpleanki.drill.DrillStatsScreen
import net.jacoblo.simpleanki.drill.RunPicker
import net.jacoblo.simpleanki.drill.runsFile
import java.io.IOException

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
	// Bumped on every scoring tap, and read by nothing but the debounce below, whose key it is.
	var pendingTaps by remember(kind) { mutableStateOf(0) }
	var pickerOpen by remember(kind) { mutableStateOf(false) }

	/**
	 * Writes whatever is pending, here and now, on the calling thread.
	 *
	 * Synchronous on purpose at every one of its call sites. ON_PAUSE and onDispose are moments
	 * where a coroutine is about to be cancelled or about to lose its process, so handing the
	 * write to one is precisely how the last marks get lost; Done, New and a pick from the picker
	 * all want the file settled before the next thing reads it, or before the run they belong to
	 * stops being the one on screen.
	 *
	 * A whole-file write on the main thread is what this app already does on every card flip and
	 * every column resize. The debounce exists to keep it off the per-TAP path, which is the one
	 * that runs fifty times a minute, and not to ban it outright.
	 */
	fun flushNow() {
		if (autosave.flush() != null) reportSaveFailure(context, container, kind)
	}

	// The debounce. Keyed on the tap counter, so every tap cancels the delay the tap before it
	// started and only a gap in the tapping reaches the write. Keyed on [kind] as well, so
	// switching drills cannot leave a delay running against the coalescer it was started for.
	LaunchedEffect(kind, pendingTaps) {
		// Nothing has been tapped yet, so there is nothing to wait out. Entering a drill would
		// otherwise dispatch a pointless quarter-second and an IO hop on the way in.
		if (pendingTaps == 0) return@LaunchedEffect
		delay(AUTOSAVE_QUIET_MILLIS)
		// OFF the main thread, which is the whole point: this is a whole-file rewrite, and at
		// the retention cap that is megabytes of JSON. withContext resumes on the dispatcher it
		// was called from, so the toast below is raised back on main, where a Toast may be
		// raised at all.
		val failure = withContext(Dispatchers.IO) { autosave.flush() }
		if (failure != null) reportSaveFailure(context, container, kind)
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
		settings = remember(container.settings) { clampItemCount(container.settings) },
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
			pendingTaps++
		},
		onOpenPicker = { pickerOpen = true },
		onCloseRun = {
			// New pressed. Raised whether or not a run was open, so this is also the ordinary
			// "start another set" path - flushNow no-ops when nothing is pending.
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
 * The reload matters as much as the first read. These files exist to be hand-edited, so a drill
 * screen left open while the user rewrites numbers-runs.json over adb has to come back to what
 * the file now says - and a run scored, flushed and then reopened has to come back to the marks
 * that were written, not to the ones this state happened to be holding.
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
 * screen over a file behind a revoked permission would sit silently empty until the save at the
 * end of the next drill finally threw, several minutes of drilling later.
 *
 * The readability is therefore asked separately, through the same cheap access check that
 * repository's own save() refuses on. It is approximate in one direction - see
 * [JsonStore.isUnreadable] - so this catches the reproducible cases, a directory in the file's
 * place or a permission that was never granted, and not a read that fails part way through. That
 * residue is the one the repository already accepts and is not made worse here.
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
 * [settings] with the Numbers item count clamped to [MAX_DRILL_ITEMS], and everything else left
 * exactly as it was.
 *
 * DrillKind.itemCount passes the stored count through unvalidated on purpose, and the settings
 * screen's validators guard that screen's own input rather than the file. So a hand-edited
 * `"count": 100000` reaches DrillGrid, which is a plain Column and not a lazy one, and asks
 * Compose for a hundred thousand cells inside one frame. That is an ANR, and it is an ANR on the
 * way to the settings screen the user would have fixed the typo from.
 *
 * Clamped HERE rather than in DrillKind.itemCount, for the reason DrillGrid gives about its own
 * column count: here it is a question about this frame, and the stored value keeps saying exactly
 * what the user typed. The ceiling is the settings screen's own upper bound, so a count this
 * changes is one that screen would have refused outright.
 *
 * The low end is deliberately untouched. A zero or a negative generates no items and the grid
 * comes up empty, which is the honest picture of what the file says and is not a crash -
 * DrillOps.generateNumbers builds from a range precisely so that it is not.
 *
 * A hand-edited RUN carrying an absurd items list is a different vector and is NOT clamped here:
 * those items are written straight back by the next scoring tap, so truncating them for the grid
 * would turn a display problem into deleted data.
 */
private fun clampItemCount(settings: Settings): Settings {
	if (settings.numbers.count <= MAX_DRILL_ITEMS) return settings
	return settings.copy(numbers = settings.numbers.copy(count = MAX_DRILL_ITEMS))
}

/**
 * The pending write for one drill's runs file, coalesced so that no scoring tap ever waits on
 * one.
 *
 * The spec has every scoring tap autosave, and taken literally that is a rewrite of the entire
 * runs file per tap - at the [MAX_RUNS] retention cap, roughly ten megabytes of JSON on the main
 * thread for each of the fifty-odd taps it takes to score a set. Coalescing is what makes that
 * promise affordable: the list is held here, the caller re-arms a short delay, and only a gap in
 * the tapping reaches [flush].
 *
 * Nothing stays pending longer than that gap. The caller flushes at the three moments a delay
 * cannot be waited out - the app pausing, the screen leaving, and the open run closing - and this
 * is a class rather than one more piece of Compose state precisely so those flushes can be made
 * safe against the debounced one:
 *
 * 1. [schedule] and [flush] are synchronized, so a background write and a main-thread flush
 *    arriving together cannot both write the file; whichever gets there second finds nothing left
 *    to write.
 * 2. The pending list is held HERE and not inside a coroutine, so a debounce cancelled mid-delay
 *    by the screen going away leaves the work behind for the flush that follows instead of taking
 *    it with it. That is the failure this shape exists against, and it is the nastiest one here:
 *    a flush that misses the last taps is invisible until the next load, by which point the marks
 *    are simply gone.
 *
 * A failed write is REPORTED and dropped rather than kept for a retry, which is the same "screen
 * ahead of disk" bargain TableRoute makes. The next load reconciles it, and a retry over a file
 * that keeps refusing would raise the same toast again at every flush for the rest of the session.
 */
class DrillAutosave(private val repository: DrillRunsRepository) {

	private var pending: List<DrillRun>? = null

	/** Holds [runs] as what the file should say, replacing anything already pending. */
	@Synchronized
	fun schedule(runs: List<DrillRun>) {
		pending = runs
	}

	/**
	 * Writes what is pending, if anything, and RETURNS the failure instead of throwing it.
	 *
	 * Returned and not thrown because every caller has to carry on regardless - one of them is an
	 * onDispose, and one is a lifecycle callback - and because the toast it becomes has to be
	 * raised on the main thread, which is not where the debounced call runs.
	 */
	@Synchronized
	fun flush(): IOException? {
		val runs = pending ?: return null
		pending = null
		return try {
			repository.save(runs, MAX_RUNS)
			null
		} catch (e: IOException) {
			e
		}
	}
}

/**
 * Runs kept per file, newest first out of the trim, matching history.json's own default.
 *
 * A constant and not a setting: the spec gives the drills the same retention the history log has
 * and adds no field for it. Reading HistorySettings.maxEntries here instead would let a user
 * trimming their card history silently discard drill runs out of two entirely different files.
 */
private const val MAX_RUNS = 5000

/**
 * How quiet a drill has to go before a pending write is sent.
 *
 * Long enough that a burst of scoring taps coalesces into one write, and short enough that the
 * window in which a process kill costs a mark is not one a user would ever be sitting inside.
 */
private const val AUTOSAVE_QUIET_MILLIS = 400L

/**
 * The most items a drill screen will be asked to compose - see [clampItemCount].
 *
 * The same ceiling the settings screen's item-count validator enforces. The two must agree: a
 * count this clamps but that screen accepts would leave the field showing a number the grid is
 * not drawing.
 */
private const val MAX_DRILL_ITEMS = 1000
