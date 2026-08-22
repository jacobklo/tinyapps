/*
 * Shared data types for decks, history, settings, and table views.
 *
 * Free of Android imports on purpose so JVM tests can build and assert on these
 * types with no emulator.
 */
package net.jacoblo.simpleanki.data

data class AnkiCard(val question: String, val answer: String)

data class HistoryEntry(
	val question: String,
	val answer: String,
	val timeTaken: Float,
	val timestamp: Long,
	val timedOut: Boolean
)

enum class ColumnType { TEXT, NUMBER, TIME, BOOL }

enum class CellFormat { TEXT, INT, ONE_DP, TWO_DP, PERCENT, TIME }

enum class Aggregate { MIN, MAX, AVG, MEDIAN, SUM, COUNT, ACCURACY, STDDEV }

/** How the rows feeding a computed column are grouped. */
sealed interface Partition {
	data class Group(val by: String) : Partition
	data class Bucket(val size: Int) : Partition
	data class Rolling(val size: Int) : Partition
}

data class ComputedSpec(
	val aggregate: Aggregate,
	val source: String,
	val partition: Partition,
	val limit: Int
)

data class ColumnSpec(
	val id: String,
	val title: String,
	val width: Int,
	val visible: Boolean = true,
	val frozen: Boolean = false,
	val format: CellFormat? = null,
	val computed: ComputedSpec? = null,
	val formula: String? = null,
	val formulaError: String? = null
)

enum class SortDir { ASC, DESC }

data class SortSpec(val column: String, val dir: SortDir)

data class TableView(
	val id: String,
	val name: String,
	val filterToCurrentDeck: Boolean,
	val collapseDuplicatesOn: String?,
	val highlightEvery: Int,
	val defaultSort: SortSpec,
	val columns: List<ColumnSpec>
)

data class MetronomeSettings(
	val enabled: Boolean = false,
	val intervalSeconds: Float = 10.0f,
	val soundPath: String? = null
)

data class TableSettings(
	val defaultLimit: Int = 10,
	val highlightEvery: Int = 5,
	val defaultWindowSize: Int = 100
)

data class HistorySettings(val maxEntries: Int = 5000)

/**
 * Totals that outlive the rolling history window.
 *
 * [lifetimeReviews] counts every card ever shown, timeouts included. It cannot be
 * derived from history.json, which keeps only the newest [HistorySettings.maxEntries]
 * attempts, so it is stored rather than computed.
 */
data class CounterSettings(val lifetimeReviews: Int = 0)

data class Settings(
	val metronome: MetronomeSettings = MetronomeSettings(),
	val table: TableSettings = TableSettings(),
	val history: HistorySettings = HistorySettings(),
	val counters: CounterSettings = CounterSettings()
)
