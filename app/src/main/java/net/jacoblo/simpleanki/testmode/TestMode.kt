/*
 * Test mode: the hooks that let an agent drive and verify the app on a device.
 *
 * Production code, but reachable only from a debug build - [isActive] is guarded by
 * BuildConfig.DEBUG, a compile-time constant, so in a release build the whole test-mode
 * branch folds away and the intent extra does nothing.
 *
 * Activated with:
 *   adb shell am start -n net.jacoblo.simpleanki/.MainActivity --ez test_mode true
 *
 * When active exactly four things change, and nothing else:
 *   1. AnkiPaths resolves to /sdcard/SimpleAnki-test rather than /sdcard/SimpleAnki
 *   2. that directory is wiped and reseeded from the fixtures below, on every launch
 *   3. dump.json is written after every table render
 *   4. AppContainer.clickPlayer is the no-op, so an automated run makes no sound
 */
package net.jacoblo.simpleanki.testmode

import android.app.Activity
import net.jacoblo.simpleanki.BuildConfig
import net.jacoblo.simpleanki.data.AnkiCard
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.DrillRunsRepository
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.HistorySettings
import net.jacoblo.simpleanki.data.ItemStatus
import net.jacoblo.simpleanki.data.JsonStore
import net.jacoblo.simpleanki.data.MetronomeSettings
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.ViewsRepository
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillOps
import net.jacoblo.simpleanki.drill.itemCount
import net.jacoblo.simpleanki.drill.runsFile
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.toWireToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.random.Random

object TestMode {

	/** Intent extra that turns test mode on. Boolean; see the file header for the command. */
	const val EXTRA = "test_mode"

	/**
	 * True only when this is a debug build AND the launching intent carries [EXTRA].
	 *
	 * BuildConfig.DEBUG is first so a release build short-circuits before ever reading the
	 * intent; being a compile-time constant it also lets R8 strip the branch entirely.
	 */
	fun isActive(activity: Activity): Boolean =
		BuildConfig.DEBUG && activity.intent?.getBooleanExtra(EXTRA, false) == true

	/**
	 * Wipes [paths] and writes the deck, history, settings, views and drill-run fixtures.
	 *
	 * Run once per activity launch, before anything reads those files, so every run starts
	 * from an identical state no matter what the previous run left behind.
	 *
	 * DESTRUCTIVE ON ROTATION. The activity declares no configChanges, so a rotation - or
	 * any other configuration change - recreates it, and the fresh AppContainer seeds
	 * again, wiping whatever the run has recorded so far. An agent driving the app must
	 * not rotate mid-scenario, or must expect to start the scenario over if it does.
	 *
	 * @throws IllegalArgumentException when [paths] is not a test root - see [wipe]. That
	 *   is a programming error and must never be caught.
	 * @throws IllegalStateException when a stale file survives the wipe, which would leave
	 *   the run non-pristine - the single thing this function exists to guarantee.
	 * @throws IOException when the fixtures cannot be written despite storage access. The
	 *   caller checks that access first, so getting here means something else is wrong;
	 *   the message names the permission and the remedy rather than surfacing a bare
	 *   FileNotFoundException about a .tmp file nobody asked for.
	 */
	fun seed(paths: AnkiPaths) {
		wipe(paths.root)
		try {
			paths.ensureRoot()
			writeDeck(paths)
			HistoryRepository(paths).save(HISTORY_FIXTURE, HistorySettings().maxEntries)
			SettingsRepository(paths).save(SETTINGS_FIXTURE)
			// The three built-ins, exactly as a first run would get them. Written here
			// rather than left to ViewsRepository.load's auto-create so a seed failure
			// still surfaces as the IOException below rather than being swallowed.
			ViewsRepository(paths).save(ViewsRepository.defaults(SETTINGS_FIXTURE.table))
			writeRuns(paths)
		} catch (e: IOException) {
			throw IOException(
				"could not seed ${paths.root}: test mode needs MANAGE_EXTERNAL_STORAGE. " +
					"Launch without --ez $EXTRA true, grant file access at the prompt, " +
					"then relaunch with it.",
				e
			)
		}
	}

	/**
	 * Serializes [table] to [AnkiPaths.dump]. Called after every render, from test mode only.
	 *
	 * The rendered table verbatim: an agent reading dump.json is reading exactly the strings
	 * the WebView was handed, so a disagreement between the two is a renderer bug and
	 * nothing else.
	 *
	 * Never throws. This runs from a Compose effect on the render path, where an exception
	 * would take the screen down; a dump that failed to write is a missing file, which the
	 * agent already has to notice.
	 */
	fun writeDump(paths: AnkiPaths, table: RenderedTable) {
		try {
			paths.ensureRoot()
			JsonStore(paths.dump).write(toJson(table).toString(DUMP_INDENT))
		} catch (_: IOException) {
			// See the doc comment: a render must not be able to crash the app.
		}
	}

	/**
	 * Deletes everything inside [root], leaving the directory itself in place.
	 *
	 * The guard is the whole point of this function. One wrong AnkiPaths would otherwise
	 * erase /sdcard/SimpleAnki, which holds the user's entire practice history and exists
	 * in exactly one copy. It is a hard check rather than a comment or a convention, and it
	 * throws rather than returning quietly, so a mistake here fails at the first launch
	 * instead of destroying data on it.
	 */
	private fun wipe(root: File) {
		require(root.name.endsWith(TEST_ROOT_SUFFIX)) {
			"refusing to wipe ${root.path}: only a directory named *$TEST_ROOT_SUFFIX is wipeable"
		}
		root.listFiles()?.forEach { child ->
			// Discarding this boolean would leave the run quietly non-pristine, which is
			// the one outcome the wipe exists to prevent.
			check(child.deleteRecursively()) {
				"could not delete ${child.path}, so ${root.path} is not pristine"
			}
		}
	}

	/**
	 * Writes the deck fixture.
	 *
	 * Hand-rolled rather than routed through DeckRepository, which can only write its own
	 * five-card sample. Indented to match what that writes, since the deck is the one file
	 * here a human ever opens.
	 */
	private fun writeDeck(paths: AnkiPaths) {
		val array = JSONArray()
		DECK_FIXTURE.forEach { card ->
			array.put(JSONObject().put("question", card.question).put("answer", card.answer))
		}
		JsonStore(paths.deck).write(array.toString(DECK_INDENT))
	}

	/**
	 * Writes one runs file per drill, so both stats screens have rows before anything is drilled.
	 *
	 * Iterates DrillKind rather than naming numbers-runs.json and poker-runs.json, and routes
	 * through [DrillRunsRepository] rather than building the JSON here. Both are the same point:
	 * a fixture the app cannot read is worse than no fixture, and the only way to be sure it can
	 * is to write it with the very code that will read it back. A third drill also gets its own
	 * seeded file for free instead of being silently left out of test mode.
	 *
	 * The retention cap is the fixture's own size. The cap exists to trim a file that grows one
	 * run at a time; here the list IS the file, and passing anything smaller would quietly seed
	 * fewer runs than [RUN_PLANS] declares.
	 */
	private fun writeRuns(paths: AnkiPaths) {
		DrillKind.entries.forEach { kind ->
			val runs = runFixture(kind)
			DrillRunsRepository(kind.runsFile(paths)).save(runs, runs.size)
		}
	}

	/** [table] as the documented dump.json object. */
	private fun toJson(table: RenderedTable): JSONObject {
		val columns = JSONArray()
		table.columns.forEach { column ->
			columns.put(
				JSONObject()
					.put("id", column.id)
					.put("title", column.title)
					.put("width", column.width)
					.put("frozen", column.frozen)
					.put("type", column.type.name)
					.put("sortable", column.sortable)
					// put(name, null) REMOVES the key, so an absent error has to be spelled
					// with NULL to reach the file as a JSON null.
					.put("error", column.error ?: JSONObject.NULL)
			)
		}
		val rows = JSONArray()
		table.rows.forEach { row -> rows.put(JSONArray(row)) }
		return JSONObject()
			.put("viewId", table.viewId)
			.put(
				"sort",
				JSONObject()
					.put("column", table.sort.column)
					.put("dir", table.sort.dir.toWireToken())
			)
			.put("highlightEvery", table.highlightEvery)
			.put("visibleRowCount", table.visibleRowCount)
			.put("columns", columns)
			.put("rows", rows)
			.put("warnings", JSONArray(table.warnings))
	}

	/** Only a directory whose name ends in this may be wiped. See [wipe]. */
	private const val TEST_ROOT_SUFFIX = "-test"

	private const val DECK_INDENT = 4
	private const val DUMP_INDENT = 2

	/**
	 * Timestamp of the oldest fixture record, 2023-11-14T22:13:20Z.
	 *
	 * A constant rather than a clock read: two runs of [seed] must produce byte-identical
	 * files, so that a diff against a previous run only ever shows what the app changed.
	 */
	private const val EPOCH_BASE_MILLIS = 1_700_000_000_000L

	/** Spacing between consecutive fixture records - one minute. */
	private const val RECORD_STEP_MILLIS = 60_000L

	/** Rounds of the deck in the history fixture; six questions each, so 30 records. */
	private const val ROUNDS = 5

	/** Stored on a timed-out attempt, whose Seconds cell renders "-" whatever it holds. */
	private const val TIMED_OUT_SECONDS = 10.0f

	/** Added to a question's first-attempt time on each successive round. */
	private const val ROUND_DELTA_SECONDS = 0.25f

	/**
	 * One question's place in the fixtures: its card, how long its first attempt took, and
	 * which rounds it timed out in.
	 */
	private data class QuestionPlan(
		val question: String,
		val answer: String,
		val firstSeconds: Float,
		val timedOutRounds: Set<Int>
	)

	/**
	 * The six questions, "01" through "06".
	 *
	 * "06" times out in every round on purpose. Seconds is null for a timed-out row, so a
	 * question with no surviving attempt renders TableEngine.EMPTY_CELL, and that "-" path
	 * has no other way to appear in a fixture. "03" and "04" time out once each, which puts
	 * the total over four while leaving them a real time to show.
	 */
	private val QUESTION_PLANS = listOf(
		QuestionPlan("01", "one", 1.40f, emptySet()),
		QuestionPlan("02", "two", 2.40f, emptySet()),
		QuestionPlan("03", "three", 3.40f, setOf(2)),
		QuestionPlan("04", "four", 4.40f, setOf(4)),
		QuestionPlan("05", "five", 5.40f, emptySet()),
		QuestionPlan("06", "six", 6.40f, setOf(0, 1, 2, 3, 4))
	)

	/** simple-anki.json: one card per plan, in order. */
	private val DECK_FIXTURE: List<AnkiCard> =
		QUESTION_PLANS.map { AnkiCard(it.question, it.answer) }

	/**
	 * history.json: [ROUNDS] passes over the deck, oldest first, 30 records in all.
	 *
	 * Round-major so the questions interleave the way real practice does, which is what
	 * makes the rolling and bucket partitions see a realistic order.
	 *
	 * Every value is derived from the constants above; nothing reads the clock and nothing
	 * is random, so the file is byte-identical on every seed.
	 */
	private val HISTORY_FIXTURE: List<HistoryEntry> = buildList {
		for (round in 0 until ROUNDS) {
			QUESTION_PLANS.forEachIndexed { index, plan ->
				val timedOut = plan.timedOutRounds.contains(round)
				add(
					HistoryEntry(
						question = plan.question,
						answer = plan.answer,
						timeTaken = if (timedOut) TIMED_OUT_SECONDS
							else plan.firstSeconds + round * ROUND_DELTA_SECONDS,
						timestamp = EPOCH_BASE_MILLIS +
							(round * QUESTION_PLANS.size + index) * RECORD_STEP_MILLIS,
						timedOut = timedOut
					)
				)
			}
		}
	}

	/**
	 * settings.json: defaults but for the metronome interval.
	 *
	 * 0.3 seconds is short enough that a timeout can be provoked faster than an agent can
	 * act, and disabled so it only ticks when a test turns it on.
	 *
	 * It reaches the file as 0.30000001192092896, because SettingsRepository widens the
	 * Float to a Double and 0.3 has no exact binary form. It reads back as exactly 0.3f, so
	 * this is cosmetic - but an agent asserting on the TEXT of settings.json has to match
	 * the long form, or parse the number and compare with a tolerance.
	 */
	private val SETTINGS_FIXTURE = Settings(
		metronome = MetronomeSettings(enabled = false, intervalSeconds = 0.3f)
	)

	// ---------------------------------------------------------------------------
	// Drill runs
	// ---------------------------------------------------------------------------

	/**
	 * Seed of the generator behind every fixture set.
	 *
	 * Fixed, and never Random.Default, for the reason [EPOCH_BASE_MILLIS] gives about the clock:
	 * two seeds have to produce byte-identical files. A set of numbers that changed on every
	 * launch would leave an agent's expected dump good for exactly one run, and a Poker grid
	 * reshuffled behind its stored marks would be a fixture that reads differently from itself.
	 */
	private const val RUN_RANDOM_SEED = 20231114

	/** Spacing between fixture runs - one hour. See [runFixture] for why it is this wide. */
	private const val RUN_STEP_MILLIS = 3_600_000L

	/**
	 * The first fixture run, one hour past [EPOCH_BASE_MILLIS].
	 *
	 * An hour, so the runs start clear of the 30 history records, which end 29 minutes in. The
	 * two fixtures describe unrelated activity and nothing joins them, but a run timestamped
	 * inside the history's own span invites the reader to look for a link that is not there.
	 */
	private const val RUNS_BASE_MILLIS = EPOCH_BASE_MILLIS + RUN_STEP_MILLIS

	/** Every [WRONG_EVERY]th marked cell is WRONG; the rest are RIGHT. */
	private const val WRONG_EVERY = 5

	/**
	 * How much of a fixture run the user is pretended to have marked.
	 *
	 * All three exist so that every shape the stats table can draw is on screen before anyone
	 * has drilled. [NONE] is the one worth spelling out: right and wrong are both zero, so its
	 * Accuracy cell reads 0% and NOT the dash - the dash belongs to a run with no items at all -
	 * and that distinction has no other way into a fixture, since scoring a run through the UI
	 * is what a tester would otherwise have to skip in order to see it.
	 */
	private enum class Scored {
		NONE,
		HALF,
		ALL;

		/** How many of a run's [itemCount] cells carry a mark. */
		fun markedCount(itemCount: Int): Int = when (this) {
			NONE -> 0
			HALF -> itemCount / 2
			ALL -> itemCount
		}
	}

	/** One fixture run: how long it took, and how much of it was scored. */
	private data class RunPlan(val seconds: Float, val scored: Scored)

	/**
	 * The three runs each drill gets, oldest first - the order a runs file is held in.
	 *
	 * Three is the whole set on purpose: the point is one run of each shape in [Scored], and a
	 * fourth would only be more rows to read past when an agent dumps the stats table.
	 *
	 * 83.4f is not a round number by accident. It has no exact binary form, so it reaches the
	 * file as 83.4000015258789 - DrillRunsRepository stores a Float as a Double, since org.json
	 * has no float - and reads back as exactly 83.4f again. That makes the fixture carry the one
	 * case the round trip has to get right, and it is also the number an agent comparing the
	 * file's TEXT against "83.4" would trip over.
	 */
	private val RUN_PLANS = listOf(
		RunPlan(83.4f, Scored.NONE),
		RunPlan(96.5f, Scored.HALF),
		RunPlan(120.0f, Scored.ALL)
	)

	/**
	 * [kind]'s three runs, oldest first.
	 *
	 * ONE generator per drill, advanced across its runs in order, rather than a fresh
	 * Random(seed) inside each: seeding per run would hand all three the identical set, which
	 * for Poker means three identical shuffles and for Numbers a grid the tester learns by
	 * heart. Determinism comes from the seed, not from re-seeding.
	 *
	 * The set itself comes from [DrillOps.generate] - the same call DrillScreen.freshItems
	 * makes - so a seeded run holds exactly what a live one would. It is also what lets the card
	 * fixtures carry the real suit glyphs while this file stays pure ASCII, since the glyphs are
	 * spelled as escapes once, in DrillOps, and never typed again.
	 *
	 * The item count is read off [SETTINGS_FIXTURE] rather than written out again here, so a
	 * seeded Numbers run and a fresh set generated from the seeded settings are always the same
	 * size. No cap is applied on the way past, unlike DrillScreen.freshItems: that guards a
	 * hand-edited settings.json, and this count comes from the fixture two lines up.
	 */
	private fun runFixture(kind: DrillKind): List<DrillRun> {
		val random = Random(RUN_RANDOM_SEED)
		val itemCount = kind.itemCount(SETTINGS_FIXTURE)
		return RUN_PLANS.mapIndexed { index, plan ->
			// Keyed on the drill's ordinal, so each drill owns its own block of hours and no
			// two runs anywhere in the seeded root share a startedAt. An hour apart also means
			// the When column - MM-dd HH:mm:ss, rendered in the DEVICE's zone - tells them
			// apart at a glance rather than by their last two digits.
			val startedAt =
				RUNS_BASE_MILLIS + (kind.ordinal * RUN_PLANS.size + index) * RUN_STEP_MILLIS
			DrillRun(
				// id IS startedAt rendered, which is what DrillScreen mints and what Models.kt
				// documents as the invariant. DrillScreen carries an openId precisely because a
				// HAND-EDITED file can break that - so a fixture spelling an id of its own
				// would quietly point every test-mode reopen at the hand-edit path instead of
				// at the one a real run takes.
				id = startedAt.toString(),
				startedAt = startedAt,
				seconds = plan.seconds,
				items = scoredItems(
					DrillOps.generate(kind, itemCount, random),
					plan.scored.markedCount(itemCount)
				)
			)
		}
	}

	/**
	 * [items] with the first [markedCount] of them marked, every [WRONG_EVERY]th of those WRONG.
	 *
	 * Marks the FRONT of the set rather than a scattering of it, because that is the shape
	 * scoring actually leaves behind: the user works down the grid and stops. A run marked at
	 * random would be a state the app cannot reach by tapping.
	 *
	 * Positional rather than random for a second reason too - the marks stay put no matter what
	 * the generator did, so the right/wrong counts of every fixture run can be worked out on
	 * paper and checked against the stats table without opening a single grid.
	 */
	private fun scoredItems(items: List<DrillItem>, markedCount: Int): List<DrillItem> =
		items.mapIndexed { index, item ->
			when {
				index >= markedCount -> item
				index % WRONG_EVERY == WRONG_EVERY - 1 -> item.copy(status = ItemStatus.WRONG)
				else -> item.copy(status = ItemStatus.RIGHT)
			}
		}
}
