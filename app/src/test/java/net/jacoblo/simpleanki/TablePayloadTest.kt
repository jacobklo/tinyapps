package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.table.RenderedColumn
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.table.toPayloadJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the JSON contract table.html reads.
 *
 * Assertions go through a parse rather than over the raw string, because org.json makes
 * no promise about key order and the page does not care about it either.
 */
class TablePayloadTest {

	private fun column(
		id: String,
		title: String = id,
		width: Int = 100,
		frozen: Boolean = false,
		sortable: Boolean = true,
		error: String? = null
	) = RenderedColumn(id, title, width, frozen, ColumnType.TEXT, sortable, error)

	private fun table(
		columns: List<RenderedColumn>,
		rows: List<List<String>>,
		sort: SortSpec = SortSpec("When", SortDir.DESC),
		highlightEvery: Int = 5
	) = RenderedTable(
		viewId = "history",
		sort = sort,
		columns = columns,
		rows = rows,
		highlightEvery = highlightEvery,
		visibleRowCount = rows.size,
		warnings = emptyList()
	)

	@Test
	fun serializesEveryTopLevelField() {
		val payload = JSONObject(
			table(listOf(column("When")), listOf(listOf("08-22 10:05"))).toPayloadJson(false)
		)

		assertEquals("history", payload.getString("viewId"))
		assertEquals(5, payload.getInt("highlightEvery"))
		assertFalse(payload.getBoolean("dark"))
		assertEquals("When", payload.getJSONObject("sort").getString("column"))
		assertEquals("desc", payload.getJSONObject("sort").getString("dir"))
	}

	@Test
	fun darkFlagFollowsTheArgument() {
		val payload = JSONObject(table(listOf(column("When")), emptyList()).toPayloadJson(true))

		assertTrue(payload.getBoolean("dark"))
	}

	@Test
	fun ascendingSortSerializesLowercase() {
		val payload = JSONObject(
			table(listOf(column("Question")), emptyList(), SortSpec("Question", SortDir.ASC))
				.toPayloadJson(false)
		)

		assertEquals("asc", payload.getJSONObject("sort").getString("dir"))
	}

	@Test
	fun serializesEveryColumnField() {
		val payload = JSONObject(
			table(
				listOf(column("Question", title = "Card", width = 220, frozen = true, sortable = false)),
				emptyList()
			).toPayloadJson(false)
		)
		val column = payload.getJSONArray("columns").getJSONObject(0)

		assertEquals("Question", column.getString("id"))
		assertEquals("Card", column.getString("title"))
		assertEquals(220, column.getInt("width"))
		assertTrue(column.getBoolean("frozen"))
		assertFalse(column.getBoolean("sortable"))
		assertTrue(column.isNull("error"))
	}

	/**
	 * Both flags in both polarities, in one payload.
	 *
	 * Asserting either flag in only one polarity would be satisfied by an implementation
	 * that wrote that value unconditionally, so both columns are checked for all four.
	 */
	@Test
	fun booleanColumnFlagsAreReadPerColumn() {
		val payload = JSONObject(
			table(
				listOf(
					column("Question", frozen = true, sortable = false),
					column("Seconds", frozen = false, sortable = true)
				),
				emptyList()
			).toPayloadJson(false)
		)
		val first = payload.getJSONArray("columns").getJSONObject(0)
		val second = payload.getJSONArray("columns").getJSONObject(1)

		assertTrue(first.getBoolean("frozen"))
		assertFalse(first.getBoolean("sortable"))
		assertFalse(second.getBoolean("frozen"))
		assertTrue(second.getBoolean("sortable"))
	}

	/**
	 * Rows are positional, so a reordered columns array would silently mislabel every
	 * cell. Asserted directly rather than left implicit in the row-ordering test.
	 */
	@Test
	fun columnsKeepTheirInputOrder() {
		val payload = JSONObject(
			table(
				listOf(column("#"), column("When"), column("Question"), column("Seconds")),
				emptyList()
			).toPayloadJson(false)
		)
		val columns = payload.getJSONArray("columns")

		assertEquals(4, columns.length())
		assertEquals(
			listOf("#", "When", "Question", "Seconds"),
			(0 until columns.length()).map { columns.getJSONObject(it).getString("id") }
		)
	}

	/** A dropped key would leave the page unable to tell an errored column from a good one. */
	@Test
	fun columnErrorSurvivesAsAString() {
		val payload = JSONObject(
			table(listOf(column("Ratio", error = "unknown token")), emptyList()).toPayloadJson(false)
		)
		val column = payload.getJSONArray("columns").getJSONObject(0)

		assertFalse(column.isNull("error"))
		assertEquals("unknown token", column.getString("error"))
	}

	/** Rows are arrays aligned with the columns, not objects keyed by column id. */
	@Test
	fun rowsSerializeAsArraysInColumnOrder() {
		val payload = JSONObject(
			table(
				listOf(column("#"), column("When"), column("Seconds")),
				listOf(listOf("1", "08-22 10:05", "2.40"), listOf("2", "08-22 10:04", "3.10"))
			).toPayloadJson(false)
		)
		val rows = payload.getJSONArray("rows")

		assertEquals(2, rows.length())
		assertEquals(3, rows.getJSONArray(0).length())
		assertEquals("1", rows.getJSONArray(0).getString(0))
		assertEquals("08-22 10:05", rows.getJSONArray(0).getString(1))
		assertEquals("2.40", rows.getJSONArray(0).getString(2))
		assertEquals("3.10", rows.getJSONArray(1).getString(2))
	}

	/** The empty table still has to be valid JSON with its headers intact. */
	@Test
	fun noRowsStillCarriesTheColumns() {
		val payload = JSONObject(
			table(listOf(column("#"), column("Question")), emptyList()).toPayloadJson(false)
		)

		assertEquals(2, payload.getJSONArray("columns").length())
		assertEquals(0, payload.getJSONArray("rows").length())
	}

	/** Cells are free text, so anything that would break out of a JSON string must escape. */
	@Test
	fun cellsWithQuotesAndNewlinesRoundTrip() {
		val cell = "say \"hi\"\n\\ end"
		val payload = JSONObject(table(listOf(column("Answer")), listOf(listOf(cell))).toPayloadJson(false))

		assertEquals(cell, payload.getJSONArray("rows").getJSONArray(0).getString(0))
	}

	@Test
	fun highlightEveryZeroSerializesAsZero() {
		val payload = JSONObject(
			table(listOf(column("#")), emptyList(), highlightEvery = 0).toPayloadJson(false)
		)

		assertEquals(0, payload.getInt("highlightEvery"))
	}
}
