package net.jacoblo.notesoutloud

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

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
    onToggleDarkMode: () -> Unit,
    // Blanking Feature Params
    isBlankingEnabled: Boolean,
    onToggleBlanking: () -> Unit,
    blankingPercentage: String,
    onBlankingPercentageChange: (String) -> Unit,
    // Blanking Script Editor Params
    blankingScriptContent: String,
    defaultBlankingScript: String,
    onSaveBlankingScript: (String) -> Unit
) {
    var showTabList by remember { mutableStateOf(false) }
    var showScriptList by remember { mutableStateOf(false) }
    var showBlankingScriptEditor by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    val activeTab = tabs.getOrNull(activeTabIndex)

    BackHandler(enabled = activeTab?.webView?.canGoBack() == true) {
        activeTab?.webView?.goBack()
    }

    Scaffold(
        topBar = {
            Column {
                // First bar: Action icons
                TopAppBar(
                    title = { },
                    actions = {
                        if (showControls && activeTab != null) {
                            // Blanking Feature Controls
                            OutlinedTextField(
                                value = blankingPercentage,
                                onValueChange = onBlankingPercentageChange,
                                modifier = Modifier.width(55.dp),
                                label = { Text("%", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            IconButton(onClick = onToggleBlanking) {
                                val icon = if (isBlankingEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility
                                Icon(icon, contentDescription = "Toggle Word Hiding")
                            }

                            // Edit Blanking Script
                            IconButton(onClick = { showBlankingScriptEditor = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Blanking Script")
                            }

                            // TTS Controls - Delay Input
                            OutlinedTextField(
                                value = ttsDelay,
                                onValueChange = onTtsDelayChange,
                                modifier = Modifier.width(55.dp),
                                label = { Text("s", fontSize = 10.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )

                            // Dark Mode Toggle
                            IconButton(onClick = onToggleDarkMode) {
                                Text(text = if (isDarkMode) "\u2600" else "\u263D", fontSize = 20.sp)
                            }

                            // Play/Stop
                            IconButton(onClick = { if (isTtsPlaying) onTtsStop() else onTtsPlay() }) {
                                val icon = if (isTtsPlaying) Icons.Default.Close else Icons.Default.PlayArrow
                                val tint = if (isTtsPlaying) Color.Red else Color.Green
                                Icon(icon, contentDescription = "Toggle TTS", tint = tint)
                            }

                            // Random/Sequential
                            IconButton(onClick = onToggleTtsRandom) {
                                val tint = if (isTtsRandom) Color(0xFFFBBC05) else MaterialTheme.colorScheme.onSurface
                                Icon(Icons.Default.Refresh, contentDescription = "Shuffle", tint = tint)
                            }

                            // TOC Toggle
                            IconButton(onClick = {
                                activeTab.showToc.value = !activeTab.showToc.value
                            }) {
                                Icon(
                                    Icons.Default.List,
                                    contentDescription = "Table of Contents",
                                    tint = if (activeTab.showToc.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
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

                        // Toggle Button to show/hide controls (Always visible)
                        IconButton(onClick = { showControls = !showControls }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Toggle Controls")
                        }
                    }
                )

                // Second bar: URL bar
                if (activeTab != null) {
                    var text by remember(activeTab.url.value) { mutableStateOf(activeTab.url.value) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            var urlToLoad = text
                            if (urlToLoad.startsWith("/")) {
                                urlToLoad = "file://$urlToLoad"
                            } else if (!urlToLoad.startsWith("http") && !urlToLoad.startsWith("file") && !urlToLoad.startsWith("content")) {
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
            }
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
                    TocSidebar(activeTab, onTocClick)
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

    // Dialogs
    if (showTabList) {
        TabListDialog(
            tabs = tabs,
            onTabSelected = onTabSelected,
            onNewTab = onNewTab,
            onCloseTab = onCloseTab,
            onDismiss = { showTabList = false }
        )
    }

    if (showScriptList) {
        ScriptInjectionDialog(
            userScripts = userScripts,
            onAddScript = onAddScript,
            onRemoveScript = onRemoveScript,
            onDismiss = { showScriptList = false }
        )
    }

    if (showBlankingScriptEditor) {
        BlankingScriptEditorDialog(
            blankingScriptContent = blankingScriptContent,
            defaultBlankingScript = defaultBlankingScript,
            onSave = onSaveBlankingScript,
            onDismiss = { showBlankingScriptEditor = false }
        )
    }
}

@Composable
fun TocSidebar(activeTab: BrowserTab, onTocClick: (String) -> Unit) {
    if (!activeTab.showToc.value) return

    val density = LocalDensity.current
    var tocWidth by remember { mutableStateOf(300.dp) }

    // Draggable Handle
    Box(
        modifier = Modifier
            .width(8.dp)
            .fillMaxHeight()
            .background(Color.LightGray)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    val delta = with(density) { dragAmount.toDp() }
                    tocWidth = (tocWidth - delta).coerceIn(100.dp, 600.dp)
                }
            }
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Gray).align(Alignment.Center))
    }

    Surface(
        modifier = Modifier
            .width(tocWidth)
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
                    val indent = 8.dp * (item.level - 1)
                    Text(
                        text = item.text.ifBlank { item.id },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTocClick(item.id) }
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
