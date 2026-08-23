/*
 * The one place an answered card is written down.
 *
 * Lives in data/ rather than in AppContainer or MainActivity so it can be exercised on
 * the JVM: both repositories reach the disk only through AnkiPaths, so a test needs a
 * TemporaryFolder and no mocks.
 */
package net.jacoblo.simpleanki.data

/** What one answer left behind: the trimmed history, and the settings that were banked. */
data class RecordedAnswer(val history: List<HistoryEntry>, val settings: Settings)

/**
 * Appends [entry] to [history] and advances the lifetime review counter by exactly one.
 *
 * The counter is tied to the record being appended rather than to any UI event, so a
 * metronome timeout counts for the same reason a flip does, and the append runs first
 * so a failed history write cannot inflate the count.
 *
 * [history] is passed in rather than re-read. This runs on the UI thread on every card
 * flip and the caller already holds the authoritative list; re-reading would parse up to
 * [maxEntries] records twice - once to migrate, once to parse - before rewriting them.
 * The appended list is only RETURNED once the write has succeeded, so a caller that
 * assigns the result keeps the screen showing exactly what reached the disk.
 *
 * The two figures are deliberately not derivable from one another. History is trimmed
 * to the newest [maxEntries] on every write while the counter only ever grows, which is
 * the whole reason the counter is stored rather than computed.
 *
 * Propagates IOException when either file cannot be written. A failed settings write
 * throws after the record has already been appended, leaving the stored count one
 * behind the records on disk; the next answer resumes from the stored count, so that
 * gap stays at one rather than widening.
 */
fun recordAnswer(
	historyRepository: HistoryRepository,
	settingsRepository: SettingsRepository,
	settings: Settings,
	history: List<HistoryEntry>,
	entry: HistoryEntry,
	maxEntries: Int
): RecordedAnswer {
	val appended = (history + entry).takeLast(maxEntries)
	historyRepository.save(appended, maxEntries)
	// copy() rather than a fresh Settings(): every other preference must survive a bump.
	val banked = settings.copy(
		counters = CounterSettings(settings.counters.lifetimeReviews + 1)
	)
	settingsRepository.save(banked)
	return RecordedAnswer(appended, banked)
}
