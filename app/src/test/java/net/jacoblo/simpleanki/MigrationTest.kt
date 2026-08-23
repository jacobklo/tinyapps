package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.HistoryRepository
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** Covers the one-time timedOut migration applied to pre-existing history.json files. */
class MigrationTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun migrateReturnsNullWhenEveryRecordAlreadyHasTheKey() {
		val raw = arrayOf(
			record("a", 3.0, 1L, timedOut = false),
			record("b", 12.0, 2L, timedOut = true)
		).joinToString(",", "[", "]")
		assertNull(HistoryRepository.migrate(raw))
	}

	@Test
	fun migrateReturnsNullForAnEmptyArray() {
		assertNull(HistoryRepository.migrate("[]"))
	}

	@Test
	fun migrateFillsOnlyTheRecordsMissingTheKey() {
		val raw = "[" +
			record("kept", 1.0, 1L, timedOut = true) + "," +
			record("filled", 20.0, 2L) + "," +
			record("untouched", 30.0, 3L, timedOut = false) +
		"]"

		val migrated = HistoryRepository.migrate(raw)
		assertNotNull(migrated)

		val array = JSONArray(migrated)
		assertEquals(3, array.length())
		// A record that already carries the key keeps its value even when it disagrees
		// with the 10 second rule.
		assertTrue(array.getJSONObject(0).getBoolean("timedOut"))
		assertTrue(array.getJSONObject(1).getBoolean("timedOut"))
		assertFalse(array.getJSONObject(2).getBoolean("timedOut"))
	}

	@Test
	fun migrateTreatsExactlyTenSecondsAsTimedOut() {
		val migrated = HistoryRepository.migrate("[" + record("q", 10.0, 1L) + "]")
		assertNotNull(migrated)
		assertTrue(JSONArray(migrated).getJSONObject(0).getBoolean("timedOut"))
	}

	@Test
	fun migrateTreatsIntegerSerializedTenSecondsAsTimedOut() {
		// A real legacy history.json holds "timeTaken":10, not 10.0 - org.json trims
		// the trailing zero of a whole double on both Android and the reference impl,
		// so this hand written text is the exact on-disk form. Built as a raw string
		// rather than through JSONObject.put so the numeric form cannot drift.
		val raw = "[{\"question\":\"q\",\"answer\":\"a\",\"timeTaken\":10,\"timestamp\":1}]"
		val migrated = HistoryRepository.migrate(raw)
		assertNotNull(migrated)
		assertTrue(JSONArray(migrated).getJSONObject(0).getBoolean("timedOut"))
	}

	@Test
	fun migrateTreatsJustUnderTenSecondsAsAnswered() {
		val migrated = HistoryRepository.migrate("[" + record("q", 9.99, 1L) + "]")
		assertNotNull(migrated)
		assertFalse(JSONArray(migrated).getJSONObject(0).getBoolean("timedOut"))
	}

	@Test
	fun loadBacksUpTheOriginalTextBeforeRewriting() {
		val paths = AnkiPaths.at(tempFolder.root)
		val raw = "[" + record("q", 11.0, 1L) + "]"
		paths.history.writeText(raw)
		paths.historyBackup.writeText("stale backup")

		val loaded = HistoryRepository(paths).load()

		assertEquals(1, loaded.size)
		assertTrue(loaded[0].timedOut)
		assertEquals(11.0f, loaded[0].timeTaken, 0.001f)
		assertEquals(raw, paths.historyBackup.readText())
		assertTrue(paths.history.readText().contains("timedOut"))
	}

	@Test
	fun loadLeavesAnAlreadyMigratedFileAlone() {
		val paths = AnkiPaths.at(tempFolder.root)
		val raw = "[" + record("q", 4.0, 7L, timedOut = false) + "]"
		paths.history.writeText(raw)

		val loaded = HistoryRepository(paths).load()

		assertEquals(1, loaded.size)
		assertFalse(loaded[0].timedOut)
		assertFalse(paths.historyBackup.exists())
		assertEquals(raw, paths.history.readText())
	}

	@Test
	fun loadReturnsEmptyForMissingOrMalformedFiles() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = HistoryRepository(paths)
		assertTrue(repository.load().isEmpty())

		paths.history.writeText("{ not an array")
		assertTrue(repository.load().isEmpty())
	}

	@Test
	fun loadSkipsOneBadRecordAndKeepsTheRest() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.history.writeText(
			"[" +
				record("good1", 2.0, 1L, timedOut = false) + "," +
				"{\"question\":\"broken\",\"timedOut\":false}," +
				record("good2", 3.0, 3L, timedOut = false) +
			"]"
		)

		// One unreadable row must not discard the whole history; history.json is the
		// only record of every per-card figure.
		val loaded = HistoryRepository(paths).load()

		assertEquals(listOf("good1", "good2"), loaded.map { it.question })
	}

	@Test
	fun saveKeepsTheNewestEntriesOldestFirst() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = HistoryRepository(paths)
		val entries = (1..5).map {
			HistoryEntry("q$it", "a$it", it.toFloat(), it.toLong(), timedOut = false)
		}

		repository.save(entries, 3)

		val reloaded = repository.load()
		assertEquals(listOf("q3", "q4", "q5"), reloaded.map { it.question })
	}

	/**
	 * An unreadable file is not an empty one.
	 *
	 * The caller writes back the list this returns, so answering "no records" for a file
	 * that is merely unreadable would replace every stored record with one on the next
	 * card flip. A directory stands in for the unreadable file: it exists, and reading it
	 * throws, which is exactly the shape of a permission failure on device.
	 */
	@Test
	fun loadThrowsRatherThanReportingAnUnreadableFileAsEmpty() {
		val paths = AnkiPaths.at(tempFolder.root)
		assertTrue(paths.history.mkdirs())

		try {
			HistoryRepository(paths).load()
			fail("an unreadable history.json must not read as an empty one")
		} catch (e: IOException) {
			assertTrue(e.message!!.contains("history.json"))
		}
	}

	private fun record(
		question: String,
		timeTaken: Double,
		timestamp: Long,
		timedOut: Boolean? = null
	): String {
		val timedOutPart = if (timedOut == null) "" else ",\"timedOut\":$timedOut"
		return "{\"question\":\"$question\",\"answer\":\"a\"," +
			"\"timeTaken\":$timeTaken,\"timestamp\":$timestamp$timedOutPart}"
	}
}
