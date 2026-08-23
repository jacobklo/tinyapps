package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.DeckRepository
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.table.RenderedColumn
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.testmode.TestMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers test mode's two file-writing halves: the fixture seed and the render dump.
 *
 * Everything runs on the JVM through AnkiPaths.at, which touches no Android API. isActive
 * is the one member with no test here - it needs a real Activity and a real intent, and
 * its release-build behaviour is a property of the BuildConfig.DEBUG constant, which is
 * true in a debug unit test by definition.
 *
 * Serialized text is never asserted on directly, except where two seeds are compared with
 * each other. The test classpath's org.json is HashMap-backed while Android's is
 * LinkedHashMap-backed, so key order is arbitrary here and stable on device.
 */
class TestModeTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun seedWritesTheThreeFixtureFiles() {
		val paths = seededRoot("SimpleAnki-test")

		assertTrue(paths.deck.exists())
		assertTrue(paths.history.exists())
		assertTrue(paths.settings.exists())
		// Task 8 owns views.json; nothing should be writing it yet.
		assertFalse(paths.views.exists())
	}

	@Test
	fun seededDeckIsTheSixNumberedQuestions() {
		val cards = DeckRepository(seededRoot("deck-test")).load()

		assertEquals(listOf("01", "02", "03", "04", "05", "06"), cards.map { it.question })
		assertTrue(cards.all { it.answer.isNotEmpty() })
	}

	@Test
	fun seededHistoryParsesAndCarriesEnoughTimeouts() {
		val history = HistoryRepository(seededRoot("history-test")).load()

		assertEquals(30, history.size)
		assertTrue("expected at least four timeouts", history.count { it.timedOut } >= 4)
		// Oldest first, and strictly increasing, so "newest" means something.
		assertEquals(history.map { it.timestamp }.sorted(), history.map { it.timestamp })
		assertEquals(history.size, history.map { it.timestamp }.distinct().size)
		// Timestamps come from a fixed epoch, never the clock. A clock-built fixture would
		// land within seconds of now; this one is pinned to 2023 and only drifts further.
		val newestAge = System.currentTimeMillis() - history.last().timestamp
		assertTrue("timestamps look clock-derived", newestAge > 24L * 60 * 60 * 1000)
	}

	/**
	 * The "-" empty-aggregate path has no other way into a fixture: TableEngine reads
	 * Seconds as null for a timed-out row, so only a question with no surviving attempt
	 * renders it.
	 */
	@Test
	fun seededHistoryHasAQuestionThatAlwaysTimedOut() {
		val history = HistoryRepository(seededRoot("alltimeout-test")).load()

		val alwaysTimedOut = history.groupBy { it.question }
			.filterValues { attempts -> attempts.all { it.timedOut } }
		assertEquals(setOf("06"), alwaysTimedOut.keys)
		// A question is only evidence of the path if it was actually attempted.
		assertTrue(alwaysTimedOut.getValue("06").size >= 2)
	}

	@Test
	fun seededSettingsCarryTheShortMetronomeInterval() {
		val settings = SettingsRepository(seededRoot("settings-test")).load()

		assertEquals(0.3f, settings.metronome.intervalSeconds, 0.0001f)
		assertFalse(settings.metronome.enabled)
		// Everything else is left at its default.
		assertEquals(5000, settings.history.maxEntries)
		assertEquals(10, settings.table.defaultLimit)
		assertEquals(0, settings.counters.lifetimeReviews)
	}

	@Test
	fun seedWipesWhateverWasThereBefore() {
		val root = tempFolder.newFolder("dirty-test")
		val junk = File(root, "junk.txt").apply { writeText("stale") }
		val staleSubdir = File(root, "nested").apply { mkdirs() }
		val nestedJunk = File(staleSubdir, "deep.txt").apply { writeText("stale") }
		// A file the app itself writes, which must not survive either.
		val staleDump = File(root, "dump.json").apply { writeText("{}") }

		TestMode.seed(AnkiPaths.at(root))

		assertFalse(junk.exists())
		assertFalse(nestedJunk.exists())
		assertFalse(staleSubdir.exists())
		assertFalse(staleDump.exists())
		assertTrue(root.exists())
	}

	@Test
	fun seedRefusesARootThatIsNotATestDirectory() {
		val production = tempFolder.newFolder("SimpleAnki")
		val decoy = File(production, "history.json").apply { writeText("precious") }

		val thrown = try {
			TestMode.seed(AnkiPaths.at(production))
			null
		} catch (e: IllegalArgumentException) {
			e
		}

		assertTrue("seed must refuse a non-test root", thrown != null)
		assertTrue(thrown!!.message!!.contains("refusing to wipe"))
		// The refusal has to happen BEFORE anything is deleted.
		assertEquals("precious", decoy.readText())
	}

	/** "-test" has to be the end of the name, not merely somewhere in it. */
	@Test
	fun seedRefusesARootThatOnlyContainsTestInItsName() {
		val sneaky = tempFolder.newFolder("SimpleAnki-test-backup")
		val decoy = File(sneaky, "history.json").apply { writeText("precious") }

		try {
			TestMode.seed(AnkiPaths.at(sneaky))
			assertTrue("seed must refuse SimpleAnki-test-backup", false)
		} catch (_: IllegalArgumentException) {
			// Expected.
		}
		assertEquals("precious", decoy.readText())
	}

	@Test
	fun seedIsDeterministicAcrossRootsAndRepeats() {
		val first = seededRoot("first-test")
		val second = seededRoot("second-test")
		// Re-seeding an already seeded root must land in the same place too.
		TestMode.seed(first)

		// Byte comparison is safe here: both sides came from the same org.json in the same
		// JVM, so any difference is the fixture's, not the serializer's.
		assertEquals(first.deck.readText(), second.deck.readText())
		assertEquals(first.history.readText(), second.history.readText())
		assertEquals(first.settings.readText(), second.settings.readText())
		// A fixture that read the clock would still pass the above if it were fast enough.
		assertFalse(first.history.readText().contains(System.currentTimeMillis().toString()))
	}

	@Test
	fun writeDumpProducesTheDocumentedShape() {
		val paths = AnkiPaths.at(tempFolder.newFolder("dump-test"))
		paths.ensureRoot()

		TestMode.writeDump(paths, SAMPLE_TABLE)

		val dump = JSONObject(paths.dump.readText())
		assertEquals("stats", dump.getString("viewId"))
		assertEquals(5, dump.getInt("highlightEvery"))
		assertEquals(2, dump.getInt("visibleRowCount"))
		// Lowercase, as documented, and not the enum's own spelling.
		assertEquals("Question", dump.getJSONObject("sort").getString("column"))
		assertEquals("asc", dump.getJSONObject("sort").getString("dir"))

		val columns = dump.getJSONArray("columns")
		assertEquals(2, columns.length())
		val question = columns.getJSONObject(0)
		assertEquals("Question", question.getString("id"))
		assertEquals("Question", question.getString("title"))
		assertEquals(160, question.getInt("width"))
		assertTrue(question.getBoolean("frozen"))
		assertEquals("TEXT", question.getString("type"))
		assertTrue(question.getBoolean("sortable"))

		val rows = dump.getJSONArray("rows")
		assertEquals(2, rows.length())
		assertEquals("01", rows.getJSONArray(0).getString(0))
		assertEquals("2.40", rows.getJSONArray(0).getString(1))
		assertEquals("-", rows.getJSONArray(1).getString(1))

		assertEquals(1, dump.getJSONArray("warnings").length())
		assertEquals("a warning", dump.getJSONArray("warnings").getString(0))
	}

	/**
	 * A null error has to arrive as a JSON null. put(name, null) would drop the key
	 * outright, and stringifying it first would store the text "null" instead.
	 */
	@Test
	fun writeDumpKeepsAnAbsentColumnErrorAsAJsonNull() {
		val paths = AnkiPaths.at(tempFolder.newFolder("error-test"))
		paths.ensureRoot()

		TestMode.writeDump(paths, SAMPLE_TABLE)

		val columns = JSONObject(paths.dump.readText()).getJSONArray("columns")
		val clean = columns.getJSONObject(0)
		// has() fails if the key was dropped; isNull() fails if it became the string "null".
		assertTrue("the error key must be present", clean.has("error"))
		assertTrue("the error key must be a JSON null", clean.isNull("error"))

		val broken = columns.getJSONObject(1)
		assertFalse(broken.isNull("error"))
		assertEquals("bad formula", broken.getString("error"))
	}

	@Test
	fun writeDumpReplacesThePreviousDump() {
		val paths = AnkiPaths.at(tempFolder.newFolder("replace-test"))
		paths.ensureRoot()

		TestMode.writeDump(paths, SAMPLE_TABLE)
		TestMode.writeDump(paths, SAMPLE_TABLE.copy(viewId = "history", rows = emptyList()))

		val dump = JSONObject(paths.dump.readText())
		assertEquals("history", dump.getString("viewId"))
		assertEquals(0, dump.getJSONArray("rows").length())
	}

	/** Seeds a fresh temp folder whose name ends in "-test", and returns its paths. */
	private fun seededRoot(name: String): AnkiPaths {
		val paths = AnkiPaths.at(tempFolder.newFolder(name))
		TestMode.seed(paths)
		return paths
	}

	private companion object {
		val SAMPLE_TABLE = RenderedTable(
			viewId = "stats",
			sort = SortSpec("Question", SortDir.ASC),
			columns = listOf(
				RenderedColumn(
					id = "Question",
					title = "Question",
					width = 160,
					frozen = true,
					type = ColumnType.TEXT,
					sortable = true,
					error = null
				),
				RenderedColumn(
					id = "Last",
					title = "Last",
					width = 100,
					frozen = false,
					type = ColumnType.NUMBER,
					sortable = false,
					error = "bad formula"
				)
			),
			rows = listOf(listOf("01", "2.40"), listOf("06", "-")),
			highlightEvery = 5,
			visibleRowCount = 2,
			warnings = listOf("a warning")
		)
	}
}
