package net.jacoblo.notesoutloud

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jacoblo.notesoutloud.ui.theme.NotesOutLoudTheme

class MainActivity : ComponentActivity() {
    
    // Manage tabs at Activity level to survive config changes if needed, 
    // or just simplified as a static object for this "extremely simple" example.
    private val tabs = mutableStateListOf<BrowserTab>()
    private val activeTabIndex = mutableStateOf(0)
    
    // Store injected scripts (URL and Content)
    private val userScripts = mutableStateListOf<UserScript>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                    onRemoveScript = { script -> userScripts.remove(script) }
                )
            }
        }
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
        val webView = createWebView()
        webView.loadUrl(url)
        tabs.add(BrowserTab(webView, mutableStateOf(url), mutableStateOf("New Tab")))
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Update tab URL state
                    tabs.find { it.webView == view }?.url?.value = url ?: ""
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    tabs.find { it.webView == view }?.title?.value = view?.title ?: "No Title"
                    
                    // Inject Scripts into the page
                    userScripts.forEach { script ->
                        if (script.content.isNotEmpty()) {
                            view?.evaluateJavascript(script.content, null)
                        }
                    }
                }
            }
            
            webChromeClient = WebChromeClient()
        }
    }
    
    private fun addScript(url: String) {
        // Fetch script content in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = if (url.startsWith("http")) {
                    java.net.URL(url).readText()
                } else if (url.startsWith("file://")) {
                    java.io.File(url.removePrefix("file://")).readText()
                } else {
                    "" // Unsupported scheme or invalid
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

// Data class to hold tab state
data class BrowserTab(
    val webView: WebView,
    val url: MutableState<String>,
    val title: MutableState<String>
)

// Data class for User Scripts
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
    onRemoveScript: (UserScript) -> Unit
) {
    var showTabList by remember { mutableStateOf(false) }
    var showScriptList by remember { mutableStateOf(false) }
    
    // Safety check for active tab
    val activeTab = tabs.getOrNull(activeTabIndex)
    
    // Handle back button for WebView navigation
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
                                if (!urlToLoad.startsWith("http") && !urlToLoad.startsWith("file") && !urlToLoad.startsWith("content")) {
                                    urlToLoad = "https://$urlToLoad"
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
                    // Button to toggle tab list
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
                // Host the WebView
                AndroidView(
                    factory = { context ->
                        // Return a container that we can swap WebViews in/out of
                        FrameLayout(context).apply { 
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { container ->
                        // Remove old views and add the active WebView
                        if (container.childCount > 0 && container.getChildAt(0) != activeTab.webView) {
                            container.removeAllViews()
                        }
                        if (container.childCount == 0) {
                            // Ensure WebView has a parent removal check if needed
                            (activeTab.webView.parent as? ViewGroup)?.removeView(activeTab.webView)
                            container.addView(activeTab.webView)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
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
