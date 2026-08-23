package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.CounterSettings
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.HistorySettings
import net.jacoblo.simpleanki.data.MetronomeSettings
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.recordAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the rule the lifetime counter exists to enforce: one increment per history
 * record appended, whatever the rolling history window does to the records themselves.
 */
class ReviewRecorderTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun oneAnswerAppendsOneRecordAndAdvancesTheCountByOne() {
		val fixture = Fixture()

		val recorded = fixture.record(fixture.entry("q1"), maxEntries = 10)

		assertEquals(listOf("q1"), recorded.history.map { it.question })
		assertEquals(1, recorded.settings.counters.lifetimeReviews)
		// Banked, not just returned: the count has to survive a restart.
		assertEquals(1, fixture.settingsRepository.load().counters.lifetimeReviews)
	}

	@Test
	fun theCounterKeepsGrowingWhileHistoryIsTrimmedToTheWindow() {
		val fixture = Fixture()

		var settings = Settings()
		var history = emptyList<HistoryEntry>()
		repeat(5) { i ->
			val recorded = fixture.record(
				fixture.entry("q" + i),
				maxEntries = 2,
				settings = settings,
				history = history
			)
			settings = recorded.settings
			// Threaded through exactly as MainActivity threads it: the caller owns the
			// list now, and recordAnswer no longer re-reads the file to find it.
			history = recorded.history
		}

		// The exact divergence the counter exists to survive: five cards were reviewed,
		// but history.json can only prove two of them.
		assertEquals(5, settings.counters.lifetimeReviews)
		assertEquals(5, fixture.settingsRepository.load().counters.lifetimeReviews)
		assertEquals(listOf("q3", "q4"), fixture.historyRepository.load().map { it.question })
	}

	@Test
	fun aTimedOutAnswerCountsLikeAnyOther() {
		val fixture = Fixture()

		// Task 15 appends a record on metronome timeout; every card shown counts.
		val recorded = fixture.record(fixture.entry("q1", timedOut = true), maxEntries = 10)

		assertEquals(1, recorded.settings.counters.lifetimeReviews)
		assertEquals(listOf(true), recorded.history.map { it.timedOut })
	}

	@Test
	fun bumpingTheCounterLeavesEveryOtherPreferenceAlone() {
		val fixture = Fixture()
		val configured = Settings(
			metronome = MetronomeSettings(enabled = true, intervalSeconds = 4.5f, soundPath = "/sdcard/t.wav"),
			history = HistorySettings(maxEntries = 2),
			counters = CounterSettings(15700)
		)
		val expected = configured.copy(counters = CounterSettings(15701))

		val recorded = fixture.record(fixture.entry("q1"), maxEntries = 2, settings = configured)

		assertEquals(expected, recorded.settings)
		assertEquals(expected, fixture.settingsRepository.load())
	}

	/** One temp folder wired to real repositories; no mocks are needed for any of this. */
	private inner class Fixture {
		val paths = AnkiPaths.at(tempFolder.newFolder())
		val historyRepository = HistoryRepository(paths)
		val settingsRepository = SettingsRepository(paths)
		private var nextTimestamp = 1L

		fun entry(question: String, timedOut: Boolean = false) =
			HistoryEntry(question, "a", 1.0f, nextTimestamp++, timedOut)

		fun record(
			entry: HistoryEntry,
			maxEntries: Int,
			settings: Settings = Settings(),
			history: List<HistoryEntry> = emptyList()
		) = recordAnswer(
			historyRepository, settingsRepository, settings, history, entry, maxEntries
		)
	}
}
