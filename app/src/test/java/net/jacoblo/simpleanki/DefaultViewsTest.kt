package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.DefaultViews
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableSettings
import net.jacoblo.simpleanki.data.TableView
import net.jacoblo.simpleanki.table.TableEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the three built-ins.
 *
 * Task 8 seeds views.json from these, so a change here changes what a new install gets
 * and what a factory reset restores - worth failing a test over rather than noticing on
 * a device.
 */
class DefaultViewsTest {

	private val settings = TableSettings(highlightEvery = 7)

	private fun ids(view: TableView): List<String> = view.columns.map { it.id }

	@Test
	fun statsCarriesOnlyItsBaseColumns() {
		val view = DefaultViews.statsView(settings)
		assertEquals("stats", view.id)
		assertEquals("Stats", view.name)
		assertEquals(listOf(TableEngine.ID_QUESTION, TableEngine.ID_SECONDS), ids(view))
		// The aggregates arrive in Task 13, once a pivot engine exists to express them.
		assertTrue(view.columns.all { it.computed == null && it.formula == null })
	}

	@Test
	fun statsTitlesSecondsAsLastAndFreezesQuestion() {
		val view = DefaultViews.statsView(settings)
		assertEquals("Last", view.columns.single { it.id == TableEngine.ID_SECONDS }.title)
		assertTrue(view.columns.single { it.id == TableEngine.ID_QUESTION }.frozen)
	}

	@Test
	fun statsSortsByQuestionAndCollapsesOnIt() {
		val view = DefaultViews.statsView(settings)
		assertEquals(SortSpec(TableEngine.ID_QUESTION, SortDir.ASC), view.defaultSort)
		assertEquals(TableEngine.ID_QUESTION, view.collapseDuplicatesOn)
	}

	@Test
	fun historyShowsEveryFieldNewestFirst() {
		val view = DefaultViews.historyView(settings)
		assertEquals("history", view.id)
		assertEquals("History", view.name)
		assertEquals(
			listOf(
				TableEngine.ID_INDEX, TableEngine.ID_WHEN, TableEngine.ID_QUESTION,
				TableEngine.ID_ANSWER, TableEngine.ID_SECONDS, TableEngine.ID_TIMED_OUT
			),
			ids(view)
		)
		assertEquals(SortSpec(TableEngine.ID_WHEN, SortDir.DESC), view.defaultSort)
		assertNull(view.collapseDuplicatesOn)
	}

	@Test
	fun listRowsIsTheBareQuestionList() {
		val view = DefaultViews.listRowsView(settings)
		assertEquals("list_rows", view.id)
		assertEquals("List Rows", view.name)
		assertEquals(listOf(TableEngine.ID_INDEX, TableEngine.ID_QUESTION), ids(view))
		assertEquals(SortSpec(TableEngine.ID_WHEN, SortDir.DESC), view.defaultSort)
		assertNull(view.collapseDuplicatesOn)
	}

	@Test
	fun everyBuiltInFiltersToTheCurrentDeck() {
		assertTrue(DefaultViews.all(settings).all { it.filterToCurrentDeck })
	}

	@Test
	fun highlightEveryComesFromSettings() {
		assertTrue(DefaultViews.all(settings).all { it.highlightEvery == 7 })
		assertTrue(DefaultViews.all(TableSettings()).all { it.highlightEvery == 5 })
	}

	@Test
	fun allListsTheThreeBuiltInsInDrawerOrder() {
		assertEquals(listOf("stats", "history", "list_rows"), DefaultViews.all(settings).map { it.id })
	}
}
