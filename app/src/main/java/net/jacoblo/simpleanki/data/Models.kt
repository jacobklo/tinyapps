/*
 * Shared data types for decks, history, settings, table views, and drills.
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

/**
 * How the user marked one drill cell after the fact.
 *
 * UNSCORED means "not checked" and WRONG means "checked and missed". The two stay
 * distinct on disk so a half-scored run is still recognisable as one, but they weigh
 * exactly the same in [DrillRun.accuracy] - see the note there.
 */
enum class ItemStatus { UNSCORED, RIGHT, WRONG }

/**
 * One cell of a drill. [status] is the ONLY state a cell has - whether its value is
 * revealed while scoring is derived from it (UNSCORED hides, the other two reveal),
 * so there is no second flag that could disagree with the mark.
 */
data class DrillItem(val value: String, val status: ItemStatus = ItemStatus.UNSCORED)

/**
 * One completed drill: the values that were shown, how long they were studied, and how
 * each of them was marked afterwards.
 *
 * Every figure below is derived from [items] on read rather than stored beside them.
 * Scoring rewrites the run on every tap and the file is hand-editable, so a persisted
 * right/wrong total would be a second copy free to drift out of step with the marks it
 * claims to count.
 *
 * [id] is [startedAt] rendered as a decimal string. A collision needs two runs to start
 * within the same millisecond, which a drill spanning seconds cannot reach.
 */
data class DrillRun(
	val id: String,
	val startedAt: Long,
	val seconds: Float,
	val items: List<DrillItem>
) {
	val count: Int get() = items.size
	val right: Int get() = items.count { it.status == ItemStatus.RIGHT }
	val wrong: Int get() = items.count { it.status == ItemStatus.WRONG }

	/**
	 * right / count. Null for an empty run, which only a hand-edited file can produce.
	 *
	 * The denominator is [count] and not right + wrong on purpose: an item the user
	 * never checked counts against them exactly as a missed one does. Dividing by the
	 * checked items instead would score a run where one cell was marked right and the
	 * rest left alone as 100%.
	 *
	 * Null rather than 0f because 0f is a legitimate accuracy - every item marked
	 * wrong - so returning it for an empty run makes "no data" indistinguishable from
	 * a disastrous session, and it would sort, average and render as a real 0% in the
	 * stats table. Null leaves the caller no option but to print a dash.
	 */
	val accuracy: Float? get() = if (count == 0) null else right.toFloat() / count

	/** seconds / count, null for an empty run for the same reason as [accuracy]. */
	val secondsPerItem: Float? get() = if (count == 0) null else seconds / count
}

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

/**
 * Size and shape of the Numbers grid.
 *
 * The defaults put the whole grid inside a 360dp phone with no horizontal scrolling -
 * 5 columns at 64dp is 320dp. Raising a column count or a cell size is meant to be what
 * makes a grid scroll, so recompute that product before changing any default here.
 */
data class NumbersSettings(
	val count: Int = 50,
	val columns: Int = 5,
	val cellWidthDp: Int = 64,
	val cellHeightDp: Int = 56
)

/**
 * Size and shape of the Poker grid. 6 columns at 56dp is 336dp, inside 360dp for the
 * same reason as [NumbersSettings].
 *
 * No count: Poker is one full deck, always 52, so there is nothing here to configure.
 */
data class PokerSettings(
	val columns: Int = 6,
	val cellWidthDp: Int = 56,
	val cellHeightDp: Int = 56
)

data class Settings(
	val metronome: MetronomeSettings = MetronomeSettings(),
	val table: TableSettings = TableSettings(),
	val history: HistorySettings = HistorySettings(),
	val counters: CounterSettings = CounterSettings(),
	val numbers: NumbersSettings = NumbersSettings(),
	val poker: PokerSettings = PokerSettings()
)
