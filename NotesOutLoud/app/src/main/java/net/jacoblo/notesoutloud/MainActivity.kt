package net.jacoblo.notesoutloud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jacoblo.notesoutloud.ui.theme.NotesOutLoudTheme

class MainActivity : ComponentActivity() {

    private val tabs = mutableStateListOf<BrowserTab>()
    private val activeTabIndex = mutableStateOf(0)
    private val userScripts = mutableStateListOf<UserScript>()
    private val isDarkMode = mutableStateOf(false)
    private val isBlankingEnabled = mutableStateOf(false)
    private val blankingPercentage = mutableStateOf("5")
    private val blankingScriptContent = mutableStateOf(JsScripts.DEFAULT_BLANKING_SCRIPT)

    private lateinit var ttsManager: TtsManager
    private lateinit var stateManager: StateManager
    private lateinit var webViewFactory: WebViewFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stateManager = StateManager(this)
        ttsManager = TtsManager(lifecycleScope) {
            tabs.getOrNull(activeTabIndex.value)?.webView
        }
        ttsManager.init(this) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        webViewFactory = WebViewFactory(
            tabs = tabs,
            userScripts = userScripts,
            blankingScriptContent = { blankingScriptContent.value },
            isDarkMode = { isDarkMode.value },
            isBlankingEnabled = { isBlankingEnabled.value },
            blankingPercentage = { blankingPercentage.value }
        )

        checkAndRequestPermissions()
        loadSavedState()
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
                        tabs.getOrNull(activeTabIndex.value)?.webView?.settings?.let {
                           it.textZoom = (it.textZoom + 10).coerceAtMost(300)
                        }
                    },
                    onZoomOut = {
                        tabs.getOrNull(activeTabIndex.value)?.webView?.settings?.let {
                           it.textZoom = (it.textZoom - 10).coerceAtLeast(50)
                        }
                    },
                    onTtsPlay = { ttsManager.start() },
                    onTtsStop = { ttsManager.stop() },
                    isTtsPlaying = ttsManager.isTtsPlaying.value,
                    isTtsRandom = ttsManager.isTtsRandom.value,
                    onToggleTtsRandom = { ttsManager.isTtsRandom.value = !ttsManager.isTtsRandom.value },
                    ttsDelay = ttsManager.ttsDelaySeconds.value,
                    onTtsDelayChange = { ttsManager.ttsDelaySeconds.value = it },
                    onTocClick = { id -> ttsManager.handleTocClick(id) },
                    isDarkMode = isDarkMode.value,
                    onToggleDarkMode = { toggleDarkMode() },
                    isBlankingEnabled = isBlankingEnabled.value,
                    onToggleBlanking = { toggleBlanking() },
                    blankingPercentage = blankingPercentage.value,
                    onBlankingPercentageChange = {
                        blankingPercentage.value = it
                        if (isBlankingEnabled.value) applyBlanking()
                    },
                    blankingScriptContent = blankingScriptContent.value,
                    defaultBlankingScript = JsScripts.DEFAULT_BLANKING_SCRIPT,
                    onSaveBlankingScript = { script ->
                        blankingScriptContent.value = script
                        saveState()
                        tabs.getOrNull(activeTabIndex.value)?.webView?.reload()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        ttsManager.destroy()
        super.onDestroy()
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

    private fun toggleDarkMode() {
        isDarkMode.value = !isDarkMode.value
        tabs.forEach { tab ->
            tab.webView.evaluateJavascript(JsScripts.darkModeToggleScript(isDarkMode.value), null)
        }
    }

    private fun toggleBlanking() {
        isBlankingEnabled.value = !isBlankingEnabled.value
        applyBlanking()
    }

    private fun applyBlanking() {
        val webView = tabs.getOrNull(activeTabIndex.value)?.webView ?: return
        val percent = blankingPercentage.value.toIntOrNull() ?: 5
        webView.evaluateJavascript("window.AndroidBlanker.toggle(${isBlankingEnabled.value}, $percent)", null)
    }

    private fun addNewTab(url: String = "https://www.google.com") {
        val tocItems = mutableStateListOf<TocItem>()
        val onTocLoaded: (List<TocItem>) -> Unit = { items ->
            lifecycleScope.launch(Dispatchers.Main) {
                tocItems.clear()
                tocItems.addAll(items)
            }
        }

        val webView = webViewFactory.createWebView(this, onTocLoaded)
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
        stateManager.save(tabs, userScripts, isDarkMode.value, blankingScriptContent.value)
    }

    private fun loadSavedState() {
        val state = stateManager.load()
        state.scripts.forEach { userScripts.add(it) }
        state.tabUrls.forEach { addNewTab(it) }
        isDarkMode.value = state.isDarkMode
        if (state.blankingScript != null) {
            blankingScriptContent.value = state.blankingScript
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
