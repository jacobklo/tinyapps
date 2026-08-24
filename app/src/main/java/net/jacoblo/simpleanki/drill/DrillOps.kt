/*
 * The correctness core of both drills: what a set is made of, what a tap does to a cell, and
 * whether that cell shows its value.
 *
 * Pure functions over pure data. No clock, no filesystem, no Android import, and no
 * randomness that is not injected, so every rule this feature can get wrong is decided here
 * and asserted by a JVM test rather than by squinting at a screen. Anything that needs a
 * test belongs in this file; the drill composables are meant to hold no rule of their own.
 */
package net.jacoblo.simpleanki.drill

import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.ItemStatus
import java.util.Locale
import kotlin.random.Random

object DrillOps {

	/** One full deck: Poker's item count, and the size [generateDeck] must always return. */
	const val DECK_SIZE = 52

	/** Exclusive upper bound of a Numbers draw, so the two digits are 00 through 99. */
	private const val NUMBER_CEILING = 100

	private const val SPADE = "\u2660"
	private const val HEART = "\u2665"
	private const val DIAMOND = "\u2666"
	private const val CLUB = "\u2663"

	/**
	 * "10" is the only two-character rank, so nothing may take a card's rank by a fixed-width
	 * slice of its value.
	 */
	val RANKS: List<String> =
		listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

	/**
	 * The four suit glyphs.
	 *
	 * Written as \uXXXX escapes so the source file stays pure ASCII while the glyph itself
	 * reaches the screen. A string resource would deliver the same glyph but would drag an
	 * Android import into this object and cost it its JVM tests.
	 *
	 * The SOLID variants deliberately, not the hollow heart and diamond at \u2661 and \u2662:
	 * those render as outlines, and an outline heart beside a solid spade reads as two decks
	 * shuffled together rather than as one.
	 *
	 * Deliberately NOT the conventional bridge order, which runs spade, heart, diamond, club
	 * and so seats the two red suits side by side. Interleaving them is what gives the tests a
	 * grip on [isRedSuit]: with the red suits contiguous, a rule keyed on a position in this
	 * list answers identically to one keyed on the glyph for every input there is, and no test
	 * can tell the two apart. Split them and the position-keyed version paints clubs red on
	 * its first run.
	 *
	 * Past that the order decides only the order [generateDeck] builds in, which its shuffle
	 * then destroys.
	 */
	val SUITS: List<String> = listOf(SPADE, HEART, CLUB, DIAMOND)

	private val RED_SUITS = setOf(HEART, DIAMOND)

	/**
	 * Whether [value] is a card of a red suit, which the grid renders in red so that suit is
	 * carried by colour as well as by shape.
	 *
	 * Keyed on the glyph the value ends with, never on a position in [SUITS] - and that list
	 * interleaves its red suits precisely so the difference between the two is something a
	 * test can see rather than something a comment has to be trusted about.
	 *
	 * A Numbers value ends in a digit and so is never red, which is what lets one grid
	 * composable colour both drills without being told which it is drawing.
	 */
	fun isRedSuit(value: String): Boolean = RED_SUITS.any { value.endsWith(it) }

	/**
	 * A fresh set for [kind].
	 *
	 * [count] is Numbers' alone; Poker returns all [DECK_SIZE] cards whatever it says.
	 * Callers pass DrillKind.itemCount(settings), which already answers 52 for Poker, so the
	 * argument and the result agree rather than the count being silently overruled.
	 */
	fun generate(kind: DrillKind, count: Int, random: Random): List<DrillItem> = when (kind) {
		DrillKind.NUMBERS -> generateNumbers(count, random)
		DrillKind.POKER -> generateDeck(random)
	}

	/**
	 * [count] draws from 00..99 WITH replacement, each zero-padded to two characters.
	 *
	 * With replacement on purpose: the drill trains recall of a random digit stream, and a
	 * stream that never repeats a pair is not one. Drawing without replacement would also
	 * make any count above 100 unsatisfiable.
	 *
	 * Padded because an unpadded "7" is both a narrower cell than "42", which makes a grid of
	 * equal cells ragged, and one digit to memorise where every other cell holds two.
	 *
	 * Built from a range rather than from List(count), which throws on a negative size. A
	 * hand-edited count reaches here unvalidated by design (see DrillKind.itemCount), and an
	 * empty grid the user can go and fix beats a crash on the way to it.
	 */
	fun generateNumbers(count: Int, random: Random): List<DrillItem> =
		(0 until count).map { DrillItem(random.nextInt(NUMBER_CEILING).toString().padStart(2, '0')) }

	/**
	 * All 52 rank/suit pairs, shuffled - never fewer, never a duplicate.
	 *
	 * Built whole and then shuffled once, rather than dealt card by card out of a shrinking
	 * pile. The pile version is the same deck written in a way that can drop or repeat a card
	 * if its removal is ever gotten wrong, and a set holding two aces of spades is one the
	 * user cannot score correctly no matter how well they memorised it.
	 */
	fun generateDeck(random: Random): List<DrillItem> =
		SUITS.flatMap { suit -> RANKS.map { rank -> DrillItem(rank + suit) } }.shuffled(random)

	/**
	 * The status one tap on from [status]: UNSCORED -> WRONG -> RIGHT -> UNSCORED.
	 *
	 * Not the conventional right-first order, and deliberately not. The first tap is the one
	 * that reveals the answer, and revealing it defaults to "I got this wrong" until the user
	 * says otherwise. Marking a long run of correct answers therefore costs two taps each;
	 * that is the accepted price of never recording a right the user did not claim.
	 *
	 * A `when` over the enum with no `else`, so a fourth status would fail to compile here
	 * rather than reach a cell that silently refuses to leave it.
	 */
	fun next(status: ItemStatus): ItemStatus = when (status) {
		ItemStatus.UNSCORED -> ItemStatus.WRONG
		ItemStatus.WRONG -> ItemStatus.RIGHT
		ItemStatus.RIGHT -> ItemStatus.UNSCORED
	}

	/**
	 * [items] with the item at [index] advanced one step, as a new list.
	 *
	 * A copy rather than an in-place edit because the caller keeps this list in Compose state,
	 * which diffs by equality: a mutated list is equal to the one already held, so the tap
	 * would change the model and leave the screen showing the old mark.
	 *
	 * An index outside the list returns [items] untouched rather than throwing, so no tap
	 * handler has to repeat a bounds check the grid has already made.
	 */
	fun cycle(items: List<DrillItem>, index: Int): List<DrillItem> {
		if (index !in items.indices) return items
		return items.mapIndexed { i, item ->
			if (i == index) item.copy(status = next(item.status)) else item
		}
	}

	/**
	 * Whether a cell's VALUE is visible.
	 *
	 * Outside scoring a mark hides nothing: whether the set is covered at all is the screen's
	 * own state - Fresh covers it, Start reveals it - one decision for the whole grid rather
	 * than one per cell. While scoring, only a marked cell shows, which is exactly what makes
	 * the first tap the reveal and the last tap of the cycle a re-cover.
	 *
	 * Derived from [status] rather than stored alongside it, so there is no second flag free
	 * to disagree with the mark: no revealed cell reading UNSCORED, and no hidden cell holding
	 * a RIGHT.
	 */
	fun isRevealed(status: ItemStatus, scoring: Boolean): Boolean =
		!scoring || status != ItemStatus.UNSCORED

	/**
	 * [seconds] as mm:ss, truncated to whole seconds the way a running stopwatch reads.
	 *
	 * Here rather than in either caller because both need it: the drill screen's live timer,
	 * and the stats table's Time column. Two copies would be two chances for the screen a run
	 * was timed on and the table that lists it to disagree about how long it took - and only
	 * one of the two would be the copy the tests happen to reach.
	 *
	 * The minutes are NOT wrapped at 60: a 3900 second run renders "65:00", not the "05:00"
	 * that a modulo would give it. A drill this long is a user who walked away mid-set rather
	 * than a real session, and "05:00" would hide that behind a figure that looks like a
	 * respectable time.
	 *
	 * A negative [seconds] - only a hand-edited file has one, since the drill's own clock
	 * cannot produce it - renders as "00:-5" and is deliberately not clamped, for the reason
	 * [DrillKind.itemCount] gives: clamping would leave the file saying one thing and the
	 * screen showing another, and a typo the user can see is one they can go and fix.
	 */
	fun minutesSeconds(seconds: Float): String {
		val whole = seconds.toInt()
		return "%02d:%02d".format(Locale.ROOT, whole / 60, whole % 60)
	}
}
