package net.jacoblo.notesoutloud

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    onSaveBlankingScript: (String) -> Unit,
    // TTS Settings
    onOpenTtsSettings: () -> Unit,
    // Page Source Editor
    onEditPageSource: () -> Unit,
    onApplyPageSource: (String) -> Unit,
    onDismissPageSourceEditor: () -> Unit,
    pageSourceToEdit: String?
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
                // Spacer to push content below the system status bar
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))

                // URL bar with toggle controls on the right
                if (activeTab != null) {
                    var text by remember(activeTab.url.value) { mutableStateOf(activeTab.url.value) }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
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
                            })
                        )
                        TooltipIconButton("Toggle Controls", onClick = { showControls = !showControls }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                    }
                }

                // Grouped icon rows below the URL bar
                if (showControls && activeTab != null) {
                    // Group 1: Edit Source
                    ToolbarGroup(label = "Edit Source") {
                        OutlinedTextField(
                            value = blankingPercentage,
                            onValueChange = onBlankingPercentageChange,
                            modifier = Modifier.width(105.dp),
                            label = { Text("HideWord%", fontSize = 10.sp, maxLines = 1) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TooltipIconButton("Toggle Word Hiding", onClick = onToggleBlanking) {
                            val icon = if (isBlankingEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility
                            Icon(icon, contentDescription = null)
                        }
                        TooltipIconButton("Edit Blanking Script", onClick = { showBlankingScriptEditor = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                        TooltipIconButton("Edit Page Source", onClick = onEditPageSource) {
                            Text("</>", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        TooltipIconButton("Inject JS", onClick = { showScriptList = true }) {
                            Icon(Icons.Default.Build, contentDescription = null)
                        }
                    }

                    // Group 2: TTS
                    ToolbarGroup(label = "TTS") {
                        OutlinedTextField(
                            value = ttsDelay,
                            onValueChange = onTtsDelayChange,
                            modifier = Modifier.width(120.dp),
                            label = { Text("WaitForSecs", fontSize = 10.sp, maxLines = 1) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TooltipIconButton(
                            if (isTtsPlaying) "Stop TTS" else "Play TTS",
                            onClick = { if (isTtsPlaying) onTtsStop() else onTtsPlay() }
                        ) {
                            val icon = if (isTtsPlaying) Icons.Default.Close else Icons.Default.PlayArrow
                            val tint = if (isTtsPlaying) Color.Red else Color.Green
                            Icon(icon, contentDescription = null, tint = tint)
                        }
                        TooltipIconButton("Shuffle / Sequential", onClick = onToggleTtsRandom) {
                            val tint = if (isTtsRandom) Color(0xFFFBBC05) else MaterialTheme.colorScheme.onSurface
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = tint)
                        }
                        TooltipIconButton("TTS Settings", onClick = onOpenTtsSettings) {
                            Icon(Icons.Default.Settings, contentDescription = null)
                        }
                    }

                    // Group 3: Misc
                    ToolbarGroup(label = "Misc") {
                        TooltipIconButton("Dark Mode", onClick = onToggleDarkMode) {
                            Text(text = if (isDarkMode) "\u2600" else "\u263D", fontSize = 20.sp)
                        }
                        TooltipIconButton("Table of Contents", onClick = {
                            activeTab.showToc.value = !activeTab.showToc.value
                        }) {
                            Icon(
                                Icons.Default.List,
                                contentDescription = null,
                                tint = if (activeTab.showToc.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        TooltipIconButton("Zoom Out", onClick = onZoomOut) {
                            Text("-", fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                        TooltipIconButton("Zoom In", onClick = onZoomIn) {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                        TooltipIconButton("Tabs", onClick = { showTabList = true }) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(imageVector = Icons.Default.Menu, contentDescription = null)
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

    if (pageSourceToEdit != null) {
        PageSourceEditorDialog(
            sourceCode = pageSourceToEdit,
            onApply = onApplyPageSource,
            onDismiss = onDismissPageSourceEditor
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
                    Text(
                        text = item.text.ifBlank { item.id },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTocClick(item.id) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolbarGroup(
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp)
        )
        FlowRow(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            horizontalArrangement = Arrangement.Start,
            verticalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = onClick) {
            content()
        }
    }
}
