/*
 * The @JavascriptInterface object the table page calls back through, bound as "Android".
 *
 * Only primitives and String cross this boundary, which is why a column order arrives as
 * a JSON array in a String rather than as a list.
 */
package net.jacoblo.simpleanki.table

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray

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

	/**
	 * Registered by whatever hosts the page - [TableWebView] - rather than by the screen
	 * that built this bridge.
	 *
	 * A completed render is the only proof the renderer came back healthy, and the host
	 * is what counts renderer deaths, so relaying it up to the screen and back down again
	 * would be a longer path to the same place. Set and called on the main thread only.
	 */
	internal var onHostRenderComplete: (() -> Unit)? = null

	@JavascriptInterface
	fun sort(columnId: String) {
		main.post { onSort(columnId) }
	}

	@JavascriptInterface
	fun resize(columnId: String, width: Int) {
		main.post { onResize(columnId, width) }
	}

	/**
	 * @param columnIdsJson every visible column id in the new display order, as a JSON
	 *   array of strings.
	 *
	 * JSON rather than a delimited string because the only shape this boundary accepts is
	 * a String, and no delimiter is safe: the base column ids happen to be comma-free, but
	 * Task 9 lets the user name a computed column and nothing stops them using a comma.
	 * JSON escapes that away instead of assuming it never happens.
	 */
	@JavascriptInterface
	fun reorder(columnIdsJson: String) {
		val array = JSONArray(columnIdsJson)
		val ids = (0 until array.length()).map { array.getString(it) }
		main.post { onReorder(ids) }
	}

	@JavascriptInterface
	fun renderComplete(rowCount: Int) {
		main.post {
			onHostRenderComplete?.invoke()
			onRenderComplete(rowCount)
		}
	}
}
