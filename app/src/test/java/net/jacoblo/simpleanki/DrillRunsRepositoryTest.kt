package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.DrillRunsRepository
import net.jacoblo.simpleanki.data.ItemStatus
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillOps
import net.jacoblo.simpleanki.drill.runsFile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Covers the runs file end to end: what an absent, corrupt or hand-edited one reads as, what
 * a saved one contains, and what upsert does to a run that is already in the list.
 *
 * Stored JSON is always re-parsed and asserted on structurally, never compared as text. The
 * test classpath's org.json is HashMap-backed while Android's is LinkedHashMap-backed, so key
 * order is arbitrary here and stable on device, and only a structural assertion passes both.
 *
 * The wire fixtures are spelled out as JSON text by hand rather than built with the writer
 * under test, since a fixture built out of the code under test cannot fail. They are all
 * two-digit Numbers values, which are ASCII. The one fixture carrying poker cards is the
 * round-trip run, which takes them from DrillOps.generateDeck: a card's value holds a real
 * suit glyph, this file has to stay ASCII, and DrillOps already spells those four glyphs as
 * escapes. That test asserts on the whole run object, so it needs no card named here.
 *
 * An unreadable file is spelled as a DIRECTORY at the file's path, matching RepositoryTest:
 * it exists and reading it throws, the same shape a permission failure takes on device, and
 * unlike chmod it behaves identically whatever user the tests run as.
 */
class DrillRunsRepositoryTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	// ---------------------------------------------------------------------------
	// Reading
	// ---------------------------------------------------------------------------

	@Test
	fun anAbsentFileReadsAsEmptyAndIsNotCreated() {
		assertEquals(emptyList<DrillRun>(), repository().load())

		// An empty runs file is a user who has not drilled yet, not an error - and a stats
		// screen that created one just by being opened would leave a file behind for a drill
		// the user only ever glanced at.
		assertFalse(numbersFile().exists())
	}

	@Test
	fun aDocumentThatIsNotAnArrayIsQuarantinedBeforeItReadsAsEmpty() {
		val truncated = "[{\"id\":\"1\",\"startedAt\":1,\"seconds\":1.5"
		numbersFile().writeText(truncated)

		// The empty list is what the next save writes back, so the only copy of these runs
		// has to be moved out of the way before it is returned.
		assertEquals(emptyList<DrillRun>(), repository().load())
		assertEquals(truncated, File(numbersFile().path + ".corrupt").readText())
	}

	@Test
	fun oneMalformedRecordIsSkippedAndTheRestSurvive() {
		numbersFile().writeText(
			"[" + record(1L) + ",{\"nonsense\":true}," +
				"{\"id\":\"9\",\"startedAt\":9,\"seconds\":1.5}," +
				record(2L) + "]"
		)

		// Per-record tolerance, as in history.json: one bad row must not cost the user every
		// good one, and nothing is quarantined for it. Both bad records are missing a field
		// the run cannot be rebuilt without, which is the only thing that costs a record now.
		assertEquals(listOf("1", "2"), repository().load().map { it.id })
		assertFalse(File(numbersFile().path + ".corrupt").exists())
	}

	@Test
	fun anUnreadableCellKeepsItsSlotBlankInsteadOfCostingTheRun() {
		numbersFile().writeText(
			"[{\"id\":\"1\",\"startedAt\":1,\"seconds\":1.5,\"items\":[" +
				"{\"value\":\"07\",\"status\":\"right\"}," +
				"{\"status\":\"right\"}," +
				"\"nonsense\"," +
				"{\"value\":\"42\",\"status\":\"wrong\"}]}]"
		)
		val run = repository().load().single()

		// Four cells in, four cells out. The slot is the denominator of accuracy, so dropping
		// the two unreadable ones would score this run 1 out of 2 instead of 1 out of 4 - and
		// dropping the whole record would delete it from the file at the next save, since the
		// caller writes back the list it loaded.
		assertEquals(4, run.count)
		assertEquals(listOf("07", "", "", "42"), run.items.map { it.value })
		// The second cell claims "right" and does not get it. A mark on a value nobody can
		// read is not evidence the user answered it, and UNSCORED weighs as WRONG does.
		assertEquals(
			listOf(ItemStatus.RIGHT, ItemStatus.UNSCORED, ItemStatus.UNSCORED, ItemStatus.WRONG),
			run.items.map { it.status }
		)
		assertEquals(1, run.right)
	}

	@Test
	fun anUnrecognisedStatusReadsAsUnscored() {
		numbersFile().writeText(
			"[{\"id\":\"1\",\"startedAt\":1,\"seconds\":1.5,\"items\":[" +
				"{\"value\":\"07\",\"status\":\"right\"}," +
				"{\"value\":\"42\",\"status\":\"wrong\"}," +
				"{\"value\":\"91\",\"status\":\"\"}," +
				"{\"value\":\"13\",\"status\":\"nonsense\"}," +
				"{\"value\":\"55\"}]}]"
		)

		// The two recognised marks are asserted alongside the three that are not, so this
		// cannot pass for a reader that answers UNSCORED to everything it is handed.
		assertEquals(
			listOf(
				ItemStatus.RIGHT,
				ItemStatus.WRONG,
				ItemStatus.UNSCORED,
				ItemStatus.UNSCORED,
				ItemStatus.UNSCORED
			),
			repository().load().single().items.map { it.status }
		)
	}

	// ---------------------------------------------------------------------------
	// Writing
	// ---------------------------------------------------------------------------

	@Test
	fun anUnreadableFileReadsAsEmptyAndIsThenRefusedBySave() {
		assertTrue(numbersFile().mkdirs())

		// The one place load parts company with HistoryRepository.load, which throws here.
		// Asserted alongside the refusal rather than on its own, because it is only half of
		// one argument: reading as empty is safe ONLY because the save below will not write
		// that empty list back.
		assertEquals(emptyList<DrillRun>(), repository().load())

		try {
			// load() answers an unreadable file with an empty list, so without this the save
			// at the end of the next drill would write one run over all of them.
			repository().save(listOf(runOf(1L)), MAX_ENTRIES)
			fail("save must refuse a file it cannot read")
		} catch (e: IOException) {
			// The refusal specifically, not just any write failure: renameTo over a directory
			// throws too, so the file name alone would pass either way.
			assertTrue(e.message, e.message!!.startsWith("refusing to overwrite"))
			assertTrue(e.message!!.contains("numbers-runs.json"))
		}
	}

	@Test
	fun saveKeepsTheNewestRunsAndDropsTheOldest() {
		repository().save((1L..5L).map { runOf(it) }, 3)

		// The list is held oldest-first, so `take` in place of `takeLast` keeps 1, 2 and 3
		// here: a file that still looks entirely plausible and has silently thrown away every
		// recent run.
		assertEquals(listOf("3", "4", "5"), storedIds())
		assertEquals(listOf("3", "4", "5"), repository().load().map { it.id })
	}

	@Test
	fun saveWritesTheDocumentedWireShape() {
		val items = listOf(
			DrillItem("07"),
			DrillItem("42", ItemStatus.WRONG),
			DrillItem("91", ItemStatus.RIGHT)
		)
		repository().save(listOf(DrillRun(STARTED.toString(), STARTED, 83.4f, items)), MAX_ENTRIES)

		// Pinned against the format history.json set rather than against a round trip, which
		// would pass for any private encoding this class agreed with itself about.
		val obj = JSONArray(numbersFile().readText()).getJSONObject(0)
		assertEquals(STARTED.toString(), obj.getString("id"))
		assertEquals(STARTED, obj.getLong("startedAt"))
		assertEquals(83.4, obj.getDouble("seconds"), 0.0001)
		val stored = obj.getJSONArray("items")
		val cells = (0 until stored.length()).map { stored.getJSONObject(it) }
		assertEquals(listOf("07", "42", "91"), cells.map { it.getString("value") })
		assertEquals(listOf("", "wrong", "right"), cells.map { it.getString("status") })
	}

	@Test
	fun saveCreatesTheStorageDirectory() {
		val paths = AnkiPaths.at(File(tempFolder.root, "SimpleAnki"))
		val repository = DrillRunsRepository(DrillKind.NUMBERS.runsFile(paths))

		// JsonStore.write does not create the parent and AnkiPaths.ensureRoot is out of reach
		// from a bare File, so a user who drills before ever touching a card gets here first.
		repository.save(listOf(runOf(1L)), MAX_ENTRIES)
		assertEquals(listOf("1"), repository.load().map { it.id })
	}

	// ---------------------------------------------------------------------------
	// Round trip
	// ---------------------------------------------------------------------------

	@Test
	fun aRoundTripPreservesSecondsExactly() {
		val run = runOf(STARTED, seconds = 83.4f)
		repository().save(listOf(run), MAX_ENTRIES)

		// 83.4f is not exactly representable, and the delta is ZERO on purpose: the run has to
		// come back holding the SAME float, not one that is merely close. A store that
		// truncates or re-rounds on the way through would still look right in a stats table
		// and would move every seconds-per-item figure drawn from it.
		assertEquals(83.4f, repository().load().single().seconds, 0.0f)
	}

	@Test
	fun aRoundTrippedPokerRunKeepsItsCards() {
		val items = DrillOps.generateDeck(Random(7))
			.mapIndexed { i, item -> if (i % 3 == 0) item.copy(status = ItemStatus.RIGHT) else item }
		val run = DrillRun(STARTED.toString(), STARTED, 61.25f, items)
		val poker = DrillRunsRepository(DrillKind.POKER.runsFile(AnkiPaths.at(tempFolder.root)))
		poker.save(listOf(run), MAX_ENTRIES)

		// Whole-object equality, so every one of the 52 values and marks has to survive the
		// trip in order. Nothing here asserts id against startedAt: this test builds the run,
		// so that invariant would hold by construction and the assertion could not fail. It
		// belongs to whatever mints the run.
		assertEquals(run, poker.load().single())
	}

	// ---------------------------------------------------------------------------
	// upsert
	// ---------------------------------------------------------------------------

	@Test
	fun upsertReplacesInPlaceAndAppendsAnythingNew() {
		val runs = listOf(runOf(1L), runOf(2L), runOf(3L))
		val rescored = runOf(2L, seconds = 99.0f)

		val replaced = DrillRunsRepository.upsert(runs, rescored)
		// Position, not just presence: a run that moved to the end would climb over the runs
		// that genuinely came after it, and save keeps the newest BY POSITION, so an older run
		// would start evicting a newer one.
		assertEquals(listOf("1", "2", "3"), replaced.map { it.id })
		assertEquals(rescored, replaced[1])

		assertEquals(listOf("1", "2", "3", "4"), DrillRunsRepository.upsert(replaced, runOf(4L)).map { it.id })
	}

	// ---------------------------------------------------------------------------
	// Fixtures
	// ---------------------------------------------------------------------------

	private fun numbersFile(): File = DrillKind.NUMBERS.runsFile(AnkiPaths.at(tempFolder.root))

	private fun repository(): DrillRunsRepository = DrillRunsRepository(numbersFile())

	private fun runOf(startedAt: Long, seconds: Float = 1.5f): DrillRun = DrillRun(
		id = startedAt.toString(),
		startedAt = startedAt,
		seconds = seconds,
		items = listOf(DrillItem("07", ItemStatus.RIGHT), DrillItem("42"))
	)

	/** One stored record as hand-written wire text, the way a hand-edit would type it. */
	private fun record(startedAt: Long): String =
		"{\"id\":\"$startedAt\",\"startedAt\":$startedAt,\"seconds\":1.5," +
			"\"items\":[{\"value\":\"07\",\"status\":\"right\"}]}"

	private fun storedIds(): List<String> {
		val array = JSONArray(numbersFile().readText())
		return (0 until array.length()).map { array.getJSONObject(it).getString("id") }
	}

	private companion object {
		/** Well past any cap these tests exercise, so only an explicit limit ever trims. */
		const val MAX_ENTRIES = 100

		const val STARTED = 1756000000000L
	}
}
