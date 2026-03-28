package net.jacoblo.notesoutloud

import android.webkit.WebView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList

data class TocItem(val id: String, val text: String, val level: Int)

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
