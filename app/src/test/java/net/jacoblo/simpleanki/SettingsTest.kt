package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.CounterSettings
import net.jacoblo.simpleanki.data.HistorySettings
import net.jacoblo.simpleanki.data.MetronomeSettings
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.TableSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers settings.json: the one-time seed of the lifetime review counter from the
 * retired stats.json, corrupt-file quarantine, unknown-key preservation, and round
 * tripping.
 *
 * Serialized text is never asserted on directly. The test classpath's org.json is
 * HashMap-backed while Android's is LinkedHashMap-backed, so key order is arbitrary
 * here and stable on device; every assertion goes through a parsed object instead.
 */
class SettingsTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun seedReadsTheCountFromARealStatsFile() {
		assertEquals(15700, SettingsRepository.seedLifetimeReviews(STATS_FIXTURE))
	}

	@Test
	fun seedIsZeroForAMissingFile() {
		assertEquals(0, SettingsRepository.seedLifetimeReviews(null))
	}

	@Test
	fun seedIsZeroForACorruptFile() {
		assertEquals(0, SettingsRepository.seedLifetimeReviews("{\"statsUpdateCount\":157"))
		// A well formed document that is not an object is corrupt too.
		assertEquals(0, SettingsRepository.seedLifetimeReviews("[15700]"))
		assertEquals(0, SettingsRepository.seedLifetimeReviews(""))
	}

	@Test
	fun seedIsZeroWhenTheKeyIsAbsent() {
		assertEquals(0, SettingsRepository.seedLifetimeReviews("{\"33\":{\"history\":[1.0]}}"))
	}

	@Test
	fun loadSeedsFromStatsAndPersistsOnFirstRun() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.legacyStats.writeText(STATS_FIXTURE)

		val loaded = SettingsRepository(paths).load()

		assertEquals(15700, loaded.counters.lifetimeReviews)
		val stored = JSONObject(paths.settings.readText())
		assertEquals(1, stored.getInt("schemaVersion"))
		assertEquals(15700, stored.getJSONObject("counters").getInt("lifetimeReviews"))
		// Seeding reads stats.json; it must never rewrite it.
		assertEquals(STATS_FIXTURE, paths.legacyStats.readText())
	}

	@Test
	fun loadSeedsZeroWhenThereIsNoStatsFile() {
		val paths = AnkiPaths.at(tempFolder.root)

		assertEquals(Settings(), SettingsRepository(paths).load())
		// The defaults must be persisted, not merely returned: with no file on disk the
		// seed would re-fire on every launch instead of once.
		assertTrue(paths.settings.exists())
		assertEquals(Settings(), SettingsRepository(paths).load())
	}

	@Test
	fun anExistingSettingsFileSuppressesTheSeed() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.legacyStats.writeText(STATS_FIXTURE)
		val repository = SettingsRepository(paths)
		repository.save(Settings(counters = CounterSettings(7)))

		assertEquals(7, repository.load().counters.lifetimeReviews)
		// A user who resets the count stays reset; the stale stats.json cannot undo it.
		repository.save(Settings(counters = CounterSettings(0)))
		assertEquals(0, repository.load().counters.lifetimeReviews)
	}

	@Test
	fun theCounterSurvivesARestart() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.legacyStats.writeText(STATS_FIXTURE)

		// First launch seeds, then one card is answered.
		val first = SettingsRepository(paths)
		val seeded = first.load()
		first.save(seeded.copy(counters = CounterSettings(seeded.counters.lifetimeReviews + 1)))

		// A fresh process reads the bumped total, not the stats.json seed.
		assertEquals(15701, SettingsRepository(paths).load().counters.lifetimeReviews)
	}

	@Test
	fun saveAndLoadRoundTripEveryField() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = SettingsRepository(paths)
		val settings = Settings(
			metronome = MetronomeSettings(enabled = true, intervalSeconds = 4.5f, soundPath = "/sdcard/tick.wav"),
			table = TableSettings(defaultLimit = 3, highlightEvery = 2, defaultWindowSize = 40),
			history = HistorySettings(maxEntries = 120),
			counters = CounterSettings(lifetimeReviews = 15701)
		)

		repository.save(settings)

		assertEquals(settings, repository.load())
	}

	@Test
	fun anExplicitNullSoundPathSurvivesTheRoundTrip() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = SettingsRepository(paths)

		repository.save(Settings(metronome = MetronomeSettings(soundPath = null)))

		// The key must be present and JSON null, not dropped by put(name, null).
		val metronome = JSONObject(paths.settings.readText()).getJSONObject("metronome")
		assertTrue(metronome.has("soundPath"))
		assertTrue(metronome.isNull("soundPath"))
		assertNull(repository.load().metronome.soundPath)
	}

	/**
	 * The on-disk key vocabulary, spelled out.
	 *
	 * The round trip above cannot see a rename: reader and writer share one private KEY_*
	 * constant each, so renaming "lifetimeReviews" in both passes every other test in this
	 * file while resetting the user's real total to zero the next time the app starts.
	 * These literals are typed out rather than read back from the constants precisely so
	 * that they disagree when that happens.
	 */
	@Test
	fun theStoredKeysAreTheContractWithFilesOnTheUsersDisk() {
		val paths = AnkiPaths.at(tempFolder.root)
		SettingsRepository(paths).save(
			Settings(
				metronome = MetronomeSettings(enabled = true, intervalSeconds = 4.5f, soundPath = "/sdcard/tick.wav"),
				table = TableSettings(defaultLimit = 3, highlightEvery = 2, defaultWindowSize = 40),
				history = HistorySettings(maxEntries = 120),
				counters = CounterSettings(lifetimeReviews = 15701)
			)
		)

		val root = JSONObject(paths.settings.readText())
		assertEquals(1, root.getInt("schemaVersion"))

		val metronome = root.getJSONObject("metronome")
		assertTrue(metronome.getBoolean("enabled"))
		assertEquals(4.5, metronome.getDouble("intervalSeconds"), 1e-9)
		assertEquals("/sdcard/tick.wav", metronome.getString("soundPath"))

		val table = root.getJSONObject("table")
		assertEquals(3, table.getInt("defaultLimit"))
		assertEquals(2, table.getInt("highlightEvery"))
		assertEquals(40, table.getInt("defaultWindowSize"))

		assertEquals(120, root.getJSONObject("history").getInt("maxEntries"))
		assertEquals(15701, root.getJSONObject("counters").getInt("lifetimeReviews"))
	}

	@Test
	fun aCorruptSettingsFileIsQuarantinedAndTheCountRecovered() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.legacyStats.writeText(STATS_FIXTURE)
		paths.settings.writeText(TRUNCATED)

		val loaded = SettingsRepository(paths).load()

		// The unreadable text is kept, so nothing is discarded without a trace.
		val quarantined = File(paths.settings.path + ".corrupt")
		assertTrue(quarantined.exists())
		assertEquals(TRUNCATED, quarantined.readText())
		// Recovered from stats.json rather than reset to zero. Zeroing would throw away
		// the only surviving copy of a total the user cannot recompute; re-seeding only
		// loses the reviews logged since the seed.
		assertEquals(15700, loaded.counters.lifetimeReviews)
		val stored = JSONObject(paths.settings.readText())
		assertEquals(15700, stored.getJSONObject("counters").getInt("lifetimeReviews"))
	}

	@Test
	fun aCorruptSettingsFileSeedsExactlyLikeAnAbsentOne() {
		val corrupt = AnkiPaths.at(tempFolder.newFolder("corrupt"))
		val absent = AnkiPaths.at(tempFolder.newFolder("absent"))
		corrupt.legacyStats.writeText(STATS_FIXTURE)
		absent.legacyStats.writeText(STATS_FIXTURE)
		corrupt.settings.writeText(TRUNCATED)

		// No readable settings.json is a first run, however it came to be one. The two
		// cases are one code path and must not drift into disagreeing about the count.
		assertEquals(SettingsRepository(absent).load(), SettingsRepository(corrupt).load())
		assertEquals(15700, SettingsRepository(corrupt).load().counters.lifetimeReviews)
	}

	@Test
	fun aSettingsDocumentThatIsNotAnObjectIsCorrupt() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.settings.writeText("[1, 2, 3]")

		// Zero here because no stats.json exists to recover from, not because a corrupt
		// settings.json resets the count.
		assertEquals(Settings(), SettingsRepository(paths).load())
		assertTrue(File(paths.settings.path + ".corrupt").exists())
	}

	@Test
	fun unknownKeysSurviveALoadAndSave() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.settings.writeText(
			"{\"schemaVersion\":9,\"futureTopLevel\":\"keep me\"," +
				"\"counters\":{\"lifetimeReviews\":5,\"futureCounter\":42}}"
		)
		val repository = SettingsRepository(paths)

		val loaded = repository.load()
		assertEquals(5, loaded.counters.lifetimeReviews)
		repository.save(loaded.copy(counters = CounterSettings(6)))

		// A field a future build added must not be destroyed by this one.
		val stored = JSONObject(paths.settings.readText())
		assertEquals("keep me", stored.getString("futureTopLevel"))
		assertEquals(42, stored.getJSONObject("counters").getInt("futureCounter"))
		assertEquals(6, stored.getJSONObject("counters").getInt("lifetimeReviews"))
	}

	@Test
	fun missingSectionsFallBackToTheDeclaredDefaults() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.settings.writeText("{\"counters\":{\"lifetimeReviews\":12}}")

		val loaded = SettingsRepository(paths).load()

		assertEquals(Settings(counters = CounterSettings(12)), loaded)
	}

	@Test
	fun nothingEverWritesTheLegacyStatsFile() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = SettingsRepository(paths)

		repository.load()
		repository.save(Settings(counters = CounterSettings(3)))
		repository.load()

		assertFalse(paths.legacyStats.exists())
	}

	private companion object {
		/**
		 * A trimmed copy of the user's real pre-migration stats.json: the count key
		 * first, then one object per question holding an array of attempt times. The
		 * real file is 36 KB with 213 such entries and the same 15700 count.
		 */
		const val STATS_FIXTURE = "{\"statsUpdateCount\":15700," +
			"\"33\":{\"history\":[5.581999778747559,1.3270000219345093]}," +
			"\"13\":{\"history\":[1.0570000410079956,2.190000057220459]}}"

		const val TRUNCATED = "{\"counters\":{\"lifetimeReviews\":15700"
	}
}
