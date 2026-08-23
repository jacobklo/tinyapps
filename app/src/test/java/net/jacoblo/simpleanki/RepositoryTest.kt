package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.CellFormat
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.DefaultViews
import net.jacoblo.simpleanki.data.JsonStore
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.ReadResult
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.data.ViewsFile
import net.jacoblo.simpleanki.data.ViewsRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Covers views.json end to end, plus the two JsonStore weaknesses both stored files now
 * depend on: telling an absent file from an unreadable one, and never overwriting a
 * quarantine that already exists.
 *
 * Serialized text is never asserted on directly. The test classpath's org.json is
 * HashMap-backed while Android's is LinkedHashMap-backed, so key order is arbitrary here
 * and stable on device; round-trip equality is the assertion that survives both.
 *
 * An unreadable file is spelled as a DIRECTORY at the file's path. It exists, and reading
 * it throws - the same shape a permission failure takes on device - and unlike chmod it
 * behaves identically whatever user the tests run as.
 */
class RepositoryTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val tableSettings = TableSettings()

	// -- JsonStore ------------------------------------------------------------------------

	@Test
	fun readTellsAbsentApartFromUnreadable() {
		val absent = File(tempFolder.root, "absent.json")
		assertEquals(ReadResult.Absent, JsonStore(absent).read())

		val present = File(tempFolder.root, "present.json").apply { writeText("{}") }
		assertEquals(ReadResult.Present("{}"), JsonStore(present).read())

		val unreadable = File(tempFolder.root, "unreadable.json").apply { mkdirs() }
		assertEquals(ReadResult.Unreadable, JsonStore(unreadable).read())
	}

	@Test
	fun quarantineKeepsTheFirstCorruptFileRatherThanTheLatest() {
		val file = File(tempFolder.root, "settings.json")
		val corrupt = File(tempFolder.root, "settings.json.corrupt")
		file.writeText("the user's real file")

		assertTrue(JsonStore(file).quarantine())
		assertEquals("the user's real file", corrupt.readText())

		// A second incident, whose file is only the defaults written after the first one.
		file.writeText("defaults this app wrote")
		assertFalse(JsonStore(file).quarantine())

		// The valuable copy is the one that survives, and the second file is left where it
		// was for the caller's own write to replace.
		assertEquals("the user's real file", corrupt.readText())
		assertEquals("defaults this app wrote", file.readText())
	}

	// -- ViewsRepository ------------------------------------------------------------------

	@Test
	fun anAbsentViewsFileIsCreatedFromTheBuiltIns() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = ViewsRepository(paths)

		val loaded = repository.load(tableSettings)

		assertEquals(DefaultViews.all(tableSettings), loaded.views)
		assertEquals("stats", loaded.activeViewId)
		// Written, not just returned: the point of the file is that it can be hand-edited.
		assertTrue(paths.views.exists())
		assertEquals(loaded, repository.load(tableSettings))
		// Nothing was there to preserve, so nothing was quarantined.
		assertFalse(File(paths.views.path + ".corrupt").exists())
	}

	@Test
	fun aCorruptViewsFileIsQuarantinedAndThenRecreated() {
		val paths = AnkiPaths.at(tempFolder.root)
		val truncated = "{\"views\":[{\"id\":\"stats\""
		paths.views.writeText(truncated)

		val loaded = ViewsRepository(paths).load(tableSettings)

		// The unreadable text is kept, so a set of hand-tuned views is never discarded
		// without a trace.
		assertEquals(truncated, File(paths.views.path + ".corrupt").readText())
		assertEquals(DefaultViews.all(tableSettings), loaded.views)
		assertEquals(loaded, ViewsRepository(paths).load(tableSettings))
	}

	@Test
	fun anEmptyViewsArrayIsTreatedAsCorrupt() {
		val paths = AnkiPaths.at(tempFolder.root)
		val empty = "{\"schemaVersion\":1,\"activeViewId\":\"stats\",\"views\":[]}"
		paths.views.writeText(empty)

		val loaded = ViewsRepository(paths).load(tableSettings)

		// No views means a drawer with no table entries and no way back to a working
		// state, so it is a broken file rather than a user who deleted every view.
		assertEquals(empty, File(paths.views.path + ".corrupt").readText())
		assertEquals(DefaultViews.all(tableSettings), loaded.views)
	}

	@Test
	fun aDocumentThatIsNotAnObjectIsCorrupt() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.views.writeText("[]")

		assertEquals(DefaultViews.all(tableSettings), ViewsRepository(paths).load(tableSettings).views)
		assertEquals("[]", File(paths.views.path + ".corrupt").readText())
	}

	@Test
	fun anUnreadableViewsFileIsNeitherQuarantinedNorReplaced() {
		val paths = AnkiPaths.at(tempFolder.root)
		assertTrue(paths.views.mkdirs())

		// Defaults for this run only; the file itself is left completely alone so the next
		// call retries what may be a perfectly good file behind a transient failure.
		assertEquals(DefaultViews.all(tableSettings), ViewsRepository(paths).load(tableSettings).views)
		assertFalse(File(paths.views.path + ".corrupt").exists())
		assertTrue(paths.views.isDirectory)

		try {
			ViewsRepository(paths).save(ViewsRepository.defaults(tableSettings))
			fail("save must refuse a file it cannot read")
		} catch (e: IOException) {
			assertTrue(e.message!!.contains("views.json"))
		}
	}

	@Test
	fun everyFieldOfEveryColumnRoundTrips() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = ViewsRepository(paths)
		val file = ViewsFile("custom", listOf(kitchenSinkView()) + DefaultViews.all(tableSettings))

		repository.save(file)

		assertEquals(file, repository.load(tableSettings))
	}

	@Test
	fun aResizeSurvivesAReloadOfTheStoredFile() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = ViewsRepository(paths)
		val loaded = repository.load(tableSettings)
		val history = loaded.views.single { it.id == "history" }
		val widened = history.copy(
			columns = history.columns.map { if (it.id == "Question") it.copy(width = 313) else it }
		)

		// Exactly what MainActivity's onViewChanged does with a header drag.
		repository.save(loaded.copy(views = loaded.views.map { if (it.id == "history") widened else it }))

		val reloaded = ViewsRepository(paths).load(tableSettings)
		assertEquals(313, reloaded.views.single { it.id == "history" }
			.columns.single { it.id == "Question" }.width)
	}

	@Test
	fun anActiveViewIdNamingNoViewFallsBackToTheFirst() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = ViewsRepository(paths)
		repository.save(ViewsFile("gone", DefaultViews.all(tableSettings)))

		assertEquals("stats", repository.load(tableSettings).activeViewId)
		// A stored id that does name a view is left alone.
		repository.save(ViewsFile("list_rows", DefaultViews.all(tableSettings)))
		assertEquals("list_rows", repository.load(tableSettings).activeViewId)
	}

	@Test
	fun unknownKeysSurviveASave() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = ViewsRepository(paths)
		repository.save(ViewsRepository.defaults(tableSettings))
		val onDisk = JSONObject(paths.views.readText())
		onDisk.put("futureTopLevel", 7)
		onDisk.getJSONArray("views").getJSONObject(0).put("futureView", "keep me")
		onDisk.getJSONArray("views").getJSONObject(0)
			.getJSONArray("columns").getJSONObject(0).put("futureColumn", true)
		paths.views.writeText(onDisk.toString())

		// A downgrade rewriting the file must not delete a newer build's fields.
		repository.save(repository.load(tableSettings))

		val rewritten = JSONObject(paths.views.readText())
		assertEquals(7, rewritten.getInt("futureTopLevel"))
		val stats = rewritten.getJSONArray("views").getJSONObject(0)
		assertEquals("keep me", stats.getString("futureView"))
		assertTrue(stats.getJSONArray("columns").getJSONObject(0).getBoolean("futureColumn"))
	}

	@Test
	fun collapseDuplicatesOnReachesTheFileAsAJsonNull() {
		val paths = AnkiPaths.at(tempFolder.root)
		ViewsRepository(paths).save(ViewsRepository.defaults(tableSettings))

		// put(name, null) would drop the key outright, and optString on a JSON null gives
		// back the literal "null" - both would read back as a collapse on a column named
		// "null" rather than as no collapse at all.
		val history = JSONObject(paths.views.readText()).getJSONArray("views").getJSONObject(1)
		assertTrue(history.has("collapseDuplicatesOn"))
		assertTrue(history.isNull("collapseDuplicatesOn"))
		assertNull(ViewsRepository(paths).load(tableSettings).views[1].collapseDuplicatesOn)
	}

	@Test
	fun resetBuiltInsRestoresTheBuiltInsAndLeavesCustomViewsAlone() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = ViewsRepository(paths)
		val custom = kitchenSinkView()
		val mangled = DefaultViews.statsView(tableSettings).copy(name = "Wrecked", columns = emptyList())
		// A custom view before and after a built-in, and one built-in deleted outright.
		val current = ViewsFile("custom", listOf(custom, mangled, custom.copy(id = "other")))

		val reset = repository.resetBuiltIns(current, tableSettings)

		// The built-in that was there is restored in place; the two that were missing are
		// appended; both custom views keep their contents and their positions.
		assertEquals(
			listOf("custom", "stats", "other", "history", "list_rows"),
			reset.views.map { it.id }
		)
		assertEquals(custom, reset.views[0])
		assertEquals(custom.copy(id = "other"), reset.views[2])
		assertEquals(DefaultViews.statsView(tableSettings), reset.views[1])
		assertEquals(DefaultViews.historyView(tableSettings), reset.views[3])
		assertEquals("custom", reset.activeViewId)
		assertNotEquals(mangled, reset.views[1])
	}

	// -- SettingsRepository ---------------------------------------------------------------

	@Test
	fun anUnreadableSettingsFileIsNeitherQuarantinedNorReplaced() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.legacyStats.writeText("{\"statsUpdateCount\":15700}")
		assertTrue(paths.settings.mkdirs())

		// Not seeded from stats.json: an unreadable file is not a first run, and treating
		// it as one would bank a stale total over a live one.
		assertEquals(Settings(), SettingsRepository(paths).load())
		assertFalse(File(paths.settings.path + ".corrupt").exists())
		assertTrue(paths.settings.isDirectory)
	}

	@Test
	fun savingOverAnUnreadableSettingsFileIsRefused() {
		val paths = AnkiPaths.at(tempFolder.root)
		assertTrue(paths.settings.mkdirs())

		try {
			// The merge needs the current contents; without them this would replace a file
			// that may be perfectly healthy, and settings.json holds the only copy of the
			// lifetime review count.
			SettingsRepository(paths).save(Settings())
			fail("save must refuse a file it cannot read")
		} catch (e: IOException) {
			assertTrue(e.message!!.contains("settings.json"))
		}
	}

	@Test
	fun aSecondCorruptSettingsFileDoesNotDestroyTheFirstQuarantine() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.legacyStats.writeText("{\"statsUpdateCount\":15700}")
		val real = "{\"counters\":{\"lifetimeReviews\":15700},\"truncated\""
		paths.settings.writeText(real)

		assertEquals(15700, SettingsRepository(paths).load().counters.lifetimeReviews)
		// The recovered file corrupts too, some time later.
		paths.settings.writeText("{\"counters\"")
		assertEquals(15700, SettingsRepository(paths).load().counters.lifetimeReviews)

		// The first quarantine is the one holding the user's own data; the second incident
		// would only have overwritten it with the defaults this app wrote.
		assertEquals(real, File(paths.settings.path + ".corrupt").readText())
	}

	/** One view exercising every optional field, every format, and every partition mode. */
	private fun kitchenSinkView(): TableView = TableView(
		id = "custom",
		name = "Custom",
		filterToCurrentDeck = false,
		collapseDuplicatesOn = "Question",
		highlightEvery = 7,
		defaultSort = SortSpec("best10", SortDir.DESC),
		columns = listOf(
			ColumnSpec("Question", "Question", 160, visible = true, frozen = true, format = CellFormat.TEXT),
			ColumnSpec(
				"best10", "Best", 70, visible = false, frozen = false, format = CellFormat.TWO_DP,
				computed = ComputedSpec(Aggregate.MIN, "Seconds", Partition.Group("Question"), 10),
				formula = "=MIN(Seconds, group:Question, last:10)",
				formulaError = "kept verbatim until Task 12 recomputes it"
			),
			ColumnSpec(
				"acc", "Accuracy", 80, format = CellFormat.PERCENT,
				computed = ComputedSpec(Aggregate.ACCURACY, "TimedOut", Partition.Bucket(25), 0)
			),
			ColumnSpec(
				"avg", "Avg", 90, format = CellFormat.ONE_DP,
				computed = ComputedSpec(Aggregate.AVG, "Seconds", Partition.Rolling(5), 3)
			),
			ColumnSpec("n", "N", 100, format = CellFormat.INT),
			ColumnSpec("when", "When", 110, format = CellFormat.TIME),
			// No format, no aggregate, no formula: every optional key absent.
			ColumnSpec("bare", "Bare", 120)
		)
	)
}
