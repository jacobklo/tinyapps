package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DEFAULT_HIGHLIGHT_DARK
import net.jacoblo.simpleanki.data.DEFAULT_HIGHLIGHT_LIGHT
import net.jacoblo.simpleanki.data.FieldResult
import net.jacoblo.simpleanki.data.NumbersSettings
import net.jacoblo.simpleanki.data.PokerSettings
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.highlightColor
import net.jacoblo.simpleanki.data.parseCellSizeDp
import net.jacoblo.simpleanki.data.parseColumnCount
import net.jacoblo.simpleanki.data.parseDefaultLimit
import net.jacoblo.simpleanki.data.parseHexColor
import net.jacoblo.simpleanki.data.parseHighlightEvery
import net.jacoblo.simpleanki.data.parseIntervalSeconds
import net.jacoblo.simpleanki.data.parseItemCount
import net.jacoblo.simpleanki.data.parseWindowSize
import net.jacoblo.simpleanki.data.soundPathOrNull
import net.jacoblo.simpleanki.drill.MAX_DRILL_ITEMS
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the settings screen's pure half: what each field accepts, what it refuses, and
 * how a stored colour reaches the table page.
 *
 * The composable itself is not tested here. It collects text and shows refusals and
 * decides nothing, so everything worth pinning is in data/SettingsOps.kt - which is why
 * that file exists.
 */
class SettingsScreenTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	// -- parseIntervalSeconds -----------------------------------------------------------

	@Test
	fun anIntervalAcceptsAPositiveDecimal() {
		assertEquals(4.5f, ok(parseIntervalSeconds("4.5")))
		// A stray space is tolerated. Not by anything in parseIntervalSeconds - it is
		// toFloatOrNull that allows it - so this line pins behaviour rather than guarding
		// a branch, and no mutation of our own code can make it fail.
		assertEquals(0.25f, ok(parseIntervalSeconds("  0.25  ")))
	}

	/**
	 * Zero and negatives are the whole reason this parser exists.
	 *
	 * MetronomeEffect already refuses to start a countdown of zero, so accepting one here
	 * would store a setting whose only visible effect is a metronome that does nothing at
	 * all, with nothing anywhere saying why.
	 */
	@Test
	fun anIntervalRefusesZeroAndNegatives() {
		assertTrue(parseIntervalSeconds("0") is FieldResult.Err)
		assertTrue(parseIntervalSeconds("0.0") is FieldResult.Err)
		assertTrue(parseIntervalSeconds("-1") is FieldResult.Err)
	}

	@Test
	fun anIntervalRefusesTextThatIsNotANumber() {
		assertTrue(parseIntervalSeconds("") is FieldResult.Err)
		assertTrue(parseIntervalSeconds("ten") is FieldResult.Err)
	}

	/**
	 * Both parse as Floats and both are positive by every test above, and neither is a
	 * countdown that ever fires. Only the isFinite check refuses them.
	 */
	@Test
	fun anIntervalRefusesNaNAndInfinity() {
		assertTrue(parseIntervalSeconds("NaN") is FieldResult.Err)
		assertTrue(parseIntervalSeconds("Infinity") is FieldResult.Err)
	}

	// -- the three whole-number fields ---------------------------------------------------

	/**
	 * Zero disables banding, so it has to be accepted - and it is the one value a shared
	 * "must be positive" rule would have refused.
	 */
	@Test
	fun highlightEveryAcceptsZeroAndRefusesNegatives() {
		assertEquals(0, ok(parseHighlightEvery("0")))
		assertEquals(5, ok(parseHighlightEvery("5")))
		assertTrue(parseHighlightEvery("-1") is FieldResult.Err)
	}

	/** Zero is not a window, and ViewOps.sized would default it away rather than store it. */
	@Test
	fun aWindowSizeRefusesZero() {
		assertEquals(1, ok(parseWindowSize("1")))
		assertEquals(100, ok(parseWindowSize("100")))
		assertTrue(parseWindowSize("0") is FieldResult.Err)
	}

	/** Zero is the struct's own spelling of "every attempt in the group". */
	@Test
	fun aDefaultLimitAcceptsZeroAndRefusesNegatives() {
		assertEquals(0, ok(parseDefaultLimit("0")))
		assertTrue(parseDefaultLimit("-2") is FieldResult.Err)
	}

	/** A field being retyped passes through empty, and a decimal is not a row count. */
	@Test
	fun aWholeNumberFieldRefusesEmptyAndDecimalText() {
		assertTrue(parseHighlightEvery("") is FieldResult.Err)
		assertTrue(parseHighlightEvery("2.5") is FieldResult.Err)
	}

	/**
	 * Unlike the interval field, this one has to trim for itself: toIntOrNull refuses a
	 * string with a space in it where toFloatOrNull accepts one.
	 */
	@Test
	fun aWholeNumberFieldTrimsSurroundingSpace() {
		assertEquals(5, ok(parseHighlightEvery(" 5 ")))
	}

	// -- the drill grid fields -----------------------------------------------------------

	/**
	 * Both ends, because a bound tested from one side only is half a rule: a ceiling nobody
	 * pushes against is indistinguishable from no ceiling at all.
	 *
	 * The ceiling is spelled [MAX_DRILL_ITEMS] on both sides of the test rather than as the
	 * number it currently is. That is not a tautology - it is the only assertion that fails if
	 * the validator ever stops sharing DrillScreen's cap and starts enforcing a figure of its
	 * own, which is the exact drift the shared constant exists to prevent.
	 */
	@Test
	fun anItemCountAcceptsOneToTheDrillCapAndRefusesEitherSideOfIt() {
		assertEquals(1, ok(parseItemCount("1")))
		assertEquals(50, ok(parseItemCount("50")))
		assertEquals(MAX_DRILL_ITEMS, ok(parseItemCount(MAX_DRILL_ITEMS.toString())))

		// Zero generates nothing, so the drill would open on an empty grid saying nothing.
		assertTrue(parseItemCount("0") is FieldResult.Err)
		assertTrue(parseItemCount("-1") is FieldResult.Err)
		// One past the cap: were it accepted, DrillScreen would quietly draw a smaller set.
		assertTrue(parseItemCount((MAX_DRILL_ITEMS + 1).toString()) is FieldResult.Err)
	}

	/**
	 * Zero is the end that matters. DrillGrid clamps it to one column because chunked(0)
	 * throws, so accepting it here would store a number the grid openly disagrees with.
	 */
	@Test
	fun aColumnCountAcceptsOneToTwentyAndRefusesEitherSideOfIt() {
		assertEquals(1, ok(parseColumnCount("1")))
		assertEquals(20, ok(parseColumnCount("20")))

		assertTrue(parseColumnCount("0") is FieldResult.Err)
		assertTrue(parseColumnCount("-1") is FieldResult.Err)
		assertTrue(parseColumnCount("21") is FieldResult.Err)
	}

	/** A cell too small to see and one that fills the screen are both grids nobody can use. */
	@Test
	fun aCellSizeAcceptsSixteenToTwoHundredAndRefusesEitherSideOfIt() {
		assertEquals(16, ok(parseCellSizeDp("16")))
		assertEquals(200, ok(parseCellSizeDp("200")))

		assertTrue(parseCellSizeDp("15") is FieldResult.Err)
		assertTrue(parseCellSizeDp("0") is FieldResult.Err)
		assertTrue(parseCellSizeDp("201") is FieldResult.Err)
	}

	/**
	 * Empty is what every one of these fields passes through while it is being retyped, and a
	 * bound test alone would not notice a parser that let text past: "" and "five" are neither
	 * below a floor nor above a ceiling.
	 */
	@Test
	fun aDrillGridFieldRefusesTextThatIsNotAWholeNumber() {
		assertTrue(parseItemCount("") is FieldResult.Err)
		assertTrue(parseItemCount("fifty") is FieldResult.Err)
		assertTrue(parseColumnCount("five") is FieldResult.Err)
		assertTrue(parseCellSizeDp("64.5") is FieldResult.Err)
		// Trimmed like the other whole-number fields: toIntOrNull refuses a stray space.
		assertEquals(64, ok(parseCellSizeDp(" 64 ")))
	}

	/**
	 * The refusal names the bound AND the reason for it. There is no save button on this screen,
	 * so the inline message is the only place either can be stated at all - and a value silently
	 * clamped instead would leave the file disagreeing with the field the user is looking at.
	 *
	 * Both halves are asserted because the second is the one nothing else pins: the range is
	 * built from the range object and would survive most edits, where the reason clause is a
	 * string that can be dropped from parseInRange without a single other test noticing.
	 */
	@Test
	fun aRefusedDrillFieldSaysTheBoundAndWhyItIsThere() {
		val message = (parseColumnCount("40") as FieldResult.Err).message

		assertTrue(message, message.contains("from 1 to 20"))
		assertTrue(message, message.contains("phone"))
	}

	/**
	 * The shipped geometry has to survive its own validators, or the settings screen opens with
	 * drill fields already red on values the user never typed.
	 */
	@Test
	fun theShippedDrillGeometryIsItselfValid() {
		val numbers = NumbersSettings()
		assertEquals(numbers.count, ok(parseItemCount(numbers.count.toString())))
		assertEquals(numbers.columns, ok(parseColumnCount(numbers.columns.toString())))
		assertEquals(numbers.cellWidthDp, ok(parseCellSizeDp(numbers.cellWidthDp.toString())))
		assertEquals(numbers.cellHeightDp, ok(parseCellSizeDp(numbers.cellHeightDp.toString())))

		val poker = PokerSettings()
		assertEquals(poker.columns, ok(parseColumnCount(poker.columns.toString())))
		assertEquals(poker.cellWidthDp, ok(parseCellSizeDp(poker.cellWidthDp.toString())))
		assertEquals(poker.cellHeightDp, ok(parseCellSizeDp(poker.cellHeightDp.toString())))
	}

	// -- parseHexColor --------------------------------------------------------------------

	@Test
	fun aColourAcceptsSixHexDigitsInEitherCase() {
		assertEquals("#DAD5E4", ok(parseHexColor("#DAD5E4")))
		// Kept as typed rather than normalised: CSS does not care, and rewriting the text
		// under a user who is still typing it does.
		assertEquals("#dad5e4", ok(parseHexColor("#dad5e4")))
		assertEquals("#3B3546", ok(parseHexColor("  #3B3546  ")))
	}

	/**
	 * Shorthand and an alpha channel are the two near-misses worth naming.
	 *
	 * Both are colours a browser would accept, so neither would look wrong until it
	 * reached the page: "#ABC" is a tint nobody chose, and "#80DAD5E4" would blend with
	 * whatever is behind the row and make the text contrast depend on the theme.
	 */
	@Test
	fun aColourRefusesShorthandAndAlpha() {
		assertTrue(parseHexColor("#ABC") is FieldResult.Err)
		assertTrue(parseHexColor("#80DAD5E4") is FieldResult.Err)
	}

	@Test
	fun aColourRefusesAMissingHashNonHexDigitsAndNames() {
		assertTrue(parseHexColor("DAD5E4") is FieldResult.Err)
		assertTrue(parseHexColor("#GGGGGG") is FieldResult.Err)
		assertTrue(parseHexColor("red") is FieldResult.Err)
		assertTrue(parseHexColor("") is FieldResult.Err)
	}

	/**
	 * A whole-string match, not a substring one: a stray character either side is not a
	 * colour. This is what makes Regex.matches load-bearing - swapped for containsMatchIn,
	 * both of these become valid and "#DAD5E4;" reaches the page as a CSS value.
	 */
	@Test
	fun aColourRefusesTextAroundAValidOne() {
		assertTrue(parseHexColor("##DAD5E4") is FieldResult.Err)
		assertTrue(parseHexColor("#DAD5E4;") is FieldResult.Err)
	}

	// -- soundPathOrNull -------------------------------------------------------------------

	/**
	 * Blank has to become null rather than "". SoundPoolClickPlayer treats an empty string
	 * as a path it could not read and Toasts about it, so a user who merely cleared the
	 * field would be told off at every launch.
	 */
	@Test
	fun aBlankSoundPathIsNull() {
		assertNull(soundPathOrNull(""))
		assertNull(soundPathOrNull("   "))
		assertEquals("/sdcard/tick.wav", soundPathOrNull("  /sdcard/tick.wav  "))
	}

	// -- highlightColor ---------------------------------------------------------------------

	@Test
	fun theResolvedTintFollowsTheTheme() {
		val settings = TableSettings(highlightColorLight = "#AABBCC", highlightColorDark = "#112233")

		assertEquals("#AABBCC", settings.highlightColor(darkTheme = false))
		assertEquals("#112233", settings.highlightColor(darkTheme = true))
	}

	/**
	 * settings.json is hand-editable, so a value the field would have refused can still be
	 * stored - and a CSS declaration a browser rejects leaves `--highlight` unset, which
	 * on screen is indistinguishable from banding being switched off.
	 *
	 * Each theme falls back to its OWN default. Falling back to a single one would band a
	 * dark table in a light-theme tint.
	 */
	@Test
	fun aMalformedStoredTintFallsBackToItsOwnDefault() {
		val broken = TableSettings(highlightColorLight = "chartreuse", highlightColorDark = "#xyz")

		assertEquals(DEFAULT_HIGHLIGHT_LIGHT, broken.highlightColor(darkTheme = false))
		assertEquals(DEFAULT_HIGHLIGHT_DARK, broken.highlightColor(darkTheme = true))
	}

	/** The defaults have to survive their own validator, or every table bands on a fallback. */
	@Test
	fun theShippedDefaultsAreThemselvesValidColours() {
		assertEquals(DEFAULT_HIGHLIGHT_LIGHT, ok(parseHexColor(DEFAULT_HIGHLIGHT_LIGHT)))
		assertEquals(DEFAULT_HIGHLIGHT_DARK, ok(parseHexColor(DEFAULT_HIGHLIGHT_DARK)))
		assertEquals(DEFAULT_HIGHLIGHT_LIGHT, TableSettings().highlightColor(darkTheme = false))
		assertEquals(DEFAULT_HIGHLIGHT_DARK, TableSettings().highlightColor(darkTheme = true))
	}

	// -- storage ----------------------------------------------------------------------------

	@Test
	fun theTintsRoundTripThroughSettingsJson() {
		val paths = AnkiPaths.at(tempFolder.root)
		val repository = SettingsRepository(paths)
		val settings = Settings(
			table = TableSettings(highlightColorLight = "#123456", highlightColorDark = "#654321")
		)

		repository.save(settings)

		assertEquals(settings, repository.load())
	}

	/**
	 * The two new keys, spelled out, for the reason SettingsTest spells out the rest: the
	 * round trip above shares one KEY_* constant between reader and writer, so a rename
	 * passes it while resetting the field on every user's disk.
	 */
	@Test
	fun theTintKeysAreTheContractWithFilesOnTheUsersDisk() {
		val paths = AnkiPaths.at(tempFolder.root)
		SettingsRepository(paths).save(
			Settings(
				table = TableSettings(highlightColorLight = "#123456", highlightColorDark = "#654321")
			)
		)

		val table = JSONObject(paths.settings.readText()).getJSONObject("table")
		assertEquals("#123456", table.getString("highlightColorLight"))
		assertEquals("#654321", table.getString("highlightColorDark"))
	}

	/**
	 * A hand-edited typo is stored and returned verbatim rather than repaired on the way
	 * in. The settings screen shows what is in the file, so a value silently swapped for a
	 * default here would leave the user looking at a colour they never typed with no way
	 * to tell that theirs had been discarded.
	 */
	@Test
	fun aMalformedStoredTintSurvivesALoadSoItCanBeSeenAndFixed() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.settings.writeText("{\"table\":{\"highlightColorLight\":\"not a colour\"}}")

		val loaded = SettingsRepository(paths).load()

		assertEquals("not a colour", loaded.table.highlightColorLight)
		// Stored, but never handed to the page.
		assertEquals(DEFAULT_HIGHLIGHT_LIGHT, loaded.table.highlightColor(darkTheme = false))
	}

	/** A settings.json written before this build has no tint keys and must take the defaults. */
	@Test
	fun aFileWithoutTheTintKeysTakesTheDefaults() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.settings.writeText("{\"table\":{\"highlightEvery\":3}}")

		val loaded = SettingsRepository(paths).load()

		assertEquals(3, loaded.table.highlightEvery)
		assertEquals(DEFAULT_HIGHLIGHT_LIGHT, loaded.table.highlightColorLight)
		assertEquals(DEFAULT_HIGHLIGHT_DARK, loaded.table.highlightColorDark)
	}

	/** The value out of a [FieldResult], failing the test when it was refused instead. */
	private fun <T> ok(result: FieldResult<T>): T {
		assertTrue(result.toString(), result is FieldResult.Ok)
		return (result as FieldResult.Ok).value
	}
}
