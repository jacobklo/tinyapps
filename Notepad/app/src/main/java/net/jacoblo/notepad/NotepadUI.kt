package net.jacoblo.notepad

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotepadApp(state: NotepadState) {
    val context = LocalContext.current
    
    var showFindDialog by remember { mutableStateOf(false) }
    var showSelectLinesDialog by remember { mutableStateOf(false) }

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris -> uris.forEach { state.loadFile(context, it) } }
    )

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                state.activeTab?.let { tab ->
                    saveContent(context, it, tab)
                }
            }
        }
    )

    Scaffold(
        topBar = {
            Column {
                NotepadTopBar(
                    state = state,
                    onNew = { state.addTab(EditorTab()) },
                    onOpen = { openFileLauncher.launch(arrayOf("*/*")) },
                    onSave = { 
                         state.activeTab?.let { tab ->
                             if (tab.uri != null) saveContent(context, tab.uri!!, tab)
                             else saveFileLauncher.launch(tab.title)
                         }
                    },
                    onSaveAs = { state.activeTab?.let { saveFileLauncher.launch(it.title) } },
                    onFind = { showFindDialog = true },
                    onSelectLines = { showSelectLinesDialog = true },
                    onUndo = { state.activeTab?.undo() },
                    onRedo = { state.activeTab?.redo() },
                    onIndent = { indentSelection(state.activeTab) },
                    onUnindent = { unindentSelection(state.activeTab) }
                )
                TabBar(state)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.tabs.isNotEmpty()) {
                state.activeTab?.let { tab ->
                    EditorArea(
                        tab = tab,
                        fontSize = state.fontSize,
                        darkMode = state.isDarkMode
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No file open")
                }
            }
        }
    }

    if (showFindDialog) {
        FindReplaceDialog(
            onDismiss = { showFindDialog = false },
            onFind = { term -> findText(state.activeTab, term) },
            onReplace = { term, replacement -> replaceText(state.activeTab, term, replacement) },
            onReplaceAll = { term, replacement -> replaceAllText(state.activeTab, term, replacement) }
        )
    }

    if (showSelectLinesDialog) {
        SelectLinesDialog(
            onDismiss = { showSelectLinesDialog = false },
            onSelect = { start, end -> 
                selectLines(state.activeTab, start, end)
                showSelectLinesDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotepadTopBar(
    state: NotepadState,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onFind: () -> Unit,
    onSelectLines: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onIndent: () -> Unit,
    onUnindent: () -> Unit
) {
    TopAppBar(
        title = { Text("Notepad") },
        actions = {
            IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New") }
            IconButton(onClick = onOpen) { Icon(Icons.Default.FolderOpen, "Open") }
            IconButton(onClick = onSave) { Icon(Icons.Default.Save, "Save") }
            IconButton(onClick = onSaveAs) { Icon(Icons.Default.SaveAs, "Save As") }
            
            IconButton(onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = onRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            
            IconButton(onClick = onFind) { Icon(Icons.Default.Search, "Find") }
            IconButton(onClick = onSelectLines) { Icon(Icons.Default.FormatLineSpacing, "Select Lines") }
            
            IconButton(onClick = onIndent) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent") }
            IconButton(onClick = onUnindent) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Unindent") }

            IconButton(onClick = { state.fontSize += 2f }) { Icon(Icons.Default.ZoomIn, "Zoom In") }
            IconButton(onClick = { if (state.fontSize > 8f) state.fontSize -= 2f }) { Icon(Icons.Default.ZoomOut, "Zoom Out") }

            IconButton(onClick = { state.isDarkMode = !state.isDarkMode }) {
                Icon(if (state.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle Theme")
            }
        }
    )
}

@Composable
fun TabBar(state: NotepadState) {
    // Prevent rendering TabRow when there are no tabs to avoid IndexOutOfBoundsException
    // because ScrollableTabRow expects at least one tab if selectedTabIndex is 0.
    if (state.tabs.isEmpty()) return

    ScrollableTabRow(selectedTabIndex = state.activeTabIndex) {
        state.tabs.forEachIndexed { index, tab ->
            Tab(
                selected = state.activeTabIndex == index,
                onClick = { state.activeTabIndex = index },
                text = { Text(tab.title) },
                icon = {
                    Icon(
                        Icons.Default.Close, 
                        "Close", 
                        modifier = Modifier.clickable { state.closeTab(index) }.size(16.dp)
                    )
                }
            )
        }
    }
}

@Composable
fun EditorArea(tab: EditorTab, fontSize: Float, darkMode: Boolean) {
    val scrollState = rememberScrollState()
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        color = if (darkMode) Color.White else Color.Black
    )
    
    val lineCount = tab.content.text.count { it == '\n' } + 1
    val lineNumbers = (1..lineCount).joinToString("\n")

    Row(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Text(
            text = lineNumbers,
            style = textStyle.copy(color = Color.Gray, textAlign = TextAlign.End),
            modifier = Modifier
                .width(40.dp)
                .background(if (darkMode) Color.DarkGray else Color.LightGray)
                .padding(end = 4.dp)
        )
        
        BasicTextField(
            value = tab.content,
            onValueChange = { tab.updateContent(it) },
            textStyle = textStyle,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            cursorBrush = SolidColor(if (darkMode) Color.White else Color.Black)
        )
    }
}

@Composable
fun FindReplaceDialog(onDismiss: () -> Unit, onFind: (String) -> Unit, onReplace: (String, String) -> Unit, onReplaceAll: (String, String) -> Unit) {
    var findTerm by remember { mutableStateOf("") }
    var replaceTerm by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find & Replace") },
        text = {
            Column {
                OutlinedTextField(value = findTerm, onValueChange = { findTerm = it }, label = { Text("Find") })
                OutlinedTextField(value = replaceTerm, onValueChange = { replaceTerm = it }, label = { Text("Replace") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onFind(findTerm) }) { Text("Find") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
            TextButton(onClick = { onReplace(findTerm, replaceTerm) }) { Text("Replace") }
            TextButton(onClick = { onReplaceAll(findTerm, replaceTerm) }) { Text("Replace All") }
        }
    )
}

@Composable
fun SelectLinesDialog(onDismiss: () -> Unit, onSelect: (Int, Int) -> Unit) {
    var startLine by remember { mutableStateOf("") }
    var endLine by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Lines") },
        text = {
            Column {
                OutlinedTextField(
                    value = startLine, 
                    onValueChange = { startLine = it }, 
                    label = { Text("Start Line") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = endLine, 
                    onValueChange = { endLine = it }, 
                    label = { Text("End Line") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { 
                val start = startLine.toIntOrNull() ?: 1
                val end = endLine.toIntOrNull() ?: start
                onSelect(start, end)
            }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


fun saveContent(context: Context, uri: Uri, tab: EditorTab) {
    try {
        val outputStream = context.contentResolver.openOutputStream(uri, "wt")
        val writer = BufferedWriter(OutputStreamWriter(outputStream))
        val textToSave = if (tab.lineEnding == LineEnding.CRLF) {
            tab.content.text.replace("\n", "\r\n")
        } else {
            tab.content.text
        }
        writer.write(textToSave)
        writer.close()
        outputStream?.close()
        tab.uri = uri
        tab.title = uri.path?.substringAfterLast('/') ?: "Saved"
        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun findText(tab: EditorTab?, term: String) {
    if (tab == null || term.isEmpty()) return
    val text = tab.content.text
    val index = text.indexOf(term, tab.content.selection.end)
    if (index != -1) {
        tab.content = tab.content.copy(selection = TextRange(index, index + term.length))
    } else {
        val wrapIndex = text.indexOf(term)
        if (wrapIndex != -1) {
             tab.content = tab.content.copy(selection = TextRange(wrapIndex, wrapIndex + term.length))
        }
    }
}

fun replaceText(tab: EditorTab?, term: String, replacement: String) {
    if (tab == null || term.isEmpty()) return
    val text = tab.content.text
    val selection = tab.content.selection
    val selectedText = if (selection.collapsed) "" else text.substring(selection.start, selection.end)
    if (selectedText == term) {
        val newText = text.replaceRange(selection.start, selection.end, replacement)
        tab.updateContent(TextFieldValue(newText, TextRange(selection.start + replacement.length)))
    } else {
        findText(tab, term)
    }
}

fun replaceAllText(tab: EditorTab?, term: String, replacement: String) {
    if (tab == null || term.isEmpty()) return
    val newText = tab.content.text.replace(term, replacement)
    tab.updateContent(TextFieldValue(newText))
}

fun selectLines(tab: EditorTab?, startLine: Int, endLine: Int) {
    if (tab == null) return
    val text = tab.content.text
    val lines = text.lines()
    
    // Map lines to indices
    var currentIndex = 0
    var startIndex = -1
    var endIndex = -1
    
    // Adjust 1-based to 0-based
    val s = (startLine - 1).coerceAtLeast(0)
    val e = (endLine - 1).coerceAtMost(lines.lastIndex)
    
    if (s > e) return

    for (i in lines.indices) {
        if (i == s) startIndex = currentIndex
        val lineLen = lines[i].length + 1 // +1 for newline character assumption (simplified)
        // Wait, text.lines() splits by \n. 
        if (i == e) {
             endIndex = currentIndex + lines[i].length
             break
        }
        currentIndex += lineLen
    }
    
    // Refined logic for accurate indices using loop
    var pos = 0
    var startPos = 0
    var endPos = 0
    
    // We iterate to find start of startLine and end of endLine
    for (i in 0..e) {
        if (i < lines.size) {
            val len = lines[i].length
            if (i == s) startPos = pos
            if (i == e) endPos = pos + len
            pos += len + 1 // +1 for \n
        }
    }
    
    if (endPos > text.length) endPos = text.length
    
    tab.content = tab.content.copy(selection = TextRange(startPos, endPos))
}


fun indentSelection(tab: EditorTab?) {
    if (tab == null) return
    val text = tab.content.text
    val selection = tab.content.selection
    
    val beforeSelection = text.substring(0, selection.min)
    val afterSelection = text.substring(selection.max)
    val selectedText = text.substring(selection.min, selection.max)
    
    if (selection.collapsed) {
         tab.updateContent(TextFieldValue(
             text = text.substring(0, selection.start) + "\t" + text.substring(selection.end),
             selection = TextRange(selection.start + 1)
         ))
         return
    }

    val lastNewLineBefore = beforeSelection.lastIndexOf('\n')
    val startOfFirstLine = if (lastNewLineBefore == -1) 0 else lastNewLineBefore + 1
    
    val prefix = beforeSelection.substring(startOfFirstLine)
    val fullTextToProcess = prefix + selectedText
    val indentedText = fullTextToProcess.lines().joinToString("\n") { "\t$it" }
    
    val newText = beforeSelection.substring(0, startOfFirstLine) + indentedText + afterSelection
    
    tab.updateContent(TextFieldValue(
        text = newText,
        selection = TextRange(startOfFirstLine, startOfFirstLine + indentedText.length)
    ))
}

fun unindentSelection(tab: EditorTab?) {
    if (tab == null) return
    val text = tab.content.text
    val selection = tab.content.selection
    
    val beforeSelection = text.substring(0, selection.min)
    val afterSelection = text.substring(selection.max)
    val selectedText = text.substring(selection.min, selection.max)

    val lastNewLineBefore = beforeSelection.lastIndexOf('\n')
    val startOfFirstLine = if (lastNewLineBefore == -1) 0 else lastNewLineBefore + 1
    
    val prefix = beforeSelection.substring(startOfFirstLine)
    val fullTextToProcess = prefix + selectedText
    val unindentedText = fullTextToProcess.lines().joinToString("\n") { 
        if (it.startsWith("\t")) it.substring(1) 
        else if (it.startsWith("    ")) it.substring(4) 
        else it
    }
    
    val newText = beforeSelection.substring(0, startOfFirstLine) + unindentedText + afterSelection
    
    tab.updateContent(TextFieldValue(
        text = newText,
        selection = TextRange(startOfFirstLine, startOfFirstLine + unindentedText.length)
    ))
}