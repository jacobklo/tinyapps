/*
 * The two drills, and every place they differ.
 *
 * The drills are behaviourally identical - same grid, same clock, same scoring cycle, same
 * storage shape - so the screens are written once against DrillKind and ask here for the
 * handful of answers that actually differ: how many items a set holds, what to call it, how
 * wide its grid is, and which file its runs live in. Written as extensions on an enum rather
 * than as a sealed class with a subclass per drill, so each answer that varies sits in one
 * exhaustive `when` and a third drill would be a compile error in every one of them rather
 * than a silently inherited default.
 *
 * Free of Android imports on purpose - AnkiPaths reaches Environment only inside its two
 * factory methods - so the whole descriptor is exercised by JVM tests.
 */
package net.jacoblo.simpleanki.drill

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.Settings
import java.io.File

enum class DrillKind { NUMBERS, POKER }

/**
 * The grid's shape, in dp.
 *
 * One type over both drills, so the grid composable takes a shape and never asks which drill
 * it is drawing. NumbersSettings and PokerSettings spell the same three fields but stay
 * separate types, because Numbers has a count and Poker cannot have one.
 */
data class DrillGeometry(val columns: Int, val cellWidthDp: Int, val cellHeightDp: Int)

/**
 * The most items a drill screen will generate, and the ceiling the settings screen's item-count
 * field enforces.
 *
 * ONE constant over both, so the agreement between them is the compiler's business rather than a
 * comment's. [itemCount] below reports whatever settings.json says, unvalidated and on purpose -
 * but DrillGrid is a plain Column and not a lazy one, so a hand-edited "count": 100000 would ask
 * Compose for a hundred thousand cells inside a single frame. That is not a grid that merely
 * looks wrong, which is the outcome the unvalidated pass-through is defending; it is an ANR, and
 * an ANR on the way to the settings screen the typo would have been fixed from.
 *
 * The cap is applied where the count is CONSUMED - see DrillScreen.freshItems - for the reason
 * DrillGrid gives about its own column count: there it is a question about this frame, and the
 * stored value goes on saying exactly what the user typed.
 */
const val MAX_DRILL_ITEMS = 1000

/**
 * How many items a fresh set holds.
 *
 * Poker ignores settings entirely: it is one full deck, always [DrillOps.DECK_SIZE], and a
 * deck with cards missing is not the discipline being trained, so there is nothing here to
 * configure. Numbers reports whatever settings.json says.
 *
 * That includes a zero or a negative left by a hand-edit. Refusing one is the settings
 * field's job, not this function's; clamping it here instead would leave the file saying one
 * thing and the grid showing another, which is a typo the user has no way to find.
 *
 * The single exception is the top end, and it is not made here either: a count above
 * [MAX_DRILL_ITEMS] is capped by the screen that draws the set, because that one cannot be left
 * to show the user their typo - it hangs before it can.
 */
fun DrillKind.itemCount(settings: Settings): Int = when (this) {
	DrillKind.NUMBERS -> settings.numbers.count
	DrillKind.POKER -> DrillOps.DECK_SIZE
}

/** The drawer entry and the title of the drill screen. */
fun DrillKind.displayName(): String = when (this) {
	DrillKind.NUMBERS -> "Numbers"
	DrillKind.POKER -> "Poker"
}

/**
 * The drawer entry and the title of the stats screen.
 *
 * Built from [displayName] rather than spelled out per drill, so the two drawer entries for
 * one drill cannot drift into disagreeing about what that drill is called.
 */
fun DrillKind.statsName(): String = "${displayName()} Stats"

/** Passes the stored geometry through unvalidated, for the same reason as [itemCount]. */
fun DrillKind.geometry(settings: Settings): DrillGeometry = when (this) {
	DrillKind.NUMBERS -> settings.numbers.let { DrillGeometry(it.columns, it.cellWidthDp, it.cellHeightDp) }
	DrillKind.POKER -> settings.poker.let { DrillGeometry(it.columns, it.cellWidthDp, it.cellHeightDp) }
}

/**
 * Where this drill's runs are stored - one file per drill rather than one shared file with a
 * kind column, so a Numbers run can never be listed by, or reopened as, a Poker one.
 *
 * Resolved from a supplied [paths] rather than from AnkiPaths.production(), which is what
 * makes test mode's redirected root apply here for free.
 */
fun DrillKind.runsFile(paths: AnkiPaths): File = when (this) {
	DrillKind.NUMBERS -> paths.numbersRuns
	DrillKind.POKER -> paths.pokerRuns
}
