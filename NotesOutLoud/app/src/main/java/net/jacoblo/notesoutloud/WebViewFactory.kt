package net.jacoblo.notesoutloud

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

class TocJavascriptInterface(private val onTocLoaded: (List<TocItem>) -> Unit) {
    @JavascriptInterface
    fun updateToc(json: String) {
        try {
            val items = ArrayList<TocItem>()
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(TocItem(
                    obj.getString("id"),
                    obj.getString("text"),
                    obj.getInt("level")
                ))
            }
            onTocLoaded(items)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class WebViewFactory(
    private val tabs: List<BrowserTab>,
    private val userScripts: List<UserScript>,
    private val blankingScriptContent: () -> String,
    private val isDarkMode: () -> Boolean,
    private val isBlankingEnabled: () -> Boolean,
    private val blankingPercentage: () -> String
) {

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun createWebView(
        context: android.content.Context,
        onTocLoaded: (List<TocItem>) -> Unit
    ): WebView {
        return WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false

            addJavascriptInterface(TocJavascriptInterface(onTocLoaded), "AndroidToc")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    tabs.find { it.webView == view }?.url?.value = url ?: ""
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tabs.find { it.webView == view }?.title?.value = view?.title ?: "No Title"

                    // Inject User Scripts
                    userScripts.forEach { script ->
                        if (script.content.isNotEmpty()) {
                            view?.evaluateJavascript(script.content, null)
                        }
                    }

                    // Inject Blanking Helper Script (user-editable)
                    view?.evaluateJavascript(blankingScriptContent(), null)

                    // Inject TTS Helper
                    view?.evaluateJavascript(JsScripts.TTS_HELPER_SCRIPT, null)

                    // Inject TOC Extraction
                    view?.evaluateJavascript(JsScripts.TOC_EXTRACTION_SCRIPT, null)

                    // Inject Dark Mode if enabled
                    if (isDarkMode()) {
                        view?.evaluateJavascript(JsScripts.darkModeToggleScript(true), null)
                    }

                    // Re-apply blanking if previously enabled
                    if (isBlankingEnabled()) {
                        val percent = blankingPercentage().toIntOrNull() ?: 5
                        view?.evaluateJavascript("window.AndroidBlanker.toggle(true, $percent)", null)
                    }
                }
            }

            webChromeClient = WebChromeClient()
        }
    }
}
