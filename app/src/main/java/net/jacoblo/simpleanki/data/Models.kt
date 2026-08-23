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

/**
 * The two row tints are stored as "#RRGGBB" text rather than as packed ints because
 * settings.json exists to be hand-edited and a hex string is what a hand-edit types.
 *
 * No alpha channel, deliberately. The tint sits UNDER the row's own text, so a
 * translucent value would blend against whatever is behind it and make the text contrast
 * depend on the theme's surface rather than on the colour the user chose.
 *
 * A malformed value here is not an error - the file is hand-editable, so it must be
 * possible to store one and see it in the settings screen. [highlightColor] is what
 * refuses to hand it to the page.
 */
data class TableSettings(
	val defaultLimit: Int = 10,
	val highlightEvery: Int = 5,
	val defaultWindowSize: Int = 100,
	val highlightColorLight: String = DEFAULT_HIGHLIGHT_LIGHT,
	val highlightColorDark: String = DEFAULT_HIGHLIGHT_DARK
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
