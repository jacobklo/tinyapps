/*
 * Serves the table payload to the WebView as a virtual URL.
 *
 * A full 5000-row history measures around 350 KB of JSON. Handing that to
 * evaluateJavascript as a script string means the whole payload is parsed as source,
 * which is both slow and fragile at that size. Serving it as a resource instead lets the
 * page fetch() it, so the JSON is only ever parsed as JSON.
 */
package net.jacoblo.simpleanki.table

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * Answers /payload/table.json on the virtual origin TableWebView registers this under.
 * The page reaches it by relative path, so it stays on the origin table.html came from.
 */
class PayloadPathHandler : WebViewAssetLoader.PathHandler {

	/**
	 * The JSON served on the next fetch.
	 *
	 * Volatile because it is written from the main thread and read on the WebView's
	 * request thread. An empty object rather than null so a fetch that beats the first
	 * assignment still gets valid JSON.
	 */
	@Volatile
	var payload: String = "{}"

	/**
	 * Answers every path under /payload/ with the current [payload].
	 *
	 * The six-argument constructor is deliberate: it is the only one that carries response
	 * headers, and without "no-store" the WebView caches the first body and serves it
	 * again on reload, so the table would never change.
	 */
	override fun handle(path: String): WebResourceResponse =
		WebResourceResponse(
			"application/json",
			"utf-8",
			200,
			"OK",
			mapOf("Cache-Control" to "no-store"),
			payload.byteInputStream()
		)
}
