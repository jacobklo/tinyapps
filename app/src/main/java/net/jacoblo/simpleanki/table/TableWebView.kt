/*
 * Renders a RenderedTable with Tabulator inside a WebView.
 *
 * The WebView never reaches the network. A WebViewAssetLoader answers one virtual origin
 * with two handlers: /assets/ serves table.html and the vendored Tabulator bundle out of
 * the APK, and /payload/ serves the current table as JSON. The page fetches the payload
 * itself, so evaluateJavascript only ever carries the four characters "reload()" and never
 * the data.
 */
package net.jacoblo.simpleanki.table

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import net.jacoblo.simpleanki.data.SortDir
import org.json.JSONArray
import org.json.JSONObject

private const val LOG_TAG = "SimpleAnkiTable"

private const val ASSET_DOMAIN = "appassets.androidplatform.net"

private const val PAGE_URL = "https://$ASSET_DOMAIN/assets/table.html"

/**
 * The payload the page fetches: the whole table, already formatted, as one JSON document.
 *
 * Rows are arrays aligned with [RenderedTable.columns] rather than objects, which at five
 * thousand rows saves a few hundred kilobytes of repeated keys and sidesteps any question
 * about key order.
 */
fun RenderedTable.toPayloadJson(darkTheme: Boolean): String {
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
				.put("dir", if (sort.dir == SortDir.ASC) "asc" else "desc")
		)
		.put("highlightEvery", highlightEvery)
		.put("dark", darkTheme)
		.put("columns", columnsJson)
		.put("rows", rowsJson)
		.toString()
}

/**
 * What survives recomposition: the payload handler the WebView was built against, plus
 * enough state to know whether the page is ready to be told to reload.
 *
 * Every field is touched only from the main thread - Compose's update pass and the
 * WebViewClient callbacks - so none of them needs to be volatile. The payload string
 * itself crosses threads, and that one is volatile inside [PayloadPathHandler].
 */
private class TableWebViewState {
	val handler = PayloadPathHandler()

	/** The payload the page has already been given, so an unchanged table is not re-rendered. */
	var sent: String = ""

	var pageLoaded = false

	/** Set when a new payload arrived before the page could accept a reload. */
	var reloadPending = false
}

/**
 * @param bridge captured once when the WebView is created, so it must be remembered by
 *   the caller rather than rebuilt on every recomposition.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TableWebView(table: RenderedTable, bridge: TableBridge, modifier: Modifier = Modifier) {
	val darkTheme = isSystemInDarkTheme()
	val surface = MaterialTheme.colorScheme.surface.toArgb()
	val payload = remember(table, darkTheme) { table.toPayloadJson(darkTheme) }
	val state = remember { TableWebViewState() }

	AndroidView(
		modifier = modifier,
		factory = { context ->
			// Assigned before loadUrl so the page's own first fetch already sees it.
			state.handler.payload = payload
			state.sent = payload
			val loader = WebViewAssetLoader.Builder()
				.setDomain(ASSET_DOMAIN)
				.addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
				.addPathHandler("/payload/", state.handler)
				.build()
			WebView(context).apply {
				layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT
				)
				setBackgroundColor(surface)
				webViewClient = object : WebViewClient() {
					override fun shouldInterceptRequest(
						view: WebView,
						request: WebResourceRequest
					): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

					override fun onPageFinished(view: WebView, url: String) {
						state.pageLoaded = true
						if (state.reloadPending) {
							state.reloadPending = false
							view.evaluateJavascript("reload()", null)
						}
					}
				}
				// The only window into the page. Errors thrown inside Tabulator surface
				// here and nowhere else.
				webChromeClient = object : WebChromeClient() {
					override fun onConsoleMessage(message: ConsoleMessage): Boolean {
						Log.d(LOG_TAG, "${message.message()} (line ${message.lineNumber()})")
						return true
					}
				}
				settings.javaScriptEnabled = true
				// The page needs neither, and leaving them off keeps file:// and
				// content:// unreachable from JavaScript.
				settings.allowFileAccess = false
				settings.allowContentAccess = false
				addJavascriptInterface(bridge, "Android")
				loadUrl(PAGE_URL)
			}
		},
		update = { webView ->
			if (state.sent != payload) {
				state.sent = payload
				state.handler.payload = payload
				if (state.pageLoaded) {
					webView.evaluateJavascript("reload()", null)
				} else {
					state.reloadPending = true
				}
			}
		},
		onRelease = { it.destroy() }
	)
}
