package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private val INDENT_PER_LEVEL = 20.dp

/** An interaction plus a stable id, so reordering can key rows by identity. */
private data class EditorRow(val id: Long, val interaction: Interaction)

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
    val recordingData = remember { RecordingManager.loadRecording(file) }
    // The hierarchy is flattened for editing and rebuilt on save.
    val initialInteractions = remember { flatten(recordingData.events) }
    // Rows carry an id because reordering needs stable identity: keying by
    // position would attach a row's field state to the slot, not the item.
    val rows = remember {
        mutableStateListOf<EditorRow>().apply {
            initialInteractions.forEachIndexed { index, item -> add(EditorRow(index.toLong(), item)) }
        }
    }
    var nextRowId by remember { mutableLongStateOf(initialInteractions.size.toLong()) }

    var globalRandom by remember { mutableIntStateOf(recordingData.globalRandom) }
    // Only one row is expanded at a time; collapsed rows are a single summary line.
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val interactions = rows.map { it.interaction }
    val dirty = interactions != initialInteractions || globalRandom != recordingData.globalRandom
    val depths = blockDepths(interactions)

    fun add(interaction: Interaction) {
        rows.add(EditorRow(nextRowId++, interaction))
    }

    fun save() {
        RecordingManager.saveRecordingToFile(file, buildHierarchy(interactions), globalRandom)
        onBack()
    }

    fun leave() {
        if (dirty) confirmDiscard = true else onBack()
    }

    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.nameWithoutExtension) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            NumberField(
                value = globalRandom.toLong(),
                onValueChange = { globalRandom = it.toInt() },
                label = "Global Random Delay (ms)",
                modifier = Modifier.padding(8.dp).fillMaxWidth()
            )

            // Blocks are inserted as a matched pair. Adding the two ends
            // separately made it easy to leave them unbalanced, and an
            // unmatched End is silently dropped when the hierarchy is rebuilt.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { add(TextInteraction(text = "", delayBefore = 0)) },
                    label = { Text("+ Text") }
                )
                AssistChip(
                    onClick = {
                        add(LoopStartInteraction(repeatCount = 2))
                        add(LoopEndInteraction())
                    },
                    label = { Text("+ Repeat block") }
                )
                AssistChip(
                    onClick = {
                        add(RandomSelectStartInteraction())
                        add(RandomSelectEndInteraction())
                    },
                    label = { Text("+ Random block") }
                )
            }

            if (!isBalanced(interactions)) {
                Text(
                    "Unbalanced blocks: every Repeat/Random needs a matching End, or the extra one is dropped on save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            val listState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(listState) { from, to ->
                rows.add(to.index, rows.removeAt(from.index))
                expandedId = null
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    ReorderableItem(reorderState, key = row.id) { dragging ->
                        InteractionRow(
                            interaction = row.interaction,
                            depth = depths.getOrElse(index) { 0 },
                            expanded = expandedId == row.id,
                            dragging = dragging,
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .draggableHandle(onDragStarted = { expandedId = null })
                                        .size(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Reorder"
                                    )
                                }
                            },
                            onToggleExpand = {
                                expandedId = if (expandedId == row.id) null else row.id
                            },
                            onUpdate = { updated -> rows[index] = row.copy(interaction = updated) },
                            onDelete = {
                                rows.removeAt(index)
                                expandedId = null
                            },
                            // Long-pressing the row drags it too. The handle
                            // alone was a 28dp target at the screen edge, so a
                            // finger that missed it just scrolled or expanded.
                            modifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { expandedId = null }
                            )
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this recording have not been saved.") },
            confirmButton = {
                Button(onClick = {
                    confirmDiscard = false
                    onBack()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text("Keep editing")
                }
            }
        )
    }
}

@Composable
fun InteractionRow(
    interaction: Interaction,
    depth: Int,
    expanded: Boolean,
    dragging: Boolean,
    dragHandle: @Composable () -> Unit,
    onToggleExpand: () -> Unit,
    onUpdate: (Interaction) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = blockAccent(depth)
    val screen = remember { ScreenGeometry.current(AppSettings.appContext) }
    val background =
        if (dragging) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .height(IntrinsicSize.Min)
    ) {
        // Indent rails make the nesting visible; the flat Start/End markers
        // gave no indication of what was inside a block.
        repeat(depth) { level ->
            Box(
                modifier = Modifier
                    .padding(start = INDENT_PER_LEVEL)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(blockAccent(level).copy(alpha = 0.4f))
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onToggleExpand() }
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = describeInteraction(interaction, screen),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (interaction.isBlockMarker()) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
                dragHandle()
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                InteractionFields(interaction = interaction, onUpdate = onUpdate)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InteractionFields(interaction: Interaction, onUpdate: (Interaction) -> Unit) {
    // Coordinates are stored as fractions of the screen so scripts stay
    // portable, but they are shown and edited as pixels for this display.
    val screen = remember { ScreenGeometry.current(AppSettings.appContext) }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // End markers are discarded when the hierarchy is rebuilt, so a delay
        // on them would go nowhere.
        if (!interaction.isBlockEnd()) {
            NumberField(
                value = interaction.delayBefore,
                onValueChange = { onUpdate(interaction.withDelay(it)) },
                label = "Wait ms",
                modifier = Modifier.width(100.dp)
            )
        }

        when (interaction) {
            is ClickInteraction -> {
                NumberField(
                    value = (interaction.x * screen.width).toLong(),
                    onValueChange = { onUpdate(interaction.copy(x = it / screen.width.toFloat())) },
                    label = "X px",
                    modifier = Modifier.width(90.dp)
                )
                NumberField(
                    value = (interaction.y * screen.height).toLong(),
                    onValueChange = { onUpdate(interaction.copy(y = it / screen.height.toFloat())) },
                    label = "Y px",
                    modifier = Modifier.width(90.dp)
                )
                NumberField(
                    value = interaction.duration,
                    onValueChange = { onUpdate(interaction.copy(duration = it)) },
                    label = "Hold ms",
                    modifier = Modifier.width(100.dp)
                )
                NumberField(
                    value = interaction.randomFactor.toLong(),
                    onValueChange = { onUpdate(interaction.copy(randomFactor = it.toInt())) },
                    label = "Rand px",
                    modifier = Modifier.width(100.dp)
                )
            }
            is DragInteraction -> {
                val start = interaction.points.firstOrNull()
                if (start != null) {
                    // Editing the start translates the whole path, which is what
                    // you want when a recorded gesture landed slightly off.
                    NumberField(
                        value = (start.x * screen.width).toLong(),
                        onValueChange = {
                            onUpdate(interaction.translatedTo(it / screen.width.toFloat(), start.y))
                        },
                        label = "Start X px",
                        modifier = Modifier.width(110.dp)
                    )
                    NumberField(
                        value = (start.y * screen.height).toLong(),
                        onValueChange = {
                            onUpdate(interaction.translatedTo(start.x, it / screen.height.toFloat()))
                        },
                        label = "Start Y px",
                        modifier = Modifier.width(110.dp)
                    )
                }
                NumberField(
                    value = interaction.randomFactorStart.toLong(),
                    onValueChange = { onUpdate(interaction.copy(randomFactorStart = it.toInt())) },
                    label = "Rand start",
                    modifier = Modifier.width(110.dp)
                )
                NumberField(
                    value = interaction.randomFactorHighest.toLong(),
                    onValueChange = { onUpdate(interaction.copy(randomFactorHighest = it.toInt())) },
                    label = "Rand mid",
                    modifier = Modifier.width(110.dp)
                )
            }
            is TextInteraction -> {
                OutlinedTextField(
                    value = interaction.text,
                    onValueChange = { onUpdate(interaction.copy(text = it)) },
                    label = { Text("Text") },
                    modifier = Modifier.width(220.dp),
                    singleLine = true
                )
            }
            is LoopStartInteraction -> {
                NumberField(
                    value = interaction.repeatCount.toLong(),
                    onValueChange = { onUpdate(interaction.copy(repeatCount = it.toInt())) },
                    label = "Repeat",
                    modifier = Modifier.width(100.dp)
                )
            }
            else -> {}
        }

        OutlinedTextField(
            value = interaction.name,
            onValueChange = { onUpdate(interaction.withName(it)) },
            label = { Text("Name") },
            modifier = Modifier.width(180.dp),
            singleLine = true
        )
    }
}

/**
 * Numeric field that keeps its own text while being edited.
 *
 * Parsing straight into the model meant clearing the box snapped it back to
 * "0", so you could never delete the leading digit and retype.
 */
@Composable
private fun NumberField(
    value: Long,
    onValueChange: (Long) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(value.toString()) }
    // Resync when the value changed from outside this field (row reuse, reorder).
    if ((text.toLongOrNull() ?: 0L) != value) {
        text = value.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.take(9)
            text = digits
            onValueChange(digits.toLongOrNull() ?: 0L)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
        singleLine = true
    )
}

// ---------------------------------------------------------------------------
// Row presentation helpers
// ---------------------------------------------------------------------------

@Composable
private fun blockAccent(depth: Int): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary
    )
    return palette[depth % palette.size]
}

private fun Interaction.isBlockMarker(): Boolean =
    this is LoopStartInteraction || this is LoopEndInteraction ||
        this is RandomSelectStartInteraction || this is RandomSelectEndInteraction

private fun Interaction.isBlockEnd(): Boolean =
    this is LoopEndInteraction || this is RandomSelectEndInteraction

private fun describeInteraction(interaction: Interaction, screen: ScreenGeometry): String {
    fun px(x: Float, y: Float) = "(${(x * screen.width).toInt()}, ${(y * screen.height).toInt()})"

    val wait = if (interaction.delayBefore > 0) "wait ${interaction.delayBefore}ms  " else ""
    val label = when (interaction) {
        is ClickInteraction ->
            "Click ${px(interaction.x, interaction.y)}  ${interaction.duration}ms"
        is DragInteraction -> {
            val start = interaction.points.firstOrNull()
            val end = interaction.points.lastOrNull()
            if (start == null || end == null) {
                "Drag (empty)"
            } else {
                "Drag ${px(start.x, start.y)} to ${px(end.x, end.y)}  ${interaction.points.size} pts"
            }
        }
        is TextInteraction ->
            if (interaction.text.isBlank()) "Text (empty)" else "Text \"${interaction.text}\""
        is LoopStartInteraction -> "Repeat ${interaction.repeatCount}x"
        is LoopEndInteraction -> "End repeat"
        is RandomSelectStartInteraction -> "Random one of"
        is RandomSelectEndInteraction -> "End random"
        is ForLoopInteraction -> "Repeat ${interaction.repeatCount}x"
        is RandomSelectInteraction -> "Random one of"
    }
    val name = if (interaction.name.isBlank()) "" else "  -  ${interaction.name}"
    return "$wait$label$name"
}

/** Indent level of each row, so nested blocks can be drawn as nested. */
fun blockDepths(items: List<Interaction>): List<Int> {
    var depth = 0
    return items.map { item ->
        when (item) {
            is LoopStartInteraction, is RandomSelectStartInteraction -> depth++
            is LoopEndInteraction, is RandomSelectEndInteraction -> {
                depth = (depth - 1).coerceAtLeast(0)
                depth
            }
            else -> depth
        }
    }
}

fun isBalanced(items: List<Interaction>): Boolean {
    var depth = 0
    items.forEach { item ->
        when (item) {
            is LoopStartInteraction, is RandomSelectStartInteraction -> depth++
            is LoopEndInteraction, is RandomSelectEndInteraction -> {
                depth--
                if (depth < 0) return false
            }
            else -> {}
        }
    }
    return depth == 0
}

// ---------------------------------------------------------------------------
// Interaction copy helpers -- every subclass carries its own fields
// ---------------------------------------------------------------------------

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

fun Interaction.withName(newName: String): Interaction = when (this) {
    is ClickInteraction -> copy(name = newName)
    is DragInteraction -> copy(name = newName)
    is TextInteraction -> copy(name = newName)
    is ForLoopInteraction -> copy(name = newName)
    is RandomSelectInteraction -> copy(name = newName)
    is LoopStartInteraction -> copy(name = newName)
    is LoopEndInteraction -> copy(name = newName)
    is RandomSelectStartInteraction -> copy(name = newName)
    is RandomSelectEndInteraction -> copy(name = newName)
}

/** Moves the whole path so its first point lands on the given coordinates. */
fun DragInteraction.translatedTo(x: Float, y: Float): DragInteraction {
    val start = points.firstOrNull() ?: return this
    val dx = x - start.x
    val dy = y - start.y
    return copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
}

// ---------------------------------------------------------------------------
// Flattening / Unflattening
// ---------------------------------------------------------------------------

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
