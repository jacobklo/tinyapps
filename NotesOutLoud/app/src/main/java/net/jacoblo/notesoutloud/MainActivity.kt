package net.jacoblo.notesoutloud

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jacoblo.notesoutloud.ui.theme.NotesOutLoudTheme
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TocItem(val id: String, val text: String, val level: Int)

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

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    
    // Manage tabs at Activity level
    private val tabs = mutableStateListOf<BrowserTab>()
    private val activeTabIndex = mutableStateOf(0)
    
    // Store injected scripts (URL and Content)
    private val userScripts = mutableStateListOf<UserScript>()
    
    // Dark Mode state
    private val isDarkMode = mutableStateOf(false)

    // TextToSpeech Engine
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val isTtsPlaying = mutableStateOf(false)
    private val isTtsRandom = mutableStateOf(false)
    private val ttsDelaySeconds = mutableStateOf("2")
    private var currentParaIndex = 0
    private var ttsParagraphCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check and request storage permissions for file:// access
        checkAndRequestPermissions()

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Load saved tabs and scripts from persistent storage
        loadSavedState()

        // Handle initial intent
        handleIntent(intent)

        setContent {
            NotesOutLoudTheme {
                BrowserScreen(
                    tabs = tabs,
                    activeTabIndex = activeTabIndex.value,
                    userScripts = userScripts,
                    onTabSelected = { activeTabIndex.value = it },
                    onNewTab = { addNewTab() },
                    onCloseTab = { closeTab(it) },
                    onAddScript = { url -> addScript(url) },
                    onRemoveScript = { script -> userScripts.remove(script) },
                    onZoomIn = {
                        tabs.getOrNull(activeTabIndex.value)?.webView?.zoomIn()
                    },
                    onZoomOut = {
                        tabs.getOrNull(activeTabIndex.value)?.webView?.zoomOut()
                    },
                    // Pass TTS state and callbacks to UI
                    onTtsPlay = { startTts() },
                    onTtsStop = { stopTts() },
                    isTtsPlaying = isTtsPlaying.value,
                    isTtsRandom = isTtsRandom.value,
                    onToggleTtsRandom = { isTtsRandom.value = !isTtsRandom.value },
                    ttsDelay = ttsDelaySeconds.value,
                    onTtsDelayChange = { ttsDelaySeconds.value = it },
                    onTocClick = { id -> handleTocClick(id) },
                    // Dark Mode
                    isDarkMode = isDarkMode.value,
                    onToggleDarkMode = { toggleDarkMode() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 101)
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "TTS Language not supported", Toast.LENGTH_SHORT).show()
            } else {
                isTtsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        // After speaking, wait for delay then play next
                        lifecycleScope.launch(Dispatchers.Main) {
                            val delayMs = (ttsDelaySeconds.value.toLongOrNull() ?: 2L) * 1000
                            delay(delayMs)
                            if (isTtsPlaying.value) {
                                playNextParagraph()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        isTtsPlaying.value = false
                    }
                })
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun startTts() {
        if (!isTtsReady) {
            Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show()
            return
        }
        isTtsPlaying.value = true
        val webView = tabs.getOrNull(activeTabIndex.value)?.webView ?: return

        // Get total paragraph count first
        webView.evaluateJavascript("window.AndroidTtsHelper.getCount()") { countStr ->
            ttsParagraphCount = countStr?.toIntOrNull() ?: 0
            if (ttsParagraphCount > 0) {
                // If we haven't started anywhere, start at 0
                if (currentParaIndex >= ttsParagraphCount) currentParaIndex = 0
                playNextParagraph(speakCurrent = true)
            } else {
                Toast.makeText(this, "No paragraphs found", Toast.LENGTH_SHORT).show()
                isTtsPlaying.value = false
            }
        }
    }

    private fun stopTts() {
        isTtsPlaying.value = false
        tts?.stop()
        val webView = tabs.getOrNull(activeTabIndex.value)?.webView
        // Remove highlight in JS
        webView?.evaluateJavascript("window.AndroidTtsHelper.highlight(-1)", null)
    }

    private fun playNextParagraph(speakCurrent: Boolean = false) {
        if (!isTtsPlaying.value) return

        if (!speakCurrent) {
            if (isTtsRandom.value) {
                currentParaIndex = (0 until ttsParagraphCount).random()
            } else {
                currentParaIndex++
                if (currentParaIndex >= ttsParagraphCount) {
                    stopTts() // End of document
                    return
                }
            }
        }

        val webView = tabs.getOrNull(activeTabIndex.value)?.webView ?: return
        
        // 1. Highlight the paragraph
        // 2. Get the text
        val script = """
            (function() {
                window.AndroidTtsHelper.highlight($currentParaIndex);
                return window.AndroidTtsHelper.getParaText($currentParaIndex);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { text ->
            // JSON string result might be quoted "text" or "null"
            val cleanText = text?.trim()?.removeSurrounding("\"")
                ?.replace("\\n", " ")
                ?.replace("\\\"", "\"") ?: ""

            if (cleanText.isNotEmpty() && cleanText != "null") {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "TTS_ID")
                tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "TTS_ID")
            } else {
                // If empty text, skip to next immediately
                playNextParagraph()
            }
        }
    }

    private fun handleTocClick(id: String) {
        val webView = tabs.getOrNull(activeTabIndex.value)?.webView ?: return
        // Scroll to header
        webView.evaluateJavascript("document.getElementById('$id').scrollIntoView({behavior: 'smooth'});", null)
        
        // Find corresponding paragraph to start TTS
        webView.evaluateJavascript("window.AndroidTtsHelper.getParaIndexAfter('$id')") { res ->
            val idx = res?.toIntOrNull() ?: -1
            if (idx != -1) {
                currentParaIndex = idx
            }
            if (isTtsPlaying.value) {
                // Start speaking from this section
                playNextParagraph(speakCurrent = true)
            }
        }
    }
    
    // Toggle dark mode and apply/remove style in all tabs
    private fun toggleDarkMode() {
        isDarkMode.value = !isDarkMode.value
        tabs.forEach { tab ->
            applyDarkMode(tab.webView, isDarkMode.value)
        }
    }

    // Injects or removes CSS for dark mode
    private fun applyDarkMode(webView: WebView, enable: Boolean) {
        val css = """
            html, body { background:#121212 !important; color:#e0e0e0 !important; }
            body * { color: inherit; }
            a { color:#8ab4f8 !important; }
            pre, code { background:#1e1e1e !important; }
            input, textarea, select { background:#1e1e1e !important; color:#e0e0e0 !important; border:1px solid #333 !important; }
            img, video { filter: brightness(0.9) contrast(1.05); }
            #ext-toc-container { background:rgba(32,32,32,0.92) !important; color:#e0e0e0 !important; border-color:#333 !important; }
            #ext-toc-container a { color:#e0e0e0 !important; }
            #ext-toc-container a:hover { background:rgba(255,255,255,0.08) !important; }
            #ext-toc-container a[style*='font-weight: 700'] { background:rgba(138,180,248,0.25) !important; }
        """.trimIndent().replace("\n", " ")

        val js = """
            (function() {
                var styleId = 'android-dark-mode-style';
                var style = document.getElementById(styleId);
                if ($enable) {
                    if (!style) {
                        style = document.createElement('style');
                        style.id = styleId;
                        style.textContent = `$css`;
                        document.head.appendChild(style);
                    }
                } else {
                    if (style) {
                        style.remove();
                    }
                }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(js, null)
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val url = intent.dataString
            if (url != null) {
                addNewTab(url)
            }
        } else if (tabs.isEmpty()) {
            addNewTab("https://www.google.com")
        }
    }

    private fun addNewTab(url: String = "https://www.google.com") {
        val tocItems = mutableStateListOf<TocItem>()
        // Callback to update TOC items on the Main thread
        val onTocLoaded: (List<TocItem>) -> Unit = { items ->
             lifecycleScope.launch(Dispatchers.Main) {
                 tocItems.clear()
                 tocItems.addAll(items)
             }
        }

        val webView = createWebView(onTocLoaded)
        webView.loadUrl(url)
        tabs.add(BrowserTab(webView, mutableStateOf(url), mutableStateOf("New Tab"), tocItems))
        activeTabIndex.value = tabs.lastIndex
    }

    private fun closeTab(index: Int) {
        if (index in tabs.indices) {
            val tab = tabs.removeAt(index)
            tab.webView.destroy()
            if (activeTabIndex.value >= tabs.size) {
                activeTabIndex.value = maxOf(0, tabs.size - 1)
            }
        }
    }

    private fun saveState() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            val tabsJson = JSONArray()
            tabs.forEach { tab ->
                tabsJson.put(tab.url.value)
            }
            putString("saved_tabs", tabsJson.toString())
            
            val scriptsJson = JSONArray()
            userScripts.forEach { script ->
                val scriptObj = JSONObject()
                scriptObj.put("url", script.url)
                scriptObj.put("content", script.content)
                scriptsJson.put(scriptObj)
            }
            putString("saved_scripts", scriptsJson.toString())
            
            // Save Dark Mode state
            putString("is_dark_mode", isDarkMode.value.toString())
            
            apply()
        }
    }

    private fun loadSavedState() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        
        val savedScripts = sharedPref.getString("saved_scripts", null)
        if (savedScripts != null) {
            try {
                val jsonArray = JSONArray(savedScripts)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    userScripts.add(UserScript(obj.getString("url"), obj.getString("content")))
                }
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        }

        val savedTabs = sharedPref.getString("saved_tabs", null)
        if (savedTabs != null) {
            try {
                val jsonArray = JSONArray(savedTabs)
                for (i in 0 until jsonArray.length()) {
                    val url = jsonArray.getString(i)
                    if (url.isNotEmpty()) {
                        addNewTab(url)
                    }
                }
            } catch (e: Exception) { 
                e.printStackTrace() 
            }
        }
        
        // Load Dark Mode state
        val savedDarkMode = sharedPref.getString("is_dark_mode", "false")
        isDarkMode.value = savedDarkMode.toBoolean()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(onTocLoaded: (List<TocItem>) -> Unit): WebView {
        return WebView(this).apply {
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

            // Add interface for TOC
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
                    
                    // Inject TTS Helper Logic (Matches content.js logic for collecting/highlighting paragraphs)
                    val ttsHelperScript = """
                        window.AndroidTtsHelper = {
                            paragraphs: [],
                            init: function() {
                                // Collect all non-empty paragraphs
                                this.paragraphs = Array.from(document.querySelectorAll('p'))
                                    .filter(p => p.innerText.trim().length > 0);
                            },
                            getParaText: function(index) {
                                if(index < 0 || index >= this.paragraphs.length) return null;
                                return this.paragraphs[index].innerText;
                            },
                            highlight: function(index) {
                                 // Remove existing highlights
                                 document.querySelectorAll('.ext-tts-highlight').forEach(e => e.classList.remove('ext-tts-highlight'));
                                 if(index >= 0 && index < this.paragraphs.length) {
                                     const el = this.paragraphs[index];
                                     el.classList.add('ext-tts-highlight');
                                     el.scrollIntoView({behavior: 'smooth', block: 'center'});
                                 }
                            },
                            getParaIndexAfter: function(elementId) {
                                // Find first paragraph that follows the given element ID
                                const target = document.getElementById(elementId);
                                if(!target) return -1;
                                return this.paragraphs.findIndex(p => 
                                    (target.compareDocumentPosition(p) & Node.DOCUMENT_POSITION_FOLLOWING)
                                );
                            },
                            getCount: function() { return this.paragraphs.length; }
                        };
                        (function(){
                            // Inject highlight styles
                            const style = document.createElement('style');
                            style.textContent = ".ext-tts-highlight { outline: 3px solid #4285F4 !important; background-color: rgba(66, 133, 244, 0.05) !important; transition: all 0.3s ease-in-out; }";
                            document.head.appendChild(style);
                            window.AndroidTtsHelper.init();
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(ttsHelperScript, null)

                    // Inject TOC Extraction Script
                    val tocScript = """
                        (function() {
                            var headings = Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6, details > summary'));
                            var toc = [];
                            var idCounter = 0;
                            for (var i = 0; i < headings.length; i++) {
                                var el = headings[i];
                                if (!el.id) {
                                    el.id = 'android_toc_' + (idCounter++);
                                }
                                var level = 1;
                                if (el.tagName.match(/^H\d$/)) {
                                    level = parseInt(el.tagName.substring(1));
                                } else if (el.tagName === 'SUMMARY') {
                                    var current = el.parentElement;
                                    var depth = 0;
                                    while (current && current !== document.body) {
                                        if (current.tagName === 'DETAILS') depth++;
                                        current = current.parentElement;
                                    }
                                    level = Math.min(6, depth);
                                }
                                toc.push({id: el.id, text: el.innerText.trim(), level: level});
                            }
                            if (window.AndroidToc) {
                                window.AndroidToc.updateToc(JSON.stringify(toc));
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(tocScript, null)
                    
                    // Inject Dark Mode if enabled
                    if (isDarkMode.value) {
                         applyDarkMode(view!!, true)
                    }
                }
            }
            
            webChromeClient = WebChromeClient()
        }
    }
    
    private fun addScript(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = if (url.startsWith("http")) {
                    java.net.URL(url).readText()
                } else if (url.startsWith("file://")) {
                    java.io.File(url.removePrefix("file://")).readText()
                } else {
                    ""
                }
                
                if (content.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        userScripts.add(UserScript(url, content))
                        Toast.makeText(this@MainActivity, "Script added", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to read script or empty", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

data class BrowserTab(
    val webView: WebView,
    val url: MutableState<String>,
    val title: MutableState<String>,
    val tocItems: SnapshotStateList<TocItem>,
    val showToc: MutableState<Boolean> = mutableStateOf(false)
)

data class UserScript(
    val url: String,
    val content: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    tabs: List<BrowserTab>,
    activeTabIndex: Int,
    userScripts: List<UserScript>,
    onTabSelected: (Int) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (Int) -> Unit,
    onAddScript: (String) -> Unit,
    onRemoveScript: (UserScript) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    // TTS Params
    onTtsPlay: () -> Unit,
    onTtsStop: () -> Unit,
    isTtsPlaying: Boolean,
    isTtsRandom: Boolean,
    onToggleTtsRandom: () -> Unit,
    ttsDelay: String,
    onTtsDelayChange: (String) -> Unit,
    onTocClick: (String) -> Unit,
    // Dark Mode Params
    isDarkMode: Boolean,
    onToggleDarkMode: () -> Unit
) {
    var showTabList by remember { mutableStateOf(false) }
    var showScriptList by remember { mutableStateOf(false) }
    
    val activeTab = tabs.getOrNull(activeTabIndex)
    
    BackHandler(enabled = activeTab?.webView?.canGoBack() == true) {
        activeTab?.webView?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (activeTab != null) {
                        var text by remember(activeTab.url.value) { mutableStateOf(activeTab.url.value) }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                var urlToLoad = text
                                if (urlToLoad.startsWith("/")) {
                                    urlToLoad = "file://\$urlToLoad"
                                } else if (!urlToLoad.startsWith("http") && !urlToLoad.startsWith("file") && !urlToLoad.startsWith("content")) {
                                    urlToLoad = "https://\$urlToLoad"
                                }
                                activeTab.webView.loadUrl(urlToLoad)
                            }),
                            leadingIcon = {
                                IconButton(onClick = { showScriptList = true }) {
                                    Icon(Icons.Default.Build, contentDescription = "Inject JS")
                                }
                            }
                        )
                    }
                },
                actions = {
                    // TTS Controls (Right of URL Bar)
                    if (activeTab != null) {
                        // Delay Input
                        OutlinedTextField(
                            value = ttsDelay,
                            onValueChange = onTtsDelayChange,
                            modifier = Modifier.width(60.dp),
                            label = { Text("s", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        
                        // Dark Mode Toggle
                        IconButton(onClick = onToggleDarkMode) {
                             Text(text = if (isDarkMode) "☀" else "🌙", fontSize = 20.sp)
                        }

                        // Play/Stop
                        IconButton(onClick = { if(isTtsPlaying) onTtsStop() else onTtsPlay() }) {
                             val icon = if(isTtsPlaying) Icons.Default.Close else Icons.Default.PlayArrow
                             val tint = if(isTtsPlaying) Color.Red else Color.Green
                             Icon(icon, contentDescription = "Toggle TTS", tint = tint)
                        }

                        // Random/Sequential
                        IconButton(onClick = onToggleTtsRandom) {
                             val tint = if(isTtsRandom) Color(0xFFFBBC05) else MaterialTheme.colorScheme.onSurface
                             // Reuse Refresh icon as shuffle proxy or use specific shuffle if available, 
                             // using Refresh for now as standard icon set is limited in imports
                             Icon(Icons.Default.Refresh, contentDescription = "Shuffle", tint = tint)
                        }
                    }

                    // TOC Toggle
                    IconButton(onClick = { 
                        if (activeTab != null) {
                            activeTab.showToc.value = !activeTab.showToc.value
                        }
                    }) {
                        Icon(
                            Icons.Default.List, 
                            contentDescription = "Table of Contents",
                            tint = if (activeTab?.showToc?.value == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = onZoomOut) {
                        Text("-", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    }
                    IconButton(onClick = onZoomIn) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In")
                    }

                    IconButton(onClick = { showTabList = true }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Tabs")
                            if (tabs.isNotEmpty()) {
                                Text(
                                    text = tabs.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                        .padding(2.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (activeTab != null) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // WebView Container
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()) {
                        AndroidView(
                            factory = { context ->
                                FrameLayout(context).apply { 
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, 
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { container ->
                                if (container.childCount > 0 && container.getChildAt(0) != activeTab.webView) {
                                    container.removeAllViews()
                                }
                                if (container.childCount == 0) {
                                    (activeTab.webView.parent as? ViewGroup)?.removeView(activeTab.webView)
                                    container.addView(activeTab.webView)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    
                    // TOC Sidebar
                    if (activeTab.showToc.value) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.25f)
                                .fillMaxHeight(),
                            tonalElevation = 2.dp,
                            shadowElevation = 4.dp
                        ) {
                            Column {
                                Text(
                                    text = "Table of Contents",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(8.dp),
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(activeTab.tocItems) { item ->
                                        // Calculate Indentation
                                        val indent = 8.dp * (item.level - 1)

                                        Text(
                                            text = item.text.ifBlank { item.id },
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    // Delegate click to main activity
                                                    onTocClick(item.id)
                                                }
                                                .padding(start = 8.dp + indent, top = 8.dp, bottom = 8.dp, end = 8.dp),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = onNewTab) {
                        Text("Open New Tab")
                    }
                }
            }
        }
    }

    if (showTabList) {
        Dialog(onDismissRequest = { showTabList = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tabs", style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { 
                            onNewTab()
                            showTabList = false 
                        }) {
                            Icon(Icons.Default.Add, "New Tab")
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    LazyColumn {
                        itemsIndexed(tabs) { index, tab ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        onTabSelected(index)
                                        showTabList = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = tab.title.value,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = tab.url.value,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(onClick = { onCloseTab(index) }) {
                                        Icon(Icons.Default.Close, "Close")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Script Injection Dialog
    if (showScriptList) {
        var showAddScriptInput by remember { mutableStateOf(false) }
        var newScriptUrl by remember { mutableStateOf("") }
        
        Dialog(onDismissRequest = { showScriptList = false }) {
             Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Inject JS", style = MaterialTheme.typography.titleLarge)
                        if (!showAddScriptInput) {
                            IconButton(onClick = { showAddScriptInput = true }) {
                                Icon(Icons.Default.Add, "Add Script")
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    if (showAddScriptInput) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newScriptUrl,
                                onValueChange = { newScriptUrl = it },
                                label = { Text("Script URL") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(onClick = {
                                if (newScriptUrl.isNotBlank()) {
                                    onAddScript(newScriptUrl)
                                    newScriptUrl = ""
                                    showAddScriptInput = false
                                }
                            }) {
                                Icon(Icons.Default.Add, "Add")
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    LazyColumn {
                        itemsIndexed(userScripts) { index, script ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = script.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2
                                        )
                                    }
                                    IconButton(onClick = { onRemoveScript(script) }) {
                                        Icon(Icons.Default.Delete, "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}