package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.BuildResult
import net.jacoblo.simpleanki.data.CellFormat
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.FieldResult
import net.jacoblo.simpleanki.data.NEW_COLUMN_WIDTH
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.data.ViewsFile
import net.jacoblo.simpleanki.data.addComputed
import net.jacoblo.simpleanki.data.buildComputedSpec
import net.jacoblo.simpleanki.data.builderSeed
import net.jacoblo.simpleanki.data.collapseOn
import net.jacoblo.simpleanki.data.delete
import net.jacoblo.simpleanki.data.generatedTitle
import net.jacoblo.simpleanki.data.moveColumn
import net.jacoblo.simpleanki.data.parseColumnWidth
import net.jacoblo.simpleanki.data.removeColumn
import net.jacoblo.simpleanki.data.rename
import net.jacoblo.simpleanki.data.replaceComputed
import net.jacoblo.simpleanki.data.saveAsNew
import net.jacoblo.simpleanki.data.toggleColumn
import net.jacoblo.simpleanki.data.uniqueId
import net.jacoblo.simpleanki.table.FormulaParser
import net.jacoblo.simpleanki.table.ParseResult
import net.jacoblo.simpleanki.table.TableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers everything the column sheet does to the stored views.
 *
 * The sheet itself only wires callbacks to these, so this is where the rules that matter
 * are pinned: which id a copy gets, that a rename never touches one, that the last view
 * cannot be deleted, and that a base column hides rather than disappearing.
 *
 * The column controls that replaced the header drags are pinned here too, and one of them
 * needs a fixture built for it. An edited computed column must be replaced IN PLACE, so
 * every replaceComputed case below puts it second of three: at the end of the list, or
 * alone in it, an append and a replacement produce the same list and the test would pass
 * against the bug it exists to catch.
 */
class ViewOpsTest {

	private fun view(id: String, name: String = id, vararg columns: ColumnSpec) = TableView(
		id = id,
		name = name,
		filterToCurrentDeck = true,
		collapseDuplicatesOn = null,
		highlightEvery = 5,
		defaultSort = SortSpec(TableEngine.ID_WHEN, SortDir.DESC),
		columns = columns.toList()
	)

	private fun base(id: String, visible: Boolean = true) = ColumnSpec(id, id, 100, visible = visible)

	private fun computed(id: String) = ColumnSpec(
		id = id,
		title = id,
		width = 100,
		computed = ComputedSpec(Aggregate.AVG, TableEngine.ID_SECONDS, Partition.Bucket(5), 0)
	)

	private fun file(active: String, vararg views: TableView) = ViewsFile(active, views.toList())

	// -- uniqueId ---------------------------------------------------------------------

	@Test
	fun uniqueIdSlugifiesTheName() {
		assertEquals("slowest_10", uniqueId("Slowest 10!", emptySet(), "view"))
	}

	@Test
	fun uniqueIdSuffixesOnCollision() {
		assertEquals("stats_2", uniqueId("Stats", setOf("stats"), "view"))
	}

	@Test
	fun uniqueIdKeepsCountingPastAnExistingSuffix() {
		assertEquals("stats_3", uniqueId("Stats", setOf("stats", "stats_2"), "view"))
	}

	/** A name with nothing to derive from - markup, or a script of another alphabet. */
	@Test
	fun uniqueIdFallsBackWhenTheNameHasNoUsableCharacters() {
		assertEquals("view", uniqueId("!!!", emptySet(), "view"))
		assertEquals("view_2", uniqueId("...", setOf("view"), "view"))
	}

	@Test
	fun uniqueIdCollapsesRunsAndTrimsTheEdges() {
		assertEquals("a_b", uniqueId("  a -- b  ", emptySet(), "view"))
	}

	/** What is derived is lowercase; what is stored need not be. */
	@Test
	fun uniqueIdMatchesTakenIdsWhateverTheirCase() {
		assertEquals("seconds_2", uniqueId("Seconds", setOf(TableEngine.ID_SECONDS), "view"))
	}

	// -- saveAsNew --------------------------------------------------------------------

	@Test
	fun saveAsNewAppendsACopyUnderANewId() {
		val original = file("stats", view("stats", "Stats", base(TableEngine.ID_QUESTION)))
		val updated = original.saveAsNew("stats", "My Stats")
		assertEquals(listOf("stats", "my_stats"), updated.views.map { it.id })
		assertEquals("My Stats", updated.views.last().name)
		assertEquals(original.views[0].columns, updated.views.last().columns)
	}

	@Test
	fun saveAsNewSwitchesToTheCopy() {
		val updated = file("stats", view("stats")).saveAsNew("stats", "My Stats")
		assertEquals("my_stats", updated.activeViewId)
	}

	@Test
	fun saveAsNewLeavesTheSourceUntouched() {
		val original = file("stats", view("stats", "Stats"))
		val updated = original.saveAsNew("stats", "My Stats")
		assertEquals(original.views[0], updated.views[0])
	}

	/** Two views may share a name; they may never share an id. */
	@Test
	fun saveAsNewUniquifiesAgainstAnExistingViewOfThatName() {
		val updated = file("stats", view("stats", "Stats")).saveAsNew("stats", "Stats")
		assertEquals(listOf("stats", "stats_2"), updated.views.map { it.id })
		assertEquals(listOf("Stats", "Stats"), updated.views.map { it.name })
	}

	@Test
	fun saveAsNewOfAnUnknownSourceChangesNothing() {
		val original = file("stats", view("stats"))
		assertEquals(original, original.saveAsNew("ghost", "Copy"))
	}

	@Test
	fun saveAsNewNamesTheCopyAfterItsIdWhenGivenABlankName() {
		val updated = file("stats", view("stats")).saveAsNew("stats", "   ")
		assertEquals("view", updated.views.last().id)
		assertEquals("view", updated.views.last().name)
	}

	// -- rename -----------------------------------------------------------------------

	@Test
	fun renameChangesTheNameAndNothingElse() {
		val original = file("stats", view("stats", "Stats", base(TableEngine.ID_QUESTION)))
		val updated = original.rename("stats", "Practice")
		assertEquals("Practice", updated.views[0].name)
		assertEquals(original.views[0].copy(name = "Practice"), updated.views[0])
	}

	/** The whole point of renaming rather than recreating: references keep working. */
	@Test
	fun renameNeverChangesTheId() {
		val updated = file("stats", view("stats", "Stats")).rename("stats", "Something Else")
		assertEquals("stats", updated.views[0].id)
		assertEquals("stats", updated.activeViewId)
	}

	@Test
	fun renameTrimsTheName() {
		assertEquals("Practice", file("v", view("v")).rename("v", "  Practice  ").views[0].name)
	}

	@Test
	fun renameToBlankIsRefused() {
		val original = file("stats", view("stats", "Stats"))
		assertEquals(original, original.rename("stats", "   "))
	}

	@Test
	fun renameLeavesOtherViewsAlone() {
		val updated = file("a", view("a", "A"), view("b", "B")).rename("a", "Renamed")
		assertEquals(listOf("Renamed", "B"), updated.views.map { it.name })
	}

	// -- delete -----------------------------------------------------------------------

	@Test
	fun deleteRemovesTheViewAndKeepsTheRest() {
		val updated = file("a", view("a"), view("b"), view("c")).delete("b")
		assertNotNull(updated)
		assertEquals(listOf("a", "c"), updated!!.views.map { it.id })
	}

	@Test
	fun deletingTheActiveViewFallsBackToTheFirstRemaining() {
		val updated = file("b", view("a"), view("b"), view("c")).delete("b")
		assertEquals("a", updated!!.activeViewId)
	}

	@Test
	fun deletingAnInactiveViewLeavesTheSelectionAlone() {
		val updated = file("c", view("a"), view("b"), view("c")).delete("a")
		assertEquals("c", updated!!.activeViewId)
	}

	/** Without this the drawer would end up with no table entries and no way back. */
	@Test
	fun deletingTheLastViewIsRefused() {
		assertNull(file("a", view("a")).delete("a"))
	}

	@Test
	fun deleteOfAnUnknownIdChangesNothing() {
		val original = file("a", view("a"), view("b"))
		assertEquals(original, original.delete("ghost"))
	}

	/** A built-in deletes like anything else; resetBuiltIns is the way back. */
	@Test
	fun deleteRemovesABuiltIn() {
		val updated = file("history", view("stats"), view("history")).delete("history")
		assertEquals(listOf("stats"), updated!!.views.map { it.id })
	}

	// -- toggleColumn -----------------------------------------------------------------

	@Test
	fun toggleColumnHidesAVisibleColumn() {
		val updated = view("v", "V", base(TableEngine.ID_QUESTION)).toggleColumn(TableEngine.ID_QUESTION)
		assertFalse(updated.columns[0].visible)
	}

	@Test
	fun toggleColumnShowsAHiddenColumn() {
		val hidden = view("v", "V", base(TableEngine.ID_QUESTION, visible = false))
		assertTrue(hidden.toggleColumn(TableEngine.ID_QUESTION).columns[0].visible)
	}

	@Test
	fun toggleColumnLeavesTheOtherColumnsAlone() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), base(TableEngine.ID_ANSWER))
		val updated = original.toggleColumn(TableEngine.ID_ANSWER)
		assertEquals(original.columns[0], updated.columns[0])
		assertEquals(listOf(TableEngine.ID_QUESTION, TableEngine.ID_ANSWER), updated.columns.map { it.id })
	}

	/** Ticking a base column the view carries no spec for has to CREATE the spec. */
	@Test
	fun toggleColumnAddsABaseColumnTheViewDoesNotCarry() {
		val updated = view("v", "V", base(TableEngine.ID_QUESTION)).toggleColumn(TableEngine.ID_ANSWER)
		assertEquals(listOf(TableEngine.ID_QUESTION, TableEngine.ID_ANSWER), updated.columns.map { it.id })
		val added = updated.columns.last()
		assertTrue(added.visible)
		assertEquals(TableEngine.ID_ANSWER, added.title)
		assertEquals(NEW_COLUMN_WIDTH, added.width)
	}

	@Test
	fun toggleColumnIgnoresAnIdThatIsNeitherCarriedNorABaseColumn() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION))
		assertSame(original, original.toggleColumn("ghost"))
	}

	// -- addComputed ------------------------------------------------------------------

	@Test
	fun addComputedAppendsTheSpecWithASlugifiedId() {
		val updated = view("v", "V", base(TableEngine.ID_QUESTION)).addComputed(computed("Avg Seconds"))
		assertEquals(listOf(TableEngine.ID_QUESTION, "avg_seconds"), updated.columns.map { it.id })
		// The title is the user's text verbatim; only the id is transliterated.
		assertEquals("Avg Seconds", updated.columns.last().title)
		assertEquals(Aggregate.AVG, updated.columns.last().computed?.aggregate)
	}

	@Test
	fun addComputedUniquifiesAgainstTheViewsOwnColumns() {
		val original = view("v", "V", computed("avg_seconds"))
		assertEquals("avg_seconds_2", original.addComputed(computed("Avg Seconds")).columns.last().id)
	}

	/** An id of "Seconds" would be read back by TableEngine as the base column. */
	@Test
	fun addComputedUniquifiesAgainstBaseColumnIdsTheViewDoesNotCarry() {
		val updated = view("v", "V").addComputed(computed(TableEngine.ID_SECONDS))
		assertEquals("seconds_2", updated.columns.single().id)
	}

	@Test
	fun addComputedGivesAnUnnameableTitleAFallbackId() {
		assertEquals("column", view("v", "V").addComputed(computed("<<>>")).columns.single().id)
	}

	// -- buildComputedSpec ------------------------------------------------------------

	/**
	 * The user asked for "default 100 and user settable", and this is the 100. Pinned as a
	 * literal because it is a stated requirement rather than an implementation detail; the
	 * settable half is the argument the two tests below vary.
	 */
	@Test
	fun theDefaultWindowIsAHundredRows() {
		assertEquals(100, TableSettings().defaultWindowSize)
	}

	@Test
	fun buildComputedSpecOpensAnEmptyWindowAtTheUsersOwnDefault() {
		// An emptied size field reaches here as a 0, which is the same case ViewsRepository
		// defaults on the way in from a hand-edited file. Both must land on the same number
		// or the column in memory disagrees with the column on disk about how many rows it
		// covers - and a 0 left alone is clamped to a partition of one row, so the column
		// would render each row's own value while looking like an aggregate.
		val settings = TableSettings(defaultWindowSize = 40)
		assertEquals(
			Partition.Bucket(40),
			spec(build(Aggregate.AVG, partition = Partition.Bucket(0), settings = settings)).computed?.partition
		)
		assertEquals(
			Partition.Rolling(40),
			spec(build(Aggregate.AVG, partition = Partition.Rolling(0), settings = settings)).computed?.partition
		)
	}

	@Test
	fun buildComputedSpecLeavesASizeTheUserTypedAlone() {
		val settings = TableSettings(defaultWindowSize = 40)
		assertEquals(
			Partition.Bucket(7),
			spec(build(Aggregate.AVG, partition = Partition.Bucket(7), settings = settings)).computed?.partition
		)
		// A group has no size to default, and its limit is the caller's own: 0 there is the
		// struct's spelling of "every member" rather than an empty field.
		val group = spec(
			build(Aggregate.AVG, partition = Partition.Group("Question"), limit = 0, settings = settings)
		)
		assertEquals(Partition.Group("Question"), group.computed?.partition)
		assertEquals(0, group.computed?.limit)
	}

	@Test
	fun buildComputedSpecDerivesTheFormatFromTheAggregate() {
		// TableEngine falls back to TWO_DP for a computed column, which is right for the six
		// that answer in their source's units and wrong for the two that do not: COUNT would
		// render five attempts as "5.00", and ACCURACY five of six as "83.33" with no sign.
		assertEquals(CellFormat.INT, spec(build(Aggregate.COUNT)).format)
		assertEquals(CellFormat.PERCENT, spec(build(Aggregate.ACCURACY)).format)
		for (fn in listOf(
			Aggregate.MIN, Aggregate.MAX, Aggregate.AVG,
			Aggregate.MEDIAN, Aggregate.SUM, Aggregate.STDDEV
		)) {
			assertEquals(fn.name, CellFormat.TWO_DP, spec(build(fn)).format)
		}
	}

	@Test
	fun buildComputedSpecRefusesAnAggregateItsSourceCannotFeed() {
		// Two taps apart in the sheet, and it renders "-" in every cell. The pickers are
		// independent, so nothing but this stops the pairing being stored.
		val refused = build(Aggregate.AVG, source = TableEngine.ID_QUESTION)
		assertTrue(refused.toString(), refused is BuildResult.Err)

		// The very message the typed formula gives, because it is the very same rule. Two
		// copies would be two chances for the pickers to accept what the formula door
		// rejects, which is how this got past a whole task's worth of tests to begin with.
		val typed = FormulaParser.parse("=AVG(Question, group:Question)", TableEngine.BASE_COLUMN_IDS)
		assertEquals((typed as ParseResult.Err).message, (refused as BuildResult.Err).message)
		assertTrue(refused.message, refused.message.contains(TableEngine.ID_QUESTION))
	}

	@Test
	fun buildComputedSpecAllowsTheTwoAggregatesThatNeverReadTheirSource() {
		// COUNT reads the size of the member set and ACCURACY the timeout flags, so a text
		// source is not merely tolerated there - it is unread either way, and refusing it
		// would make "how many times have I seen this question" unbuildable.
		assertEquals(
			Aggregate.COUNT,
			spec(build(Aggregate.COUNT, source = TableEngine.ID_QUESTION)).computed?.aggregate
		)
		assertEquals(
			Aggregate.ACCURACY,
			spec(build(Aggregate.ACCURACY, source = TableEngine.ID_ANSWER)).computed?.aggregate
		)
	}

	@Test
	fun buildComputedSpecNamesTheColumnAfterThePickersWhenTheUserDoesNot() {
		assertEquals("AVG Seconds", generatedTitle(Aggregate.AVG, TableEngine.ID_SECONDS))
		assertEquals("AVG Seconds", spec(build(Aggregate.AVG, title = "   ")).title)
		// The user's own text otherwise, trimmed, as both id and title; addComputed derives
		// the id it is actually stored under.
		val named = spec(build(Aggregate.AVG, title = "  My Column  "))
		assertEquals("My Column", named.id)
		assertEquals("My Column", named.title)
		assertEquals(NEW_COLUMN_WIDTH, named.width)
	}

	/** A struct with no mirror beside it is not a shape that survives a save. */
	@Test
	fun buildComputedSpecWritesTheFormulaMirrorBesideTheStruct() {
		val built = build(Aggregate.AVG, partition = Partition.Group(TableEngine.ID_QUESTION), limit = 10)
		assertEquals("=AVG(Seconds, group:Question, last:10)", spec(built).formula)
	}

	private fun build(
		aggregate: Aggregate,
		source: String = TableEngine.ID_SECONDS,
		partition: Partition = Partition.Group(TableEngine.ID_QUESTION),
		limit: Int = 10,
		title: String = "",
		settings: TableSettings = TableSettings()
	): BuildResult = buildComputedSpec(aggregate, source, partition, limit, title, settings)

	/** The column a build produced, failing the test when the build was refused instead. */
	private fun spec(result: BuildResult): ColumnSpec {
		assertTrue(result.toString(), result is BuildResult.Ok)
		return (result as BuildResult.Ok).spec
	}

	// -- removeColumn -----------------------------------------------------------------

	@Test
	fun removeColumnDropsAComputedColumn() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), computed("avg"))
		assertEquals(listOf(TableEngine.ID_QUESTION), original.removeColumn("avg").columns.map { it.id })
	}

	/** A base column hides; dropping its spec would silently reset its width and title. */
	@Test
	fun removeColumnRefusesToDropABaseColumn() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), computed("avg"))
		assertSame(original, original.removeColumn(TableEngine.ID_QUESTION))
	}

	/** A hand-edit's leftover has no other way out of views.json. */
	@Test
	fun removeColumnDropsAColumnThatIsNeitherBaseNorComputed() {
		val original = view("v", "V", ColumnSpec("typo", "typo", 100))
		assertTrue(original.removeColumn("typo").columns.isEmpty())
	}

	@Test
	fun removeColumnOfAnUnknownIdChangesNothing() {
		val original = view("v", "V", computed("avg"))
		assertEquals(original.columns, original.removeColumn("ghost").columns)
	}

	// -- collapseOn --------------------------------------------------------------------

	/**
	 * All eight, not just the obvious one: the dropdown offers every base column, so a
	 * rule that only accepted Question would refuse seven live menu entries.
	 */
	@Test
	fun collapseOnAcceptsEveryBaseColumn() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION))
		for (column in TableEngine.BASE_COLUMNS) {
			val updated = original.collapseOn(column.id)
			assertEquals(column.id, updated.collapseDuplicatesOn)
			// The key and nothing else. It shares a data class with the columns.
			assertEquals(original, updated.copy(collapseDuplicatesOn = null))
		}
	}

	@Test
	fun collapseOnNullShowsEveryRowAgain() {
		val collapsed = view("v", "V", base(TableEngine.ID_QUESTION))
			.copy(collapseDuplicatesOn = TableEngine.ID_QUESTION)
		assertNull(collapsed.collapseOn(null).collapseDuplicatesOn)
	}

	/**
	 * The engine only WARNS on a bad key, so nothing downstream would report this. A
	 * computed value belongs to a partition rather than to a row and cannot be a key at
	 * all; an unknown one is a typo. Neither is offered, and neither is accepted.
	 */
	@Test
	fun collapseOnRefusesAnythingButABaseColumn() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), computed("avg"))
			.copy(collapseDuplicatesOn = TableEngine.ID_QUESTION)
		assertSame(original, original.collapseOn("avg"))
		assertSame(original, original.collapseOn("ghost"))
	}

	// -- moveColumn --------------------------------------------------------------------

	@Test
	fun moveColumnUpSwapsWithThePrecedingColumn() {
		val updated = view("v", "V", base("a"), base("b"), base("c")).moveColumn("b", -1)
		assertEquals(listOf("b", "a", "c"), updated.columns.map { it.id })
	}

	@Test
	fun moveColumnDownSwapsWithTheFollowingColumn() {
		val updated = view("v", "V", base("a"), base("b"), base("c")).moveColumn("b", 1)
		assertEquals(listOf("a", "c", "b"), updated.columns.map { it.id })
	}

	@Test
	fun moveColumnUpFromTheFirstPositionIsRefused() {
		val original = view("v", "V", base("a"), base("b"), base("c"))
		assertSame(original, original.moveColumn("a", -1))
	}

	@Test
	fun moveColumnDownFromTheLastPositionIsRefused() {
		val original = view("v", "V", base("a"), base("b"), base("c"))
		assertSame(original, original.moveColumn("c", 1))
	}

	/**
	 * Positions count the hidden columns too. Over the visible subset instead, moving c
	 * up would step over the hidden b and land at the front, which reorders a column the
	 * user cannot see and did not touch.
	 */
	@Test
	fun moveColumnCountsColumnsTheViewIsHiding() {
		val original = view("v", "V", base("a"), base("b", visible = false), base("c"))
		assertEquals(listOf("a", "c", "b"), original.moveColumn("c", -1).columns.map { it.id })
	}

	@Test
	fun moveColumnOfAnUnknownIdChangesNothing() {
		val original = view("v", "V", base("a"), base("b"))
		assertSame(original, original.moveColumn("ghost", 1))
	}

	// -- parseColumnWidth --------------------------------------------------------------

	@Test
	fun parseColumnWidthAcceptsAWholeNumberOfPixels() {
		assertEquals(80, width(parseColumnWidth("80")))
		assertEquals(80, width(parseColumnWidth("  80  ")))
		// The boundary the rule is stated at: one pixel is a width, zero is not.
		assertEquals(1, width(parseColumnWidth("1")))
	}

	@Test
	fun parseColumnWidthRefusesZeroAndBelow() {
		assertTrue(parseColumnWidth("0") is FieldResult.Err)
		assertTrue(parseColumnWidth("-40") is FieldResult.Err)
	}

	/** The empty string included, which is what the field holds mid-retype. */
	@Test
	fun parseColumnWidthRefusesWhatIsNotAWholeNumber() {
		for (typed in listOf("", "   ", "wide", "80.5", "8 0")) {
			assertTrue(typed, parseColumnWidth(typed) is FieldResult.Err)
		}
	}

	/** The value a refused width, and only a refused width, keeps off views.json. */
	private fun width(result: FieldResult<Int>): Int {
		assertTrue(result.toString(), result is FieldResult.Ok)
		return (result as FieldResult.Ok).value
	}

	// -- builderSeed -------------------------------------------------------------------

	@Test
	fun builderSeedOpensANewColumnOnTheDefaults() {
		val settings = TableSettings(defaultWindowSize = 40, defaultLimit = 3)
		val seed = builderSeed(null, settings)
		assertEquals(Aggregate.AVG, seed.aggregate)
		assertEquals(TableEngine.ID_SECONDS, seed.source)
		assertEquals(Partition.Group(TableEngine.ID_QUESTION), seed.partition)
		assertEquals(TableEngine.ID_QUESTION, seed.groupKey)
		// The user's own numbers, not a literal 100 and 10.
		assertEquals(40, seed.size)
		assertEquals(3, seed.limit)
	}

	/** Editing reopens the pickers on the column, which is the whole of what edit means. */
	@Test
	fun builderSeedOpensAnEditedColumnOnItsOwnPickers() {
		val spec = ComputedSpec(Aggregate.MAX, TableEngine.ID_SECONDS, Partition.Group(TableEngine.ID_ANSWER), 7)
		val seed = builderSeed(spec, TableSettings(defaultWindowSize = 40, defaultLimit = 3))
		assertEquals(Aggregate.MAX, seed.aggregate)
		assertEquals(TableEngine.ID_SECONDS, seed.source)
		assertEquals(Partition.Group(TableEngine.ID_ANSWER), seed.partition)
		assertEquals(TableEngine.ID_ANSWER, seed.groupKey)
		assertEquals(7, seed.limit)
	}

	@Test
	fun builderSeedTakesTheWindowSizeFromABucketOrRollingColumn() {
		val settings = TableSettings(defaultWindowSize = 40)
		val bucket = ComputedSpec(Aggregate.AVG, TableEngine.ID_SECONDS, Partition.Bucket(25), 0)
		val rolling = ComputedSpec(Aggregate.AVG, TableEngine.ID_SECONDS, Partition.Rolling(25), 0)
		assertEquals(25, builderSeed(bucket, settings).size)
		assertEquals(25, builderSeed(rolling, settings).size)
		// Neither has a group key, so the By picker still needs a default to show.
		assertEquals(TableEngine.ID_QUESTION, builderSeed(bucket, settings).groupKey)
	}

	/**
	 * A windowed column stores a limit of 0 because a window is already a count of rows.
	 * Seeded from it, the group limit would read "all of them" and the moment the user
	 * switched the partition to a group the column would silently aggregate everything.
	 */
	@Test
	fun builderSeedDoesNotSeedTheGroupLimitFromAWindowedColumn() {
		val rolling = ComputedSpec(Aggregate.AVG, TableEngine.ID_SECONDS, Partition.Rolling(25), 0)
		assertEquals(3, builderSeed(rolling, TableSettings(defaultLimit = 3)).limit)
	}

	// -- replaceComputed ---------------------------------------------------------------

	/**
	 * The edited column is second of three on purpose. At the end of the list, or alone in
	 * it, an append is indistinguishable from a replacement - which is exactly the bug
	 * this rule exists to prevent.
	 */
	@Test
	fun replaceComputedKeepsTheColumnsIdAndItsPosition() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), computed("avg"), base(TableEngine.ID_SECONDS))
		val updated = original.replaceComputed("avg", rebuilt())
		assertEquals(
			listOf(TableEngine.ID_QUESTION, "avg", TableEngine.ID_SECONDS),
			updated.columns.map { it.id }
		)
		assertEquals(3, updated.columns.size)
		// The new contents did land, at that position.
		assertEquals("Max Seconds", updated.columns[1].title)
		assertEquals(Aggregate.MAX, updated.columns[1].computed?.aggregate)
		assertEquals(rebuilt().formula, updated.columns[1].formula)
		// And nothing else moved or changed.
		assertEquals(original.columns[0], updated.columns[0])
		assertEquals(original.columns[2], updated.columns[2])
	}

	/** Width, visibility and frozen are the user's; the builder has no opinion on them. */
	@Test
	fun replaceComputedKeepsWhatTheBuilderDoesNotOwn() {
		val sized = computed("avg").copy(width = 250, visible = false, frozen = true)
		val original = view("v", "V", base(TableEngine.ID_QUESTION), sized, base(TableEngine.ID_SECONDS))
		val updated = original.replaceComputed("avg", rebuilt())
		assertEquals(250, updated.columns[1].width)
		assertFalse(updated.columns[1].visible)
		assertTrue(updated.columns[1].frozen)
	}

	/** The format is the aggregate's, so an AVG edited to a COUNT stops rendering "5.00". */
	@Test
	fun replaceComputedTakesTheFormatFromTheRebuiltColumn() {
		val original = view("v", "V", computed("avg").copy(format = CellFormat.TWO_DP), base(TableEngine.ID_SECONDS))
		val counted = spec(build(Aggregate.COUNT, partition = Partition.Group(TableEngine.ID_QUESTION)))
		assertEquals(CellFormat.INT, original.replaceComputed("avg", counted).columns[0].format)
	}

	/**
	 * The round trip the edit path exists for: reopen the builder on a column, change
	 * nothing, save, and get the same column back - id, position, width and all.
	 */
	@Test
	fun replaceComputedRebuildsAnUnchangedEditIdentically() {
		val settings = TableSettings()
		val seeded = spec(
			build(Aggregate.AVG, partition = Partition.Group(TableEngine.ID_QUESTION), limit = 10, settings = settings)
		)
		val stored = seeded.copy(id = "avg", width = 250)
		val original = view("v", "V", base(TableEngine.ID_QUESTION), stored, base(TableEngine.ID_SECONDS))
		// What the builder hands back having been opened on `stored` and left alone.
		val seed = builderSeed(stored.computed, settings)
		val again = spec(
			buildComputedSpec(
				aggregate = seed.aggregate,
				source = seed.source,
				partition = seed.partition,
				limit = seed.limit,
				title = stored.title,
				tableSettings = settings
			)
		)
		assertEquals(original.columns, original.replaceComputed("avg", again).columns)
	}

	/** A base column has no struct to edit, and two meanings for one id is not a fix. */
	@Test
	fun replaceComputedRefusesABaseColumn() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), computed("avg"))
		assertSame(original, original.replaceComputed(TableEngine.ID_QUESTION, rebuilt()))
	}

	@Test
	fun replaceComputedOfAnUnknownIdChangesNothing() {
		val original = view("v", "V", base(TableEngine.ID_QUESTION), computed("avg"))
		assertSame(original, original.replaceComputed("ghost", rebuilt()))
	}

	/** A column the builder would hand back: new title, new struct, and a starting width. */
	private fun rebuilt(): ColumnSpec = spec(
		build(Aggregate.MAX, partition = Partition.Group(TableEngine.ID_QUESTION), limit = 10, title = "Max Seconds")
	)
}
