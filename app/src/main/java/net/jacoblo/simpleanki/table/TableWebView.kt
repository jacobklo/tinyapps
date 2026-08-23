/*
 * Renders a RenderedTable with Tabulator inside a WebView.
 *
 * The WebView never reaches the network. A WebViewAssetLoader answers one virtual origin
 * with two handlers: /assets/ serves table.html and the vendored Tabulator bundle out of
 * the APK, and /payload/ serves the current table as JSON. The page fetches the payload
 * itself, so evaluateJavascript only ever carries the four characters "reload()" and never
 * the data.
 *
 * The payload itself is built in TablePayload.kt, which stays free of Android imports so
 * its tests can run on a plain JVM.
 */
package net.jacoblo.simpleanki.table

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

private const val LOG_TAG = "SimpleAnkiTable"

private const val ASSET_DOMAIN = "appassets.androidplatform.net"

private const val PAGE_URL = "https://$ASSET_DOMAIN/assets/table.html"

/**
 * Consecutive rebuilds allowed after a dead render process before the screen gives up.
 *
 * A cap rather than an unconditional rebuild because a table big enough to be killed for
 * memory once is big enough to be killed again, and rebuilding forever would spin.
 * Consecutive is the load-bearing word: the count resets on the next completed render,
 * so three unrelated reclaims spread over an afternoon do not add up to a dead screen.
 */
private const val MAX_RENDERER_RESTARTS = 3

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
	// Two counters, because they answer different questions and must not share one.
	// [instance] identifies the live WebView and only ever increases, so keying on it
	// replaces a dead one wholesale; [deaths] counts renderer deaths since the last
	// completed render, so it resets. Folding them into one would make a reset tear down
	// a WebView that had just proved itself healthy.
	var instance by remember { mutableStateOf(0) }
	var deaths by remember { mutableStateOf(0) }

	if (deaths > MAX_RENDERER_RESTARTS) {
		Box(modifier, contentAlignment = Alignment.Center) {
			Text(
				text = "The table stopped responding. Switch to another screen and back to retry.",
				modifier = Modifier.padding(24.dp),
				style = MaterialTheme.typography.bodyMedium,
				textAlign = TextAlign.Center
			)
		}
		return
	}

	key(instance) {
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

						/**
						 * The renderer runs out of process and can be killed on its own -
						 * for a real crash, or by the OS reclaiming memory from a grid of
						 * five thousand rows. Returning false hands that death back to the
						 * framework, which kills the app process with it, so a background
						 * reclaim would take the user's game session down with the table.
						 *
						 * Returning true keeps the app alive. The View is unusable from
						 * here on, so a new instance replaces it: Compose disposes this
						 * node, onRelease destroys the dead WebView, and the new one loads
						 * the page again from the payload the caller still holds.
						 */
						override fun onRenderProcessGone(
							view: WebView,
							detail: RenderProcessGoneDetail
						): Boolean {
							Log.w(LOG_TAG, "render process gone, crash=${detail.didCrash()}")
							deaths++
							instance++
							return true
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
					// A render that got all the way to drawing rows is the proof that
					// the renderer is healthy, so it retires the deaths that came before.
					bridge.onHostRenderComplete = { deaths = 0 }
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
}
