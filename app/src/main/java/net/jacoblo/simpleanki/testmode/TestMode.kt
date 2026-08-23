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
 * When active exactly three things change, and nothing else:
 *   1. AnkiPaths resolves to /sdcard/SimpleAnki-test rather than /sdcard/SimpleAnki
 *   2. that directory is wiped and reseeded from the fixtures below, on every launch
 *   3. dump.json is written after every table render
 * Task 14 adds a fourth, the no-op ClickPlayer.
 */
package net.jacoblo.simpleanki.testmode

import android.app.Activity
import net.jacoblo.simpleanki.BuildConfig
import net.jacoblo.simpleanki.data.AnkiCard
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.HistorySettings
import net.jacoblo.simpleanki.data.JsonStore
import net.jacoblo.simpleanki.data.MetronomeSettings
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.toWireToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
	 * Wipes [paths] and writes the deck, history, and settings fixtures.
	 *
	 * Run once per activity launch, before anything reads those files, so every run starts
	 * from an identical state no matter what the previous run left behind. Task 8 adds
	 * views.json here once that file format exists.
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
	 * makes the rolling and bucket partitions of Task 13 see a realistic order.
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
}
