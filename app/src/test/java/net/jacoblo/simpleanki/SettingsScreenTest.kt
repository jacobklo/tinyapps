package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DEFAULT_HIGHLIGHT_DARK
import net.jacoblo.simpleanki.data.DEFAULT_HIGHLIGHT_LIGHT
import net.jacoblo.simpleanki.data.FieldResult
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.highlightColor
import net.jacoblo.simpleanki.data.parseDefaultLimit
import net.jacoblo.simpleanki.data.parseHexColor
import net.jacoblo.simpleanki.data.parseHighlightEvery
import net.jacoblo.simpleanki.data.parseIntervalSeconds
import net.jacoblo.simpleanki.data.parseWindowSize
import net.jacoblo.simpleanki.data.soundPathOrNull
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
