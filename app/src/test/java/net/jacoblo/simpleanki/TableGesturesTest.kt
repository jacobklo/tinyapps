package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.ColumnSpec
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.table.nextSort
import net.jacoblo.simpleanki.table.reordered
import net.jacoblo.simpleanki.table.withWidth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the three pure functions behind a header gesture.
 *
 * The sort rule in particular is the one behaviour this task CHANGED - the scaffolding
 * it replaced sorted descending first - and a regression to the old rule is invisible
 * except to someone tapping a phone.
 */
class TableGesturesTest {

	private fun view(vararg columns: ColumnSpec) = TableView(
		id = "v",
		name = "V",
		filterToCurrentDeck = true,
		collapseDuplicatesOn = null,
		highlightEvery = 5,
		defaultSort = SortSpec("a", SortDir.ASC),
		columns = columns.toList()
	)

	private fun col(id: String, width: Int = 100) = ColumnSpec(id, id, width)

	@Test
	fun tappingTheSortedColumnReversesAscToDesc() {
		assertEquals(
			SortSpec("a", SortDir.DESC),
			nextSort(SortSpec("a", SortDir.ASC), "a")
		)
	}

	@Test
	fun tappingTheSortedColumnReversesDescToAsc() {
		assertEquals(
			SortSpec("a", SortDir.ASC),
			nextSort(SortSpec("a", SortDir.DESC), "a")
		)
	}

	@Test
	fun tappingADifferentColumnStartsAscending() {
		assertEquals(
			SortSpec("b", SortDir.ASC),
			nextSort(SortSpec("a", SortDir.ASC), "b")
		)
	}

	/** The delta from the scaffolding: a switch does NOT carry the old direction over. */
	@Test
	fun switchingFromADescendingColumnResetsToAscending() {
		assertEquals(
			SortSpec("b", SortDir.ASC),
			nextSort(SortSpec("a", SortDir.DESC), "b")
		)
	}

	@Test
	fun toggleReturnsToTheStartingSortAfterTwoTaps() {
		val start = SortSpec("a", SortDir.ASC)
		assertEquals(start, nextSort(nextSort(start, "a"), "a"))
	}

	@Test
	fun withWidthUpdatesOnlyTheNamedColumn() {
		val updated = view(col("a", 10), col("b", 20)).withWidth("b", 99)
		assertEquals(listOf(10, 99), updated.columns.map { it.width })
		assertEquals(listOf("a", "b"), updated.columns.map { it.id })
	}

	@Test
	fun withWidthLeavesAViewWithNoSuchColumnAlone() {
		val original = view(col("a", 10), col("b", 20))
		assertEquals(original, original.withWidth("missing", 99))
	}

	@Test
	fun reorderedFollowsTheReportedOrder() {
		val updated = view(col("a"), col("b"), col("c")).reordered(listOf("c", "a", "b"))
		assertEquals(listOf("c", "a", "b"), updated.columns.map { it.id })
	}

	/** A column the page never drew is kept, not dropped - it is still the user's. */
	@Test
	fun reorderedAppendsColumnsThePageDidNotReport() {
		val updated = view(col("a"), col("hidden"), col("b")).reordered(listOf("b", "a"))
		assertEquals(listOf("b", "a", "hidden"), updated.columns.map { it.id })
	}

	@Test
	fun reorderedKeepsUndrawnColumnsInTheirRelativeOrder() {
		val updated = view(col("x"), col("a"), col("y")).reordered(listOf("a"))
		assertEquals(listOf("a", "x", "y"), updated.columns.map { it.id })
	}

	@Test
	fun reorderedIgnoresIdsTheViewDoesNotHave() {
		val updated = view(col("a"), col("b")).reordered(listOf("b", "ghost", "a"))
		assertEquals(listOf("b", "a"), updated.columns.map { it.id })
	}

	@Test
	fun reorderedPreservesEveryColumnsOtherFields() {
		val updated = view(col("a", 10), col("b", 20)).reordered(listOf("b", "a"))
		assertEquals(listOf(20, 10), updated.columns.map { it.width })
	}
}
