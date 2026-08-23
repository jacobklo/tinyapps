/*
 * The JSON document table.html fetches, built from a RenderedTable.
 *
 * Its own file rather than a neighbour of TableWebView because RenderedTable.kt's rule -
 * stay free of Android imports so JVM tests can reach these types - binds per FILE, not
 * per package. Kotlin compiles every top-level declaration of a file into one XxxKt
 * class, so a single non-const Android-touching val sitting beside this function would
 * drag android.webkit into that class's initialiser and fail TablePayloadTest with a
 * NoClassDefFoundError naming the test rather than the cause.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.SortDir
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire spelling of a sort direction.
 *
 * Shared with TestMode's dump.json rather than spelled out at each site, because the dump
 * exists to mirror exactly what the page was handed: two independent conversions would
 * agree on these two values and diverge the moment a third is added.
 *
 * An exhaustive when rather than a comparison against ASC, so adding that third value is a
 * compile error here instead of a silent "desc" in both documents.
 */
fun SortDir.toWireToken(): String = when (this) {
	SortDir.ASC -> "asc"
	SortDir.DESC -> "desc"
}

/**
 * The payload the page fetches: the whole table, already formatted, as one JSON document.
 *
 * Rows are arrays aligned with [RenderedTable.columns] rather than objects, which at five
 * thousand rows saves a few hundred kilobytes of repeated keys and sidesteps any question
 * about key order.
 *
 * @param highlightColor the row tint, already resolved for [darkTheme]. Passed in rather
 *   than picked here from a TableSettings, because the page needs ONE colour and the
 *   caller is the only party that knows which theme is showing. The page sets it as a CSS
 *   value, so it has to be a colour a browser will accept - see
 *   [net.jacoblo.simpleanki.data.highlightColor], which is what guarantees that.
 */
fun RenderedTable.toPayloadJson(darkTheme: Boolean, highlightColor: String): String {
	val columnsJson = JSONArray()
	for (column in columns) {
		columnsJson.put(
			JSONObject()
				.put("id", column.id)
				.put("title", column.title)
				.put("width", column.width)
				.put("frozen", column.frozen)
				.put("sortable", column.sortable)
				// JSONObject.put drops a Kotlin null outright, and the page reads this
				// key to decide whether the column renders "#ERR".
				.put("error", column.error ?: JSONObject.NULL)
		)
	}
	val rowsJson = JSONArray()
	for (row in rows) {
		rowsJson.put(JSONArray(row))
	}
	return JSONObject()
		.put("viewId", viewId)
		.put(
			"sort",
			JSONObject()
				.put("column", sort.column)
				.put("dir", sort.dir.toWireToken())
		)
		.put("highlightEvery", highlightEvery)
		.put("highlightColor", highlightColor)
		.put("dark", darkTheme)
		.put("columns", columnsJson)
		.put("rows", rowsJson)
		.toString()
}
