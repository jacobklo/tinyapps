package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.DeckRepository
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.DrillRunsRepository
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.ItemStatus
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.ViewsRepository
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillOps
import net.jacoblo.simpleanki.drill.DrillStatsTable
import net.jacoblo.simpleanki.drill.runsFile
import net.jacoblo.simpleanki.table.RenderedColumn
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.toPayloadJson
import net.jacoblo.simpleanki.testmode.TestMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

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
	fun seedWritesTheSixFixtureFiles() {
		val paths = seededRoot("SimpleAnki-test")

		assertTrue(paths.deck.exists())
		assertTrue(paths.history.exists())
		assertTrue(paths.settings.exists())
		assertTrue(paths.views.exists())
		// One per drill. Both, or a stats screen opens empty for whichever was missed - which
		// looks exactly like a drill nobody has run, so the gap would be read as normal.
		assertTrue("numbers-runs.json was not seeded", paths.numbersRuns.exists())
		assertTrue("poker-runs.json was not seeded", paths.pokerRuns.exists())
	}

	@Test
	fun seededViewsAreTheThreeBuiltIns() {
		val views = ViewsRepository(seededRoot("views-test")).load(TableSettings())

		assertEquals(listOf("stats", "history", "list_rows"), views.views.map { it.id })
		assertEquals("stats", views.activeViewId)
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

	/**
	 * Both runs files have to come back through the repository that will read them on device.
	 *
	 * Parsing IS the assertion: DrillRunsRepository skips a malformed record rather than failing
	 * over it, so a fixture written in a shape it does not understand would reach the stats
	 * screen as an empty table and read there as a drill nobody had ever run.
	 */
	@Test
	fun seededRunsParseBackForBothDrills() {
		val paths = seededRoot("runs-test")

		val numbers = seededRuns(paths, DrillKind.NUMBERS)
		val poker = seededRuns(paths, DrillKind.POKER)

		assertEquals(3, numbers.size)
		assertEquals(3, poker.size)
		// Each set is the size the drill itself would generate: the Numbers count off the
		// seeded settings, and a full deck for Poker whatever settings say.
		assertTrue(numbers.all { it.count == NUMBERS_COUNT })
		assertTrue(poker.all { it.count == DrillOps.DECK_SIZE })
	}

	/**
	 * The two files hold two different drills, which is the whole reason there are two of them.
	 *
	 * Seeding one fixture into both paths would leave every other assertion here passing and
	 * still put a grid of two-digit numbers behind the Poker stats screen.
	 */
	@Test
	fun theTwoSeededFilesHoldTheirOwnDrill() {
		val paths = seededRoot("perdrill-test")

		val numbers = seededRuns(paths, DrillKind.NUMBERS).flatMap { it.items }.map { it.value }
		val poker = seededRuns(paths, DrillKind.POKER)

		assertTrue("Numbers cells must be two padded digits", numbers.all { TWO_DIGITS.matches(it) })
		// Every card exactly once per run, which is also what shows the deck came out of
		// DrillOps.generateDeck rather than out of a suit glyph typed into the fixture.
		val deck = DrillOps.SUITS.flatMap { suit -> DrillOps.RANKS.map { rank -> rank + suit } }
		poker.forEach { run -> assertEquals(deck.toSet(), run.items.map { it.value }.toSet()) }
	}

	/**
	 * id == startedAt.toString(), on every seeded run, in both files.
	 *
	 * The invariant Models.kt states and DrillScreen mints. A fixture that broke it would send
	 * every test-mode reopen down the hand-edited-file path DrillScreen's openId exists for, so
	 * a scenario driven against these runs would not be exercising what a real run does.
	 */
	@Test
	fun everySeededRunIdIsItsStartedAtRendered() {
		val paths = seededRoot("runid-test")

		val all = seededRuns(paths, DrillKind.NUMBERS) + seededRuns(paths, DrillKind.POKER)

		all.forEach { assertEquals("id must be startedAt rendered", it.startedAt.toString(), it.id) }
		// Across both files and not merely within one: DrillRoute reopens a run by id alone, so
		// a Numbers run and a Poker run sharing one would be two records it cannot tell apart.
		assertEquals(all.size, all.map { it.id }.distinct().size)
	}

	/**
	 * One unscored run, one half scored, one scored through - per drill.
	 *
	 * The counts are spelled out rather than derived, because they are exactly what a device
	 * check reads off the stats table. The unscored run is the one that matters most: right and
	 * wrong both zero is a real 0% row, and without it that row can only be reached by opening
	 * a drill and deliberately marking nothing.
	 */
	@Test
	fun seededRunsCoverUnscoredHalfScoredAndFullyScored() {
		val paths = seededRoot("scored-test")

		val numbers = seededRuns(paths, DrillKind.NUMBERS)
		val poker = seededRuns(paths, DrillKind.POKER)

		assertRun(numbers[0], right = 0, wrong = 0, unscored = 50)
		assertRun(numbers[1], right = 20, wrong = 5, unscored = 25)
		assertRun(numbers[2], right = 40, wrong = 10, unscored = 0)
		assertRun(poker[0], right = 0, wrong = 0, unscored = 52)
		assertRun(poker[1], right = 21, wrong = 5, unscored = 26)
		assertRun(poker[2], right = 42, wrong = 10, unscored = 0)
	}

	/**
	 * The 0% row is visible on the stats table itself, not merely implied by the counts.
	 *
	 * Rendered through DrillStatsTable rather than read off the run, because 0% and the dash are
	 * the two readings this fixture exists to keep apart and only the renderer decides which of
	 * them a row shows. A fixed zone, since a stable dump is the point of the whole fixture.
	 */
	@Test
	fun seededRunsPutAZeroPercentRowOnTheStatsTable() {
		val runs = seededRuns(seededRoot("zeropercent-test"), DrillKind.NUMBERS)

		val table = DrillStatsTable.render(
			runs = runs,
			kind = DrillKind.NUMBERS,
			sort = DrillStatsTable.DEFAULT_SORT,
			highlightEvery = 5,
			zone = ZoneId.of("UTC")
		)

		val accuracy = table.columns.indexOfFirst { it.id == DrillStatsTable.ID_ACCURACY }
		// Newest first, which is what DEFAULT_SORT promises: the fully scored run, then the
		// half scored one, then the untouched one.
		assertEquals(listOf("80%", "40%", "0%"), table.rows.map { it[accuracy] })
	}

	/**
	 * Fixed timestamps and fixed durations, both spelled out, so a dump taken today and one
	 * taken next year are the same file.
	 */
	@Test
	fun seededRunTimestampsAndDurationsAreFixed() {
		val paths = seededRoot("runtime-test")

		val numbers = seededRuns(paths, DrillKind.NUMBERS)
		val poker = seededRuns(paths, DrillKind.POKER)

		assertEquals(NUMBERS_STARTED_AT, numbers.map { it.startedAt })
		assertEquals(POKER_STARTED_AT, poker.map { it.startedAt })
		// Held oldest first, the order DrillRunsRepository.save trims from.
		assertEquals(numbers.map { it.startedAt }.sorted(), numbers.map { it.startedAt })
		assertEquals(poker.map { it.startedAt }.sorted(), poker.map { it.startedAt })
		// Exact equality and no tolerance: seconds is a Float stored as a Double, and the point
		// of 83.4f is that it reads back as itself rather than as a neighbouring float.
		assertEquals(SECONDS, numbers.map { it.seconds })
		assertEquals(SECONDS, poker.map { it.seconds })
		// A clock-derived fixture would land within seconds of now; this one is pinned to 2023.
		val newestAge = System.currentTimeMillis() - poker.last().startedAt
		assertTrue("run timestamps look clock-derived", newestAge > 24L * 60 * 60 * 1000)
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
		assertEquals(first.views.readText(), second.views.readText())
		// The runs are the one fixture built by a generator, so they are the only one a stray
		// Random.Default would break - and it would break it silently, since a reshuffled deck
		// is still a valid deck.
		assertEquals(first.numbersRuns.readText(), second.numbersRuns.readText())
		assertEquals(first.pokerRuns.readText(), second.pokerRuns.readText())
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

	/**
	 * A survivor of the wipe has to stop the run, not pass quietly.
	 *
	 * A read-only parent is the one portable way to make deleteRecursively fail: entries
	 * cannot be removed from a directory that is not writable. Skipped when the test user
	 * can write anyway, which is what happens as root.
	 */
	@Test
	fun seedStopsWhenAStaleFileSurvivesTheWipe() {
		val root = tempFolder.newFolder("locked-test")
		File(root, "stale.txt").writeText("stale")
		root.setWritable(false, false)
		assumeFalse("running as root, where a read-only directory is still writable", root.canWrite())

		try {
			TestMode.seed(AnkiPaths.at(root))
			fail("seed must not pass over a file it could not delete")
		} catch (e: IllegalStateException) {
			assertTrue(e.message!!.contains("not pristine"))
		} finally {
			// Or TemporaryFolder cannot clean up after itself.
			root.setWritable(true, false)
		}
	}

	/**
	 * The dump exists to mirror what the page was handed, so the two documents have to
	 * agree on every spelling. Direction is the only one either side converts.
	 */
	@Test
	fun theDumpAndThePagePayloadSpellTheSortDirectionAlike() {
		val paths = AnkiPaths.at(tempFolder.newFolder("wire-test"))
		paths.ensureRoot()

		for (dir in SortDir.entries) {
			val table = SAMPLE_TABLE.copy(sort = SortSpec("Question", dir))
			TestMode.writeDump(paths, table)

			val dumped = JSONObject(paths.dump.readText()).getJSONObject("sort").getString("dir")
			val sent = JSONObject(table.toPayloadJson(darkTheme = false, highlightColor = "#DAD5E4"))
				.getJSONObject("sort").getString("dir")
			assertEquals("dump and payload disagree on $dir", sent, dumped)
		}
	}

	/** Seeds a fresh temp folder whose name ends in "-test", and returns its paths. */
	private fun seededRoot(name: String): AnkiPaths {
		val paths = AnkiPaths.at(tempFolder.newFolder(name))
		TestMode.seed(paths)
		return paths
	}

	/**
	 * [kind]'s seeded runs, oldest first, read back the way the app reads them.
	 *
	 * Through DrillRunsRepository and DrillKind.runsFile rather than off a path spelled here,
	 * so a test asserting the fixture is asserting what the drill screens will actually load.
	 */
	private fun seededRuns(paths: AnkiPaths, kind: DrillKind): List<DrillRun> =
		DrillRunsRepository(kind.runsFile(paths)).load()

	/**
	 * [run] holds exactly these marks - and [unscored] is checked as well as the other two,
	 * because right and wrong alone cannot tell a half-scored run from a shorter finished one.
	 */
	private fun assertRun(run: DrillRun, right: Int, wrong: Int, unscored: Int) {
		assertEquals("right in run ${run.id}", right, run.right)
		assertEquals("wrong in run ${run.id}", wrong, run.wrong)
		assertEquals(
			"unscored in run ${run.id}",
			unscored,
			run.items.count { it.status == ItemStatus.UNSCORED }
		)
	}

	private companion object {
		/** What NumbersSettings defaults to, and so what a seeded Numbers run holds. */
		const val NUMBERS_COUNT = 50

		/** A Numbers cell: two digits, zero padded, never one and never three. */
		val TWO_DIGITS = Regex("[0-9]{2}")

		/**
		 * The seeded run timestamps, spelled out rather than computed from the same constants
		 * the fixture uses - which would agree with any arithmetic, including the wrong sort.
		 *
		 * 2023-11-14T23:13:20Z and the two hours after it, then Poker's block an hour later
		 * again, so no two runs in a seeded root share a start time.
		 */
		val NUMBERS_STARTED_AT =
			listOf(1_700_003_600_000L, 1_700_007_200_000L, 1_700_010_800_000L)
		val POKER_STARTED_AT =
			listOf(1_700_014_400_000L, 1_700_018_000_000L, 1_700_021_600_000L)

		/** Each drill's three durations, oldest run first. */
		val SECONDS = listOf(83.4f, 96.5f, 120.0f)

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
