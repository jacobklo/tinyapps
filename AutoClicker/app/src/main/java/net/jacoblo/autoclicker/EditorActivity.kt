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
    val recordingData = remember { RecordingManager.loadRecording(file) }
    // We flatten the hierarchical structure for editing
    val initialInteractions = remember {
        flatten(recordingData.events)
    }
    val interactions = remember { mutableStateListOf<Interaction>().apply { addAll(initialInteractions) } }

    // Global Random State
    var globalRandom by remember { mutableIntStateOf(recordingData.globalRandom) }

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
                        RecordingManager.saveRecordingToFile(file, hierarchy, globalRandom)
                        onBack()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            // Global Random Input Field
            OutlinedTextField(
                value = globalRandom.toString(),
                onValueChange = { globalRandom = it.toIntOrNull() ?: 0 },
                label = { Text("Global Random Delay (ms)") },
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
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
            .padding(8.dp), // Reduced padding
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Move Up/Down Controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
            }
            Spacer(modifier = Modifier.height(4.dp))
            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
            }
        }

        // Main Content Area - Inline
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Type and Info
            Column(modifier = Modifier.widthIn(max = 100.dp)) {
                when (interaction) {
                    is ClickInteraction -> {
                        Text("Click", style = MaterialTheme.typography.labelLarge)
                        Text("(${interaction.x.toInt()}, ${interaction.y.toInt()})", style = MaterialTheme.typography.bodyLarge)
                    }
                    is DragInteraction -> {
                        Text("Drag", style = MaterialTheme.typography.labelLarge)
                        if (interaction.points.isNotEmpty()) {
                            val start = interaction.points.first()
                            Text("(${start.x.toInt()}, ${start.y.toInt()})", style = MaterialTheme.typography.bodyLarge)
                        } else {
                            Text("(0,0)", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    is LoopStartInteraction -> {
                        Text("Start Loop", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    is LoopEndInteraction -> {
                        Text("End Loop", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    else -> {}
                }
            }

            // Loop Count Field (Only for LoopStart)
            if (interaction is LoopStartInteraction) {
                OutlinedTextField(
                    value = interaction.repeatCount.toString(),
                    onValueChange = {
                        val count = it.toIntOrNull() ?: 0
                        onUpdate(interaction.copy(repeatCount = count))
                    },
                    label = { Text("#") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(60.dp),
                    singleLine = true
                )
            }

            // Random Factor Fields
            when (interaction) {
                is ClickInteraction -> {
                    OutlinedTextField(
                        value = interaction.randomFactor.toString(),
                        onValueChange = {
                            val r = it.toIntOrNull() ?: 0
                            onUpdate(interaction.copy(randomFactor = r))
                        },
                        label = { Text("Rand") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                        singleLine = true
                    )
                }
                is DragInteraction -> {
                    OutlinedTextField(
                        value = interaction.randomFactorStart.toString(),
                        onValueChange = {
                            val r = it.toIntOrNull() ?: 0
                            onUpdate(interaction.copy(randomFactorStart = r))
                        },
                        label = { Text("R.Start") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = interaction.randomFactorHighest.toString(),
                        onValueChange = {
                            val r = it.toIntOrNull() ?: 0
                            onUpdate(interaction.copy(randomFactorHighest = r))
                        },
                        label = { Text("R.High") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                        singleLine = true
                    )
                }
                else -> {}
            }

            // Name Field
            OutlinedTextField(
                value = interaction.name,
                onValueChange = { newName ->
                    val updated = when (interaction) {
                        is ClickInteraction -> interaction.copy(name = newName)
                        is DragInteraction -> interaction.copy(name = newName)
                        is LoopStartInteraction -> interaction.copy(name = newName)
                        is LoopEndInteraction -> interaction.copy(name = newName)
                        // Should not happen as ForLoopInteraction is flattened
                        is ForLoopInteraction -> interaction.copy(name = newName)
                    }
                    onUpdate(updated)
                },
                label = { Text("Name") },
                modifier = Modifier.width(200.dp), // Reduced width to fit everything
                singleLine = true
            )
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
            flatList.add(LoopStartInteraction(interaction.repeatCount, interaction.delayBefore, interaction.name))
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
            result.add(ForLoopInteraction(item.repeatCount, children, item.delayBefore, item.name))
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
            children.add(ForLoopInteraction(item.repeatCount, subChildren, item.delayBefore, item.name))
            i = nextIndex
        } else {
            children.add(item)
            i++
        }
    }
    return children to i // End of list reached without End tag
}
