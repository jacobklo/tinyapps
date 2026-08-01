package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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

            // Block insertion lives here rather than in the app bar, where five
            // actions crowded the filename off the screen entirely.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { interactions.add(TextInteraction(text = "", delayBefore = 0)) },
                    label = { Text("+ Text") }
                )
                AssistChip(
                    onClick = { interactions.add(LoopStartInteraction(repeatCount = 1)) },
                    label = { Text("Start For") }
                )
                AssistChip(
                    onClick = { interactions.add(LoopEndInteraction()) },
                    label = { Text("End For") }
                )
                AssistChip(
                    onClick = { interactions.add(RandomSelectStartInteraction()) },
                    label = { Text("Start Rand") }
                )
                AssistChip(
                    onClick = { interactions.add(RandomSelectEndInteraction()) },
                    label = { Text("End Rand") }
                )
            }

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

@OptIn(ExperimentalLayoutApi::class)
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

        // Header line plus a wrapping field area. The fields no longer fit on
        // one line, and overflowing squeezed the Name box to a letter per line.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            // Type and Info
            Column(modifier = Modifier.weight(1f)) {
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
                    is TextInteraction -> {
                        Text("Text", style = MaterialTheme.typography.labelLarge)
                    }
                    is LoopStartInteraction -> {
                        Text("Start Loop", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    is LoopEndInteraction -> {
                        Text("End Loop", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    is RandomSelectStartInteraction -> {
                        Text("Start Rand", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    }
                    is RandomSelectEndInteraction -> {
                        Text("End Rand", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    }
                    else -> {}
                }
            }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
            // Wait before this action runs. The end markers are discarded when
            // the hierarchy is rebuilt, so a delay on them would go nowhere.
            if (interaction !is LoopEndInteraction && interaction !is RandomSelectEndInteraction) {
                OutlinedTextField(
                    value = interaction.delayBefore.toString(),
                    onValueChange = { onUpdate(interaction.withDelay(it.toLongOrNull() ?: 0L)) },
                    label = { Text("Wait ms") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(90.dp),
                    singleLine = true
                )
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
                is TextInteraction -> {
                    OutlinedTextField(
                        value = interaction.text,
                        onValueChange = { onUpdate(interaction.copy(text = it)) },
                        label = { Text("Text") },
                        modifier = Modifier.width(160.dp),
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
                        is TextInteraction -> interaction.copy(name = newName)
                        is LoopStartInteraction -> interaction.copy(name = newName)
                        is LoopEndInteraction -> interaction.copy(name = newName)
                        is RandomSelectStartInteraction -> interaction.copy(name = newName)
                        is RandomSelectEndInteraction -> interaction.copy(name = newName)
                        // Should not happen as nested types are flattened
                        is ForLoopInteraction -> interaction.copy(name = newName)
                        is RandomSelectInteraction -> interaction.copy(name = newName)
                    }
                    onUpdate(updated)
                },
                label = { Text("Name") },
                modifier = Modifier.width(180.dp),
                singleLine = true
            )
            }
        }
    }
}

/** delayBefore lives on every subclass separately, so copying needs a branch. */
fun Interaction.withDelay(delay: Long): Interaction = when (this) {
    is ClickInteraction -> copy(delayBefore = delay)
    is DragInteraction -> copy(delayBefore = delay)
    is TextInteraction -> copy(delayBefore = delay)
    is ForLoopInteraction -> copy(delayBefore = delay)
    is RandomSelectInteraction -> copy(delayBefore = delay)
    is LoopStartInteraction -> copy(delayBefore = delay)
    is LoopEndInteraction -> copy(delayBefore = delay)
    is RandomSelectStartInteraction -> copy(delayBefore = delay)
    is RandomSelectEndInteraction -> copy(delayBefore = delay)
}

// Helper functions for Flattening / Unflattening

fun flatten(interactions: List<Interaction>): List<Interaction> {
    val flatList = mutableListOf<Interaction>()
    interactions.forEach { interaction ->
        if (interaction is ForLoopInteraction) {
            flatList.add(LoopStartInteraction(interaction.repeatCount, interaction.delayBefore, interaction.name))
            flatList.addAll(flatten(interaction.interactions))
            flatList.add(LoopEndInteraction(0))
        } else if (interaction is RandomSelectInteraction) {
            flatList.add(RandomSelectStartInteraction(interaction.delayBefore, interaction.name))
            flatList.addAll(flatten(interaction.interactions))
            flatList.add(RandomSelectEndInteraction(0))
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
        } else if (item is RandomSelectStartInteraction) {
            val (children, nextIndex) = parseBlock(flatInteractions, i + 1)
            result.add(RandomSelectInteraction(children, item.delayBefore, item.name))
            i = nextIndex
        } else if (item is LoopEndInteraction || item is RandomSelectEndInteraction) {
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
        if (item is LoopEndInteraction || item is RandomSelectEndInteraction) {
            return children to (i + 1)
        } else if (item is LoopStartInteraction) {
            val (subChildren, nextIndex) = parseBlock(flatInteractions, i + 1)
            children.add(ForLoopInteraction(item.repeatCount, subChildren, item.delayBefore, item.name))
            i = nextIndex
        } else if (item is RandomSelectStartInteraction) {
            val (subChildren, nextIndex) = parseBlock(flatInteractions, i + 1)
            children.add(RandomSelectInteraction(subChildren, item.delayBefore, item.name))
            i = nextIndex
        } else {
            children.add(item)
            i++
        }
    }
    return children to i // End of list reached without End tag
}
