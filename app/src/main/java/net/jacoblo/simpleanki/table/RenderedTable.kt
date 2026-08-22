/*
 * The value object the table render pipeline produces.
 *
 * Everything here is already presentation ready: the columns are the visible ones in
 * display order and every cell is a finished string. A renderer sorts nothing, computes
 * nothing, and formats nothing.
 *
 * Free of Android imports on purpose so JVM tests can assert on these types.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.SortSpec

data class RenderedColumn(
	val id: String,
	val title: String,
	val width: Int,
	val frozen: Boolean,
	val type: ColumnType,
	val sortable: Boolean,
	/** Non-null when the column's formula failed; every cell renders "#ERR". */
	val error: String? = null
)

data class RenderedTable(
	val viewId: String,
	/** The sort actually applied, which is not the requested one when that was unusable. */
	val sort: SortSpec,
	/** Visible columns only, in display order. */
	val columns: List<RenderedColumn>,
	/** Formatted cell strings, outer list is rows, inner aligns with [columns]. */
	val rows: List<List<String>>,
	val highlightEvery: Int,
	val visibleRowCount: Int,
	/** Human-readable problems, surfaced in the column sheet. */
	val warnings: List<String>
)
