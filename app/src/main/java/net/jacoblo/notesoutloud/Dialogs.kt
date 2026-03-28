package net.jacoblo.notesoutloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun TabListDialog(
    tabs: List<BrowserTab>,
    onTabSelected: (Int) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
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
                        onDismiss()
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
                                    onDismiss()
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

@Composable
fun ScriptInjectionDialog(
    userScripts: List<UserScript>,
    onAddScript: (String) -> Unit,
    onRemoveScript: (UserScript) -> Unit,
    onDismiss: () -> Unit
) {
    var showAddScriptInput by remember { mutableStateOf(false) }
    var newScriptUrl by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
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

@Composable
fun PageSourceEditorDialog(
    sourceCode: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedSource by remember(sourceCode) { mutableStateOf(sourceCode) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Page Source", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = {
                        onApply(editedSource)
                        onDismiss()
                    }) {
                        Text("Apply")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editedSource,
                    onValueChange = { editedSource = it },
                    modifier = Modifier.fillMaxSize(),
                    singleLine = false
                )
            }
        }
    }
}

@Composable
fun BlankingScriptEditorDialog(
    blankingScriptContent: String,
    defaultBlankingScript: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedScript by remember(blankingScriptContent) { mutableStateOf(blankingScriptContent) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Blanking Script", style = MaterialTheme.typography.titleLarge)
                    Row {
                        Button(onClick = {
                            editedScript = defaultBlankingScript
                        }) {
                            Text("Reset")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            onSave(editedScript)
                            onDismiss()
                        }) {
                            Text("Save")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editedScript,
                    onValueChange = { editedScript = it },
                    modifier = Modifier.fillMaxSize(),
                    singleLine = false
                )
            }
        }
    }
}
