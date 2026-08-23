/*
 * What a header gesture does to the sort and to the view, as pure functions.
 *
 * Split out of TableScreen.kt for the reason TablePayload.kt is split out of
 * TableWebView.kt: RenderedTable.kt's no-Android rule binds per file, because Kotlin
 * folds a file's top-level declarations into one XxxKt class. TableScreen.kt is a
 * composable and unavoidably Android; these three are the logic worth testing, so they
 * live where a JVM test can reach them.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.SortDir
import net.jacoblo.simpleanki.data.SortSpec
import net.jacoblo.simpleanki.data.TableView

/**
 * The sort a tap on [columnId] produces, given the sort [current]ly applied.
 *
 * Tapping a column sorts it ascending; tapping the one already sorted reverses it. Note
 * that a tap on a different column RESETS to ascending rather than keeping the direction
 * in force, so the first tap on any column always means "smallest first".
 */
internal fun nextSort(current: SortSpec, columnId: String): SortSpec =
	if (current.column == columnId) {
		SortSpec(columnId, if (current.dir == SortDir.ASC) SortDir.DESC else SortDir.ASC)
	} else {
		SortSpec(columnId, SortDir.ASC)
	}

/** A copy of this view with [columnId]'s width replaced, or the same view if it has no such column. */
internal fun TableView.withWidth(columnId: String, width: Int): TableView =
	copy(columns = columns.map { if (it.id == columnId) it.copy(width = width) else it })

/**
 * A copy of this view with its columns in the order [columnIds] names.
 *
 * The page only ever reports the columns it drew, so any column it could not draw -
 * hidden today, and from Task 9 a computed one whose formula failed - is missing from
 * that list. Those are appended in their existing relative order rather than dropped,
 * since dropping them would delete a column the user still owns.
 */
internal fun TableView.reordered(columnIds: List<String>): TableView {
	val byId = columns.associateBy { it.id }
	val named = columnIds.toSet()
	val moved = columnIds.mapNotNull { byId[it] }
	val rest = columns.filter { it.id !in named }
	return copy(columns = moved + rest)
}
