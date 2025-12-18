package net.jacoblo.notepad

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LineEnding(val value: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF")
}

class EditorTab(
    val id: Long = System.currentTimeMillis(),
    initialContent: String = "",
    var title: String = "Untitled",
    var uri: Uri? = null,
    var lineEnding: LineEnding = LineEnding.LF
) {
    var content by mutableStateOf(TextFieldValue(initialContent))
    // We use a simplified undo/redo stack
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    
    fun updateContent(newValue: TextFieldValue, pushUndo: Boolean = true) {
        if (pushUndo && (undoStack.isEmpty() || undoStack.last().text != content.text)) {
            undoStack.addLast(content)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
        }
        content = newValue
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.addLast(content)
            content = undoStack.removeLast()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.addLast(content)
            content = redoStack.removeLast()
        }
    }
}

class NotepadState {
    val tabs = mutableStateListOf<EditorTab>()
    var activeTabIndex by mutableStateOf(0)
    var isDarkMode by mutableStateOf(true) 
    var fontSize by mutableStateOf(14f)

    val activeTab: EditorTab?
        get() = tabs.getOrNull(activeTabIndex)

    fun addTab(tab: EditorTab) {
        tabs.add(tab)
        activeTabIndex = tabs.lastIndex
    }

    fun closeTab(index: Int) {
        if (index in tabs.indices) {
            tabs.removeAt(index)
            if (activeTabIndex >= tabs.size) {
                activeTabIndex = (tabs.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun loadFile(context: Context, uri: Uri) {
        // Take persistable permission if possible, to allow future access
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Already taken or not persistable
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val sb = StringBuilder()
                var line: String?
                // Read line by line
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                reader.close()
                inputStream?.close()

                val fileName = uri.path?.substringAfterLast('/') ?: "Unknown"
                
                withContext(Dispatchers.Main) {
                    // Check if tab with this URI already exists
                    val existingTab = tabs.indexOfFirst { it.uri == uri }
                    if (existingTab != -1) {
                         activeTabIndex = existingTab
                         return@withContext
                    }
                    
                    addTab(EditorTab(
                        initialContent = sb.toString(),
                        title = fileName,
                        uri = uri
                    ))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
