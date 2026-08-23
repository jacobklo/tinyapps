package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.data.ViewsFile
import net.jacoblo.simpleanki.data.ViewsRepository
import net.jacoblo.simpleanki.table.FormulaParser
import net.jacoblo.simpleanki.table.FormulaWriter
import net.jacoblo.simpleanki.table.ParseResult
import net.jacoblo.simpleanki.table.TableEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the formula grammar in both directions, plus the two places views.json depends on
 * it: which of the struct and the string wins on load, and what a failure leaves behind.
 *
 * The round-trip over every function crossed with every partition mode is the load-bearing
 * assertion here. The error table beside it is the grammar's documentation - every rule the
 * spec lists appears in it with the exact text a user will read.
 */
class FormulaTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val tableSettings = TableSettings()

	/** The very set ViewsRepository hands the parser, not a copy built the same way. */
	private val known: Set<String> = TableEngine.BASE_COLUMN_IDS

	// -- round trip -------------------------------------------------------------------------

	@Test
	fun everyFunctionCrossedWithEveryPartitionModeRoundTrips() {
		// Seconds is numeric, so it is legal for all eight functions including the six that
		// require a numeric source; a group with and without a limit are separate shapes.
		for (fn in Aggregate.entries) {
			for ((partition, limit) in partitionsUnderTest) {
				assertRoundTrip(ComputedSpec(fn, TableEngine.ID_SECONDS, partition, limit))
			}
		}
	}

	@Test
	fun theWildcardSourceRoundTripsForCount() {
		for ((partition, limit) in partitionsUnderTest) {
			assertRoundTrip(ComputedSpec(Aggregate.COUNT, FormulaParser.WILDCARD, partition, limit))
		}
	}

	@Test
	fun writeEmitsSourceThenPartitionThenLast() {
		// The fixed order is what makes the round trip a property rather than a coincidence,
		// and what stops a save churning the file with a reordered argument list.
		assertEquals(
			"=MIN(Seconds, group:Question, last:10)",
			FormulaWriter.write(spec(Aggregate.MIN, "Seconds", Partition.Group("Question"), 10))
		)
		// A limit of 0 is the unlimited spelling and has no argument at all.
		assertEquals(
			"=AVG(Seconds, group:Question)",
			FormulaWriter.write(spec(Aggregate.AVG, "Seconds", Partition.Group("Question"), 0))
		)
		// last: is a group's alone, so a limit beside a window is dropped rather than
		// written into a formula that parse would then reject.
		assertEquals(
			"=ACCURACY(Seconds, rolling:100)",
			FormulaWriter.write(spec(Aggregate.ACCURACY, "Seconds", Partition.Rolling(100), 7))
		)
		assertEquals(
			"=COUNT(*, bucket:999999)",
			FormulaWriter.write(spec(Aggregate.COUNT, "*", Partition.Bucket(999999), 0))
		)
	}

	@Test
	fun theWorkedExamplesAllParse() {
		val examples = listOf(
			"=MIN(Seconds, group:Question, last:10)" to
				spec(Aggregate.MIN, "Seconds", Partition.Group("Question"), 10),
			"=AVG(Seconds, group:Question)" to
				spec(Aggregate.AVG, "Seconds", Partition.Group("Question"), 0),
			"=STDDEV(Seconds, group:Question, last:10)" to
				spec(Aggregate.STDDEV, "Seconds", Partition.Group("Question"), 10),
			"=ACCURACY(Seconds, rolling:100)" to
				spec(Aggregate.ACCURACY, "Seconds", Partition.Rolling(100), 0),
			"=ACCURACY(Seconds, bucket:100)" to
				spec(Aggregate.ACCURACY, "Seconds", Partition.Bucket(100), 0),
			"=ACCURACY(Seconds, group:Question)" to
				spec(Aggregate.ACCURACY, "Seconds", Partition.Group("Question"), 0),
			"=ACCURACY(Seconds, group:Date)" to
				spec(Aggregate.ACCURACY, "Seconds", Partition.Group("Date"), 0),
			"=COUNT(*, group:Question)" to
				spec(Aggregate.COUNT, "*", Partition.Group("Question"), 0),
			// The documented spelling of an overall figure: one block wider than the table.
			"=ACCURACY(Seconds, bucket:999999)" to
				spec(Aggregate.ACCURACY, "Seconds", Partition.Bucket(999999), 0)
		)

		for ((formula, expected) in examples) {
			assertEquals(formula, ParseResult.Ok(expected), FormulaParser.parse(formula, known))
			// Each example is already in the writer's canonical spelling, so parsing it and
			// writing it back must reproduce the very same text.
			assertEquals(formula, FormulaWriter.write(expected))
		}
	}

	// -- tolerance --------------------------------------------------------------------------

	@Test
	fun whitespaceAroundCommasAndArgumentsIsTolerated() {
		val expected = ParseResult.Ok(spec(Aggregate.MIN, "Seconds", Partition.Group("Question"), 10))

		assertEquals(expected, FormulaParser.parse("=MIN(Seconds,group:Question,last:10)", known))
		assertEquals(
			expected,
			FormulaParser.parse("   =  MIN (  Seconds ,  group : Question ,  last : 10  )   ", known)
		)
	}

	@Test
	fun functionNamesAreCaseInsensitiveAndWriteEmitsUpperCase() {
		val expected = ParseResult.Ok(spec(Aggregate.STDDEV, "Seconds", Partition.Bucket(20), 0))

		assertEquals(expected, FormulaParser.parse("=stddev(Seconds, bucket:20)", known))
		assertEquals(expected, FormulaParser.parse("=StdDev(Seconds, BUCKET:20)", known))
		assertEquals(
			"=STDDEV(Seconds, bucket:20)",
			FormulaWriter.write(spec(Aggregate.STDDEV, "Seconds", Partition.Bucket(20), 0))
		)
	}

	// -- rejection --------------------------------------------------------------------------

	@Test
	fun everyValidationRuleProducesItsOwnMessage() {
		val table = listOf(
			"MIN(Seconds, group:Question)" to "formula must start with \"=\"",
			"" to "formula must start with \"=\"",
			"=AVERAGE(Seconds, group:Question)" to "unknown function \"AVERAGE\"",
			"=IF(Seconds, group:Question)" to "unknown function \"IF\"",
			"=MIN(Secnods, group:Question)" to "unknown column \"Secnods\"",
			"=MIN(*, group:Question)" to "only COUNT accepts \"*\" as a source",
			"=ACCURACY(*, group:Question)" to "only COUNT accepts \"*\" as a source",
			"=MIN(Seconds)" to "a partition argument is required: group:, bucket:, or rolling:",
			"=COUNT(*)" to "a partition argument is required: group:, bucket:, or rolling:",
			"=MIN(Seconds, last:10)" to
				"a partition argument is required: group:, bucket:, or rolling:",
			"=MIN(Seconds, group:Question, bucket:5)" to "only one partition argument is allowed",
			"=MIN(Seconds, rolling:5, bucket:5)" to "only one partition argument is allowed",
			"=MIN(Seconds, bucket:5, last:10)" to "last: is only valid with group:",
			"=MIN(Seconds, rolling:5, last:10)" to "last: is only valid with group:",
			"=MIN(Seconds, group:Quesiton)" to "unknown column \"Quesiton\"",
			"=AVG(Question, group:Question)" to
				"AVG requires a numeric column, but \"Question\" is text",
			"=SUM(Answer, bucket:5)" to "SUM requires a numeric column, but \"Answer\" is text",
			"=MEDIAN(When, bucket:5)" to "MEDIAN requires a numeric column, but \"When\" is a time",
			"=MAX(TimedOut, bucket:5)" to
				"MAX requires a numeric column, but \"TimedOut\" is a boolean",
			"=MIN(Seconds, bucket:abc)" to "bucket size must be a positive integer",
			"=MIN(Seconds, bucket:0)" to "bucket size must be a positive integer",
			"=MIN(Seconds, bucket:-1)" to "bucket size must be a positive integer",
			// A partition argument with no size at all, which reads the same as one that
			// cannot be parsed. views.json takes the other option; see the test below.
			"=MIN(Seconds, bucket:)" to "bucket size must be a positive integer",
			"=MIN(Seconds, rolling:0)" to "rolling size must be a positive integer",
			"=MIN(Seconds, rolling:)" to "rolling size must be a positive integer",
			// 0 is the struct's unlimited spelling, which write omits entirely.
			"=MIN(Seconds, group:Question, last:0)" to "last must be a positive integer",
			"=MIN(Seconds, group:Question, last:3, last:4)" to "last: may only appear once",
			"=MIN(Seconds, group:Question" to "malformed formula",
			"=MIN(Seconds, group:Question))" to "malformed formula",
			"=MIN Seconds, group:Question" to "malformed formula",
			"=MIN(Seconds, group:Question) trailing" to "malformed formula",
			"=MIN(Seconds, window:5)" to "malformed formula",
			"=MIN(Seconds, group:Question, 10)" to "malformed formula",
			"=MIN(Seconds,, group:Question)" to "malformed formula",
			"=MIN(Seconds, group:Question,)" to "malformed formula",
			"=MIN()" to "malformed formula",
			"=(Seconds, group:Question)" to "malformed formula"
		)

		for ((formula, expected) in table) assertEquals(formula, expected, errorOf(formula))
	}

	@Test
	fun arithmeticNestingAndCellReferencesAreMalformed() {
		// The stated non-goal, asserted rather than merely documented. A ratio has to become
		// a new named aggregate; it can never become an operator.
		val rejected = listOf(
			"=AVG(MIN(Seconds), group:Question)",
			"=MIN(Seconds, group:Question)/COUNT(*, group:Question)",
			"=SUM(Seconds, group:Question) + 1",
			"=MIN(Seconds*2, group:Question)",
			"=MIN(Seconds, bucket:5*2)",
			// An operator inside an argument, where the source and size checks would
			// otherwise report a merely misspelt column or an unreadable size.
			"=MIN(Seconds+1, group:Question)",
			"=SUM(Seconds/2, group:Question)",
			"=MIN(Seconds, bucket:5+1)",
			"=A1",
			"=A1+B2",
			"=2*MIN(Seconds, group:Question)"
		)

		for (formula in rejected) assertEquals(formula, "malformed formula", errorOf(formula))
	}

	@Test
	fun onlyBaseColumnIdsAreAcceptedSoAComputedColumnCannotReferenceAnother() {
		// "best10" is what a computed column is called in views.json, and it is exactly what
		// must not resolve: MemberSelectors reads a group key straight off a HistoryEntry.
		assertEquals("unknown column \"best10\"", errorOf("=AVG(best10, group:Question)"))
		assertEquals("unknown column \"best10\"", errorOf("=AVG(Seconds, group:best10)"))
		// The gate is knownColumns and nothing else, so widening the set is what would let
		// one through - which is why ViewsRepository passes the base ids alone.
		assertTrue(
			FormulaParser.parse("=AVG(Seconds, group:best10)", known + "best10") is ParseResult.Ok
		)
	}

	@Test
	fun theKnownColumnSetViewsRepositoryUsesHoldsTheEightBaseIdsOnly() {
		// Asserted on the shared constant itself. A copy derived the same way could not fail
		// when the repository's gate widened, which is the change this is here to catch.
		assertEquals(
			setOf("#", "When", "Date", "Time", "Question", "Answer", "Seconds", "TimedOut"),
			TableEngine.BASE_COLUMN_IDS
		)
	}

	@Test
	fun nothingThrowsHoweverMangledTheInputIs() {
		// A failure has to come back as an Err for the "#ERR" column to exist at all; a
		// throw here would take the whole view down, which is the outcome the design exists
		// to prevent. errorOf asserts the Err, so reaching the end of the loop is the test.
		val hostile = listOf(
			"", " ", "=", "=(", "=)", "=)(", "=MIN(", "=MIN)", "=,", "=MIN(,)", "=MIN(Seconds,",
			"=MIN(Seconds, :)", "=MIN(Seconds, :5)", "=MIN(Seconds, group:)", "=MIN(Seconds, group)",
			"=   ", "==MIN(Seconds, bucket:5)", "=MIN((Seconds), bucket:5)", "=MIN(Seconds, last:)"
		)

		for (formula in hostile) errorOf(formula)

		// An integer past Int.MAX_VALUE is a size failure rather than a NumberFormatException.
		assertEquals(
			"bucket size must be a positive integer",
			errorOf("=MIN(Seconds, bucket:99999999999999999999)")
		)
		// And leading or trailing whitespace on an otherwise legal formula is not a failure.
		assertEquals(
			ParseResult.Ok(spec(Aggregate.MIN, "Seconds", Partition.Bucket(5), 0)),
			FormulaParser.parse("\t=MIN(Seconds, bucket:5)\n", known)
		)
	}

	// -- views.json ---------------------------------------------------------------------------

	@Test
	fun structuredFieldsWinOverTheFormulaAndTheFormulaIsRewrittenToMatch() {
		val paths = AnkiPaths.at(tempFolder.root)
		// A hand-edit that changed the mirror and left the aggregate keys alone. The rival
		// formula PARSES CLEANLY on purpose: one that failed would be discarded by the
		// parser whichever representation won, so the test would pass either way.
		writeOneColumn(
			paths,
			"\"id\":\"best\",\"aggregate\":\"MIN\",\"source\":\"Seconds\",\"limit\":10," +
				"\"partition\":{\"mode\":\"group\",\"by\":\"Question\"}," +
				"\"formula\":\"=MAX(Seconds, bucket:3)\""
		)

		val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

		assertEquals(spec(Aggregate.MIN, "Seconds", Partition.Group("Question"), 10), column.computed)
		assertEquals("=MIN(Seconds, group:Question, last:10)", column.formula)
	}

	@Test
	fun aStructClearsAStaleFormulaErrorRatherThanCarryingItForever() {
		val paths = AnkiPaths.at(tempFolder.root)
		// How a user actually escapes a bad formula: the failure is already on disk and they
		// add the aggregate keys instead of correcting the string. TableEngine keys "#ERR"
		// and unsortability off formulaError alone, so a message left behind here would blank
		// a working aggregate for good.
		writeOneColumn(
			paths,
			"\"id\":\"best\",\"aggregate\":\"MIN\",\"source\":\"Seconds\",\"limit\":10," +
				"\"partition\":{\"mode\":\"group\",\"by\":\"Question\"}," +
				"\"formula\":\"=MIN(Secnods, group:Question)\"," +
				"\"formulaError\":\"unknown column \\\"Secnods\\\"\""
		)

		val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

		assertNull(column.formulaError)
		assertEquals(spec(Aggregate.MIN, "Seconds", Partition.Group("Question"), 10), column.computed)
		assertEquals("=MIN(Seconds, group:Question, last:10)", column.formula)
	}

	@Test
	fun aSuccessfulParseClearsAStaleFormulaError() {
		val paths = AnkiPaths.at(tempFolder.root)
		// The other half of the same rule: the user corrected the string instead. Nothing
		// but a parse writes this field, so a parse that succeeds must also be able to
		// unwrite it.
		writeOneColumn(
			paths,
			"\"id\":\"acc\",\"formula\":\"=ACCURACY(Seconds, rolling:100)\"," +
				"\"formulaError\":\"rolling size must be a positive integer\""
		)

		val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

		assertNull(column.formulaError)
		assertEquals(spec(Aggregate.ACCURACY, "Seconds", Partition.Rolling(100), 0), column.computed)
	}

	@Test
	fun theRepositoryGateAcceptsBaseColumnsOnly() {
		val paths = AnkiPaths.at(tempFolder.root)
		// The invariant asserted through the repository rather than through a set literal:
		// widening the ids it hands the parser is what would let one computed column read
		// another, and only a test that goes through load() can see that happen.
		writeOneColumn(paths, "\"id\":\"x\",\"formula\":\"=AVG(best10, group:Question)\"")

		val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

		assertEquals("unknown column \"best10\"", column.formulaError)
		assertNull(column.computed)
	}

	@Test
	fun aStrayFormulaOnABaseColumnIsInert() {
		val paths = AnkiPaths.at(tempFolder.root)
		// Seconds reads its values off the record, so a formula key on it can only be
		// leftover text. Parsing it would earn the column a formulaError and blank a real
		// data column to "#ERR" - a regression this build would otherwise have introduced.
		writeOneColumn(paths, "\"id\":\"Seconds\",\"formula\":\"=AVG(Question)\"")

		val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

		assertNull(column.formulaError)
		assertNull(column.computed)
		assertEquals("=AVG(Question)", column.formula)
	}

	@Test
	fun aColumnCarryingOnlyAFormulaHasItsStructFilledInAndItsTextNormalised() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.views.writeText(
			"{\"activeViewId\":\"v\",\"views\":[{\"id\":\"v\",\"columns\":[" +
				"{\"id\":\"acc\",\"formula\":\"= accuracy( Seconds , ROLLING:100 )\"}]}]}"
		)

		val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

		assertEquals(
			spec(Aggregate.ACCURACY, "Seconds", Partition.Rolling(100), 0),
			column.computed
		)
		assertEquals("=ACCURACY(Seconds, rolling:100)", column.formula)
		assertNull(column.formulaError)
	}

	@Test
	fun aFormulaThatFailsToParseErrorsItsOwnColumnAndLeavesTheOthersAlone() {
		val paths = AnkiPaths.at(tempFolder.root)
		paths.views.writeText(
			"{\"activeViewId\":\"v\",\"views\":[{\"id\":\"v\",\"columns\":[" +
				"{\"id\":\"Question\",\"title\":\"Question\",\"width\":160}," +
				"{\"id\":\"broken\",\"formula\":\"=AVG(Question, group:Question)\"}," +
				"{\"id\":\"attempts\",\"formula\":\"=COUNT(*, group:Question)\"}]}]}"
		)

		val columns = ViewsRepository(paths).load(tableSettings).views.single().columns

		// One "#ERR" column, not a broken view: the other two load exactly as they would
		// have with no bad formula in the file at all.
		assertEquals(ColumnSpec("Question", "Question", 160), columns[0])
		assertEquals(
			spec(Aggregate.COUNT, "*", Partition.Group("Question"), 0),
			columns[2].computed
		)
		assertNull(columns[1].computed)
		assertEquals(
			"AVG requires a numeric column, but \"Question\" is text",
			columns[1].formulaError
		)
		// Kept verbatim, so the next autosave cannot swallow the text the user wrote.
		assertEquals("=AVG(Question, group:Question)", columns[1].formula)
	}

	@Test
	fun aPartitionWithNoUsableSizeFallsBackToTheDefaultWindowRatherThanAWindowOfOne() {
		// optInt answers 0 for all four of these and MemberSelectors clamps 0 to 1, so
		// without the fallback the column would render each row's own value while looking
		// like an aggregate. Not rejected outright: ColumnSheet builds a size of 0 from an
		// empty field, so rejecting would leave the app writing a file it refuses to read.
		val cases = listOf(
			"{\"mode\":\"bucket\"}" to Partition.Bucket(100),
			"{\"mode\":\"bucket\",\"size\":0}" to Partition.Bucket(100),
			"{\"mode\":\"rolling\",\"size\":-3}" to Partition.Rolling(100),
			"{\"mode\":\"rolling\",\"size\":\"ten\"}" to Partition.Rolling(100)
		)

		for ((partition, expected) in cases) {
			val paths = AnkiPaths.at(tempFolder.newFolder())
			paths.views.writeText(
				"{\"activeViewId\":\"v\",\"views\":[{\"id\":\"v\",\"columns\":[" +
					"{\"id\":\"b\",\"aggregate\":\"AVG\",\"source\":\"Seconds\"," +
					"\"partition\":$partition}]}]}"
			)

			val column = ViewsRepository(paths).load(tableSettings).views.single().columns.single()

			assertEquals(partition, expected, column.computed?.partition)
			// Nothing was quarantined: a size the user fumbled is not a corrupt file.
			assertFalse(partition, File(paths.views.path + ".corrupt").exists())
		}
		assertEquals(100, tableSettings.defaultWindowSize)
	}

	@Test
	fun theParserRejectsAMissingSizeRatherThanDefaultingIt() {
		// The formula surface has no settings to fall back on and a message is the whole
		// point of it, so the same condition reads as an error here.
		assertEquals("bucket size must be a positive integer", errorOf("=AVG(Seconds, bucket:)"))
		assertEquals("rolling size must be a positive integer", errorOf("=AVG(Seconds, rolling:)"))
	}

	@Test
	fun saveAlwaysRegeneratesTheFormulaFromTheStruct() {
		val paths = AnkiPaths.at(tempFolder.root)
		val computed = spec(Aggregate.MEDIAN, "Seconds", Partition.Group("Date"), 25)
		val view = TableView(
			id = "v",
			name = "V",
			filterToCurrentDeck = true,
			collapseDuplicatesOn = null,
			highlightEvery = 5,
			defaultSort = SortSpec("med", SortDir.DESC),
			// A mirror that never described this struct, as a hand-edit or a stale UI write
			// would leave it. The save is what puts the two back in step.
			columns = listOf(ColumnSpec("med", "Med", 80, computed = computed, formula = "=WRONG()"))
		)

		ViewsRepository(paths).save(ViewsFile("v", listOf(view)))

		val stored = JSONObject(paths.views.readText())
			.getJSONArray("views").getJSONObject(0).getJSONArray("columns").getJSONObject(0)
		assertEquals("=MEDIAN(Seconds, group:Date, last:25)", stored.getString("formula"))
		assertEquals(FormulaWriter.write(computed), stored.getString("formula"))
	}

	// -- helpers ------------------------------------------------------------------------------

	/** Writes a views.json holding one view whose single column is [keys] verbatim. */
	private fun writeOneColumn(paths: AnkiPaths, keys: String) {
		paths.views.writeText(
			"{\"activeViewId\":\"v\",\"views\":[{\"id\":\"v\",\"columns\":[{$keys}]}]}"
		)
	}

	/** Every partition shape, with the limits that are meaningful for each. */
	private val partitionsUnderTest: List<Pair<Partition, Int>> = listOf(
		Partition.Group("Question") to 0,
		Partition.Group("Date") to 10,
		Partition.Bucket(100) to 0,
		Partition.Rolling(25) to 0
	)

	private fun assertRoundTrip(spec: ComputedSpec) {
		val written = FormulaWriter.write(spec)
		assertEquals(written, ParseResult.Ok(spec), FormulaParser.parse(written, known))
	}

	private fun spec(fn: Aggregate, source: String, partition: Partition, limit: Int): ComputedSpec =
		ComputedSpec(fn, source, partition, limit)

	/** The message [formula] fails with; fails the test outright if it parses. */
	private fun errorOf(formula: String): String {
		val result = FormulaParser.parse(formula, known)
		assertTrue(formula, result is ParseResult.Err)
		return (result as ParseResult.Err).message
	}
}
