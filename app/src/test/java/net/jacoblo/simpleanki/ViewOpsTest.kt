package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.NEW_COLUMN_WIDTH
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.data.ViewsFile
import net.jacoblo.simpleanki.data.addComputed
import net.jacoblo.simpleanki.data.delete
import net.jacoblo.simpleanki.data.removeColumn
import net.jacoblo.simpleanki.data.rename
import net.jacoblo.simpleanki.data.saveAsNew
import net.jacoblo.simpleanki.data.toggleColumn
import net.jacoblo.simpleanki.data.uniqueId
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
}
