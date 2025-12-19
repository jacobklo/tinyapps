package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme
import java.io.File

class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val filePath = intent.getStringExtra("FILE_PATH")
        val file = if (filePath != null) File(filePath) else null

        setContent {
            AutoClickerTheme {
                if (file != null && file.exists()) {
                    EditorScreen(file = file, onBack = { finish() })
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("File not found")
                        Button(onClick = { finish() }) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(file: File, onBack: () -> Unit) {
    // Load initial state
    // We flatten the hierarchical structure for editing
    val initialInteractions = remember { 
        val loaded = RecordingManager.loadRecording(file)
        flatten(loaded)
    }
    val interactions = remember { mutableStateListOf<Interaction>().apply { addAll(initialInteractions) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        interactions.add(LoopStartInteraction(repeatCount = 1))
                    }) {
                        Text("Start For")
                    }
                    TextButton(onClick = {
                        interactions.add(LoopEndInteraction())
                    }) {
                        Text("End For")
                    }
                    IconButton(onClick = {
                        // Reconstruct hierarchy before saving
                        val hierarchy = buildHierarchy(interactions)
                        RecordingManager.saveRecordingToFile(file, hierarchy)
                        onBack()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            itemsIndexed(interactions) { index, interaction ->
                InteractionRow(
                    interaction = interaction,
                    onUpdate = { updated ->
                        interactions[index] = updated
                    },
                    onDelete = { interactions.removeAt(index) },
                    onMoveUp = {
                        if (index > 0) {
                            val prev = interactions[index - 1]
                            interactions[index - 1] = interaction
                            interactions[index] = prev
                        }
                    },
                    onMoveDown = {
                        if (index < interactions.size - 1) {
                            val next = interactions[index + 1]
                            interactions[index + 1] = interaction
                            interactions[index] = next
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun InteractionRow(
    interaction: Interaction,
    onUpdate: (Interaction) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Move Up/Down Controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 16.dp)
        ) {
            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
            }
            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
            }
        }
        
        Column(modifier = Modifier.weight(1f)) {
            when (interaction) {
                is ClickInteraction -> {
                    Text("Click", style = MaterialTheme.typography.titleMedium)
                    Text("Start: (${interaction.x.toInt()}, ${interaction.y.toInt()})")
                }
                is DragInteraction -> {
                    Text("Drag", style = MaterialTheme.typography.titleMedium)
                    if (interaction.points.isNotEmpty()) {
                        val start = interaction.points.first()
                        Text("Start: (${start.x.toInt()}, ${start.y.toInt()})")
                    } else {
                        Text("Start: (0, 0)")
                    }
                }
                is LoopStartInteraction -> {
                    Text("Start For Loop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = interaction.repeatCount.toString(),
                        onValueChange = {
                            val count = it.toIntOrNull() ?: 0
                            onUpdate(interaction.copy(repeatCount = count))
                        },
                        label = { Text("Loops") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(100.dp)
                    )
                }
                is LoopEndInteraction -> {
                    Text("End For Loop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                else -> {
                    Text("Unknown Interaction")
                }
            }
        }
        
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}

// Helper functions for Flattening / Unflattening

fun flatten(interactions: List<Interaction>): List<Interaction> {
    val flatList = mutableListOf<Interaction>()
    interactions.forEach { interaction ->
        if (interaction is ForLoopInteraction) {
            flatList.add(LoopStartInteraction(interaction.repeatCount, interaction.delayBefore))
            flatList.addAll(flatten(interaction.interactions))
            flatList.add(LoopEndInteraction(0))
        } else {
            flatList.add(interaction)
        }
    }
    return flatList
}

fun buildHierarchy(flatInteractions: List<Interaction>): List<Interaction> {
    val result = mutableListOf<Interaction>()
    var i = 0
    while (i < flatInteractions.size) {
        val item = flatInteractions[i]
        if (item is LoopStartInteraction) {
            val (children, nextIndex) = parseBlock(flatInteractions, i + 1)
            result.add(ForLoopInteraction(item.repeatCount, children, item.delayBefore))
            i = nextIndex
        } else if (item is LoopEndInteraction) {
            // Unmatched End - ignore
            i++
        } else {
            result.add(item)
            i++
        }
    }
    return result
}

fun parseBlock(flatInteractions: List<Interaction>, startIndex: Int): Pair<List<Interaction>, Int> {
    val children = mutableListOf<Interaction>()
    var i = startIndex
    while (i < flatInteractions.size) {
        val item = flatInteractions[i]
        if (item is LoopEndInteraction) {
            return children to (i + 1)
        } else if (item is LoopStartInteraction) {
            val (subChildren, nextIndex) = parseBlock(flatInteractions, i + 1)
            children.add(ForLoopInteraction(item.repeatCount, subChildren, item.delayBefore))
            i = nextIndex
        } else {
            children.add(item)
            i++
        }
    }
    return children to i // End of list reached without End tag
}
