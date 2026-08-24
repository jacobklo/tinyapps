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
	val warnings: List<String>,
	/**
	 * Whether the page's header menu may offer its four view-editing items: hide, freeze,
	 * and the two moves.
	 *
	 * False for a table whose columns are FIXED in Kotlin rather than read from views.json,
	 * which is meant for a drill stats table. There is no view file to save such an edit
	 * into, so each of the four would change the page and then revert on the next render -
	 * and Unfreeze would do real damage before it reverted. DrillStatsTable freezes "#" and
	 * "When" together because Tabulator's FrozenColumnsModule scans columns from the left
	 * and flips permanently to the RIGHT edge at the first unfrozen one, so a user who
	 * unfroze "#" would send "When" to the far side of the table rather than merely
	 * unfreezing it.
	 *
	 * Named for the VIEW and nothing wider, because nothing wider is suppressed. A header
	 * tap still SORTS - DrillStatsTable.nextSort exists for exactly that - cells were never
	 * editable on any table here, both header drags are already off for all of them, and
	 * Copy values survives because it stores nothing and so has nothing to revert.
	 *
	 * A field rather than a toPayloadJson parameter, which is how darkTheme and
	 * highlightColor arrive. Those two vary per render with the theme in force, so only the
	 * host can supply them; this is fixed for whatever produced the table, which is why it
	 * sits beside highlightEvery instead.
	 *
	 * Defaulted true because a view-backed table persists all four, which is every table
	 * that existed before drills.
	 */
	val viewEditable: Boolean = true
)
