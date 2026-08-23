/*
 * The @JavascriptInterface object the table page calls back through, bound as "Android".
 *
 * Only primitives and String cross this boundary, which is why a column order arrives as
 * CSV rather than as a list.
 */
package net.jacoblo.simpleanki.table

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Every callback is dispatched to the main thread before it reaches [onSort] and friends.
 *
 * WebView invokes @JavascriptInterface methods on a private binder thread, so a handler
 * that touched Compose state directly would be mutating it off the main thread. Hopping
 * here rather than in each caller means no consumer can get that wrong.
 */
class TableBridge(
	private val onSort: (columnId: String) -> Unit,
	private val onResize: (columnId: String, width: Int) -> Unit,
	private val onReorder: (columnIds: List<String>) -> Unit,
	private val onRenderComplete: (rowCount: Int) -> Unit
) {
	private val main = Handler(Looper.getMainLooper())

	@JavascriptInterface
	fun sort(columnId: String) {
		main.post { onSort(columnId) }
	}

	@JavascriptInterface
	fun resize(columnId: String, width: Int) {
		main.post { onResize(columnId, width) }
	}

	/**
	 * @param columnIdsCsv every visible column id in the new display order.
	 *
	 * Column ids are the base column names, none of which contains a comma, so a plain
	 * split is enough and no escaping scheme is needed.
	 */
	@JavascriptInterface
	fun reorder(columnIdsCsv: String) {
		val ids = columnIdsCsv.split(",").filter { it.isNotEmpty() }
		main.post { onReorder(ids) }
	}

	@JavascriptInterface
	fun renderComplete(rowCount: Int) {
		main.post { onRenderComplete(rowCount) }
	}
}
