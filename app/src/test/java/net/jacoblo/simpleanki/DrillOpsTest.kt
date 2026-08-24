package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.ItemStatus
import net.jacoblo.simpleanki.data.NumbersSettings
import net.jacoblo.simpleanki.data.PokerSettings
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.drill.DrillGeometry
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.DrillOps
import net.jacoblo.simpleanki.drill.displayName
import net.jacoblo.simpleanki.drill.geometry
import net.jacoblo.simpleanki.drill.itemCount
import net.jacoblo.simpleanki.drill.runsFile
import net.jacoblo.simpleanki.drill.statsName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Covers the drill core: set generation, the tap cycle, cell visibility, the accuracy figure,
 * and the per-drill descriptor.
 *
 * Two of these rules are easy to write a test that passes for the wrong reason, so both are
 * pinned against the wrong answer rather than merely against the right one. The accuracy
 * fixture is chosen so right/count and right/(right + wrong) disagree - 3 right and 1 wrong
 * out of 10 items reads 30% one way and 75% the other - and the cycle is asserted on the mark
 * AND on what the cell shows at every step, because a cycle test that only checks the mark
 * passes for an implementation that never re-covers a cell.
 *
 * Expected ranks and suits are spelled out here rather than read back from DrillOps.RANKS and
 * DrillOps.SUITS: an expectation built out of the value under test cannot fail. The suit
 * glyphs use \uXXXX escapes for the same reason DrillOps does, and every red-suit assertion
 * names a glyph rather than an index. Because SUITS interleaves its red suits, that is enough
 * to fail a rule keyed on a position in that list - see the note on DrillOps.SUITS.
 *
 * Where a seeded sample stands in for an exhaustive one it is sized so the property is
 * certain rather than merely likely: 2000 draws leave one of the 100 values unseen with
 * probability around 2e-7, and 50 draws avoid every collision with probability around 3e-7.
 */
class DrillOpsTest {

	private val ranks = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
	// Same order as DrillOps.SUITS, red suits interleaved - see the note there.
	private val suits = listOf(SPADE, HEART, CLUB, DIAMOND)
	private val everyCard: Set<String> = ranks.flatMap { rank -> suits.map { rank + it } }.toSet()

	// ---------------------------------------------------------------------------
	// Numbers generation
	// ---------------------------------------------------------------------------

	@Test
	fun numbersAreDrawnWithReplacementSoARepeatSurvives() {
		val values = DrillOps.generateNumbers(50, Random(20260824)).map { it.value }
		assertEquals(50, values.size)
		assertTrue("expected a repeat in $values", values.toSet().size < values.size)
	}

	@Test
	fun everyNumberIsTwoCharactersDrawnFromZeroThroughNinetyNine() {
		val values = DrillOps.generateNumbers(2000, Random(11)).map { it.value }
		val expected = (0..99).map { if (it < 10) "0$it" else it.toString() }.toSet()
		// A sample this size turns up the whole range, so one comparison pins the bounds - no
		// 100, no negative - and the padding of every single-digit draw at once: drop the pad
		// and "7" is in the observed set where "07" is expected.
		assertEquals(expected, values.toSet())
	}

	@Test
	fun aFreshSetIsEntirelyUnscored() {
		assertTrue(DrillOps.generateNumbers(20, Random(3)).all { it.status == ItemStatus.UNSCORED })
		assertTrue(DrillOps.generateDeck(Random(3)).all { it.status == ItemStatus.UNSCORED })
	}

	@Test
	fun theSameSeedDrawsTheSameNumbersAndADifferentSeedDoesNot() {
		assertEquals(DrillOps.generateNumbers(30, Random(99)), DrillOps.generateNumbers(30, Random(99)))
		assertNotEquals(DrillOps.generateNumbers(30, Random(99)), DrillOps.generateNumbers(30, Random(100)))
	}

	@Test
	fun aNonPositiveCountDrawsNothingRatherThanThrowing() {
		// A hand-edited settings.json can hold either, and refusing one is the settings field's
		// job. All this has to do is not crash on the way past.
		assertEquals(emptyList<DrillItem>(), DrillOps.generateNumbers(0, Random(1)))
		assertEquals(emptyList<DrillItem>(), DrillOps.generateNumbers(-5, Random(1)))
	}

	// ---------------------------------------------------------------------------
	// Deck generation
	// ---------------------------------------------------------------------------

	@Test
	fun theDeckHoldsEveryRankAndSuitExactlyOnce() {
		val deck = DrillOps.generateDeck(Random(42)).map { it.value }
		// Guards this test's own fixture first: a duplicated rank in the list above would
		// quietly shrink the expected set and make the real assertion easier to pass.
		assertEquals(52, everyCard.size)
		assertEquals(DrillOps.DECK_SIZE, deck.size)
		// The set, not just the size. A shuffle that duplicated one card and dropped another
		// is still 52 items long and would sail past a size check.
		assertEquals(everyCard, deck.toSet())
	}

	@Test
	fun aCardIsItsRankFollowedByItsSuitGlyph() {
		val deck = DrillOps.generateDeck(Random(42)).map { it.value }
		assertTrue("missing A" + SPADE, deck.contains("A" + SPADE))
		// The two-character rank, for the reason DrillOps.RANKS gives.
		assertTrue("missing 10" + DIAMOND, deck.contains("10" + DIAMOND))
		assertTrue("missing K" + CLUB, deck.contains("K" + CLUB))
		// Suit first is the same 52 cards under a different spelling, but it puts the glyph
		// where isRedSuit no longer looks, so every card would render black.
		assertFalse("suit came first: " + SPADE + "A", deck.contains(SPADE + "A"))
	}

	@Test
	fun theDeckIsShuffledRatherThanLeftInBuildOrder() {
		val deck = DrillOps.generateDeck(Random(42)).map { it.value }
		assertNotEquals(suits.flatMap { suit -> ranks.map { it + suit } }, deck)
		assertNotEquals(ranks.flatMap { rank -> suits.map { rank + it } }, deck)
	}

	@Test
	fun theSameSeedDealsTheSameDeckAndADifferentSeedDoesNot() {
		assertEquals(DrillOps.generateDeck(Random(7)), DrillOps.generateDeck(Random(7)))
		assertNotEquals(DrillOps.generateDeck(Random(7)), DrillOps.generateDeck(Random(8)))
	}

	// ---------------------------------------------------------------------------
	// Suit colour
	// ---------------------------------------------------------------------------

	@Test
	fun theSuitGlyphsAreTheFourSolidVariants() {
		// Nothing but this assertion keeps the hollow heart and diamond, \u2661 and \u2662,
		// out of the deck; they are legal glyphs that render as outlines.
		assertEquals(suits, DrillOps.SUITS)
		assertEquals(ranks, DrillOps.RANKS)
	}

	@Test
	fun heartsAndDiamondsAreRedAndTheOtherTwoSuitsAreNot() {
		for (rank in ranks) {
			assertTrue("$rank hearts", DrillOps.isRedSuit(rank + HEART))
			assertTrue("$rank diamonds", DrillOps.isRedSuit(rank + DIAMOND))
			assertFalse("$rank spades", DrillOps.isRedSuit(rank + SPADE))
			assertFalse("$rank clubs", DrillOps.isRedSuit(rank + CLUB))
		}
	}

	@Test
	fun aNumbersValueIsNeverRed() {
		// The one grid composable colours both drills, so it asks this of a "07" as readily as
		// of an ace of hearts.
		assertFalse(DrillOps.isRedSuit("07"))
		assertFalse(DrillOps.isRedSuit("42"))
		assertFalse(DrillOps.isRedSuit(""))
	}

	// ---------------------------------------------------------------------------
	// The tap cycle
	// ---------------------------------------------------------------------------

	@Test
	fun theFirstTapMarksWrongNotRight() {
		assertEquals(ItemStatus.WRONG, DrillOps.next(ItemStatus.UNSCORED))
		assertEquals(ItemStatus.RIGHT, DrillOps.next(ItemStatus.WRONG))
		assertEquals(ItemStatus.UNSCORED, DrillOps.next(ItemStatus.RIGHT))
	}

	@Test
	fun tappingRoundTheCycleRevealsThenMarksThenCoversAgain() {
		var items = listOf(DrillItem("07"))
		assertCell(items, ItemStatus.UNSCORED, revealed = false)
		items = DrillOps.cycle(items, 0)
		assertCell(items, ItemStatus.WRONG, revealed = true)
		items = DrillOps.cycle(items, 0)
		assertCell(items, ItemStatus.RIGHT, revealed = true)
		items = DrillOps.cycle(items, 0)
		assertCell(items, ItemStatus.UNSCORED, revealed = false)
	}

	@Test
	fun cyclingOneCellLeavesEveryOtherCellAlone() {
		val items = listOf(
			DrillItem("07"),
			DrillItem("42", ItemStatus.RIGHT),
			DrillItem("91", ItemStatus.WRONG)
		)
		val cycled = DrillOps.cycle(items, 1)
		assertEquals(items[0], cycled[0])
		assertEquals(items[2], cycled[2])
		assertEquals(DrillItem("42", ItemStatus.UNSCORED), cycled[1])
	}

	@Test
	fun cycleHandsBackANewListAndLeavesItsArgumentUntouched() {
		val items = listOf(DrillItem("07"), DrillItem("42"))
		val cycled = DrillOps.cycle(items, 0)
		// A fresh instance, for the reason DrillOps.cycle gives - see the note there.
		assertNotSame(items, cycled)
		assertEquals(listOf(DrillItem("07"), DrillItem("42")), items)
		assertNotEquals(items, cycled)
	}

	@Test
	fun anIndexOutsideTheGridChangesNothing() {
		val items = listOf(DrillItem("07"), DrillItem("42"))
		assertEquals(items, DrillOps.cycle(items, -1))
		assertEquals(items, DrillOps.cycle(items, 2))
		assertEquals(emptyList<DrillItem>(), DrillOps.cycle(emptyList(), 0))
	}

	// ---------------------------------------------------------------------------
	// Visibility
	// ---------------------------------------------------------------------------

	@Test
	fun outsideScoringEveryValueShowsWhateverItsMark() {
		for (status in ItemStatus.entries) {
			assertTrue(status.name, DrillOps.isRevealed(status, scoring = false))
		}
	}

	@Test
	fun whileScoringOnlyAMarkedValueShows() {
		assertFalse(DrillOps.isRevealed(ItemStatus.UNSCORED, scoring = true))
		assertTrue(DrillOps.isRevealed(ItemStatus.WRONG, scoring = true))
		assertTrue(DrillOps.isRevealed(ItemStatus.RIGHT, scoring = true))
	}

	// ---------------------------------------------------------------------------
	// Accuracy and rate
	// ---------------------------------------------------------------------------

	@Test
	fun anUncheckedItemCountsAgainstTheUserExactlyAsAMissedOneDoes() {
		val run = run(right = 3, wrong = 1, unscored = 6, seconds = 60f)
		assertEquals(10, run.count)
		assertEquals(3, run.right)
		assertEquals(1, run.wrong)
		// 3/10, not 3/4. The fixture exists to make the two definitions disagree: dividing by
		// the checked items instead reads 75% here, and would score a run with one cell marked
		// right and the rest never looked at as a perfect one.
		assertEquals(0.30f, run.accuracy!!, TOLERANCE)
	}

	@Test
	fun anUnscoredRunAndAFullyMissedOneBothReadZeroPercentAndStayTellableApart() {
		// Done writes the run before any scoring happens, so the unscored row is expected in the
		// stats table rather than a bug. The two are told apart by right + wrong, which is zero
		// only for the run nobody checked.
		val unscored = run(right = 0, wrong = 0, unscored = 10, seconds = 30f)
		val missed = run(right = 0, wrong = 10, unscored = 0, seconds = 30f)
		assertEquals(0f, unscored.accuracy!!, TOLERANCE)
		assertEquals(0f, missed.accuracy!!, TOLERANCE)
		assertEquals(0, unscored.right + unscored.wrong)
		assertEquals(missed.count, missed.wrong)
	}

	@Test
	fun secondsPerItemSpreadsTheRunOverItsItems() {
		assertEquals(1.5f, run(right = 3, wrong = 1, unscored = 6, seconds = 15f).secondsPerItem!!, TOLERANCE)
	}

	@Test
	fun anEmptyRunHasNeitherAnAccuracyNorARate() {
		// Only a hand-edited file gets here, and both figures have to be null rather than zero:
		// 0% is a real score and 0 seconds per item is a real rate, so either would put a
		// fabricated row in the stats table instead of a dash.
		val empty = DrillRun(id = "1", startedAt = 1L, seconds = 0f, items = emptyList())
		assertEquals(0, empty.count)
		assertNull(empty.accuracy)
		assertNull(empty.secondsPerItem)
	}

	// ---------------------------------------------------------------------------
	// The per-drill descriptor
	// ---------------------------------------------------------------------------

	@Test
	fun numbersTakesItsCountFromSettingsAndPokerIsAlwaysAFullDeck() {
		val settings = Settings(numbers = NumbersSettings(count = 30))
		assertEquals(30, DrillKind.NUMBERS.itemCount(settings))
		assertEquals(52, DrillKind.POKER.itemCount(settings))
		assertEquals(52, DrillKind.POKER.itemCount(Settings()))
	}

	@Test
	fun aHandEditedCountOrColumnCountReachesTheGridUnchanged() {
		// Passed through unvalidated on purpose - see the note on DrillKind.itemCount.
		assertEquals(0, DrillKind.NUMBERS.itemCount(Settings(numbers = NumbersSettings(count = 0))))
		assertEquals(-4, DrillKind.NUMBERS.itemCount(Settings(numbers = NumbersSettings(count = -4))))
		val columns = DrillKind.NUMBERS.geometry(Settings(numbers = NumbersSettings(columns = 0))).columns
		assertEquals(0, columns)
	}

	@Test
	fun eachDrillReadsItsOwnGeometrySection() {
		val settings = Settings(
			numbers = NumbersSettings(columns = 5, cellWidthDp = 64, cellHeightDp = 56),
			poker = PokerSettings(columns = 6, cellWidthDp = 56, cellHeightDp = 48)
		)
		assertEquals(DrillGeometry(5, 64, 56), DrillKind.NUMBERS.geometry(settings))
		assertEquals(DrillGeometry(6, 56, 48), DrillKind.POKER.geometry(settings))
	}

	@Test
	fun eachDrillNamesItsOwnScreens() {
		assertEquals("Numbers", DrillKind.NUMBERS.displayName())
		assertEquals("Poker", DrillKind.POKER.displayName())
		assertEquals("Numbers Stats", DrillKind.NUMBERS.statsName())
		assertEquals("Poker Stats", DrillKind.POKER.statsName())
	}

	@Test
	fun eachDrillStoresItsRunsInItsOwnFile() {
		val paths = AnkiPaths.at(File("root"))
		assertEquals(paths.numbersRuns, DrillKind.NUMBERS.runsFile(paths))
		assertEquals(paths.pokerRuns, DrillKind.POKER.runsFile(paths))
		// Swapping the two would have each drill listing and re-scoring the other's runs.
		assertNotEquals(paths.numbersRuns, paths.pokerRuns)
	}

	@Test
	fun generateDispatchesToTheDrillsOwnGenerator() {
		assertEquals(
			DrillOps.generateNumbers(5, Random(1)),
			DrillOps.generate(DrillKind.NUMBERS, 5, Random(1))
		)
		// Poker ignores the count outright: a short deck is not the discipline being trained.
		assertEquals(DrillOps.generateDeck(Random(1)), DrillOps.generate(DrillKind.POKER, 5, Random(1)))
		assertEquals(52, DrillOps.generate(DrillKind.POKER, 5, Random(1)).size)
	}

	// ---------------------------------------------------------------------------
	// Fixtures
	// ---------------------------------------------------------------------------

	/** Asserts the mark and what the cell shows together, since the cycle decides both. */
	private fun assertCell(items: List<DrillItem>, expected: ItemStatus, revealed: Boolean) {
		assertEquals("mark", expected, items[0].status)
		assertEquals("revealed", revealed, DrillOps.isRevealed(items[0].status, scoring = true))
	}

	private fun run(right: Int, wrong: Int, unscored: Int, seconds: Float): DrillRun {
		val items = List(right) { DrillItem("07", ItemStatus.RIGHT) } +
			List(wrong) { DrillItem("42", ItemStatus.WRONG) } +
			List(unscored) { DrillItem("91", ItemStatus.UNSCORED) }
		return DrillRun(id = "1", startedAt = 1L, seconds = seconds, items = items)
	}

	private companion object {
		const val TOLERANCE = 0.0001f

		const val SPADE = "\u2660"
		const val HEART = "\u2665"
		const val DIAMOND = "\u2666"
		const val CLUB = "\u2663"
	}
}
