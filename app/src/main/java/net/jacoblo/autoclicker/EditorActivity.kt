package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

private val INDENT_PER_LEVEL = 20.dp

/**
 * Something the editor can insert. Blocks return both markers, because adding
 * the two ends separately made it easy to leave them unbalanced and an
 * unmatched End is dropped when the hierarchy is rebuilt.
 */
private class StepOption(val label: String, val create: () -> List<Interaction>)

// New gestures start in the middle of the screen, which is always somewhere
// real; a corner default would look like the step was broken.
private const val NEW_GESTURE_MS = 300L

private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) = listOf(
    DragInteraction(
        points = listOf(DragPoint(fromX, fromY, 0), DragPoint(toX, toY, NEW_GESTURE_MS)),
        delayBefore = 0
    )
)

private val STEP_OPTIONS = listOf(
    StepOption("Tap") { listOf(ClickInteraction(0.5f, 0.5f, duration = 50, delayBefore = 0)) },
    StepOption("Double tap") {
        listOf(ClickInteraction(0.5f, 0.5f, duration = 50, taps = 2, delayBefore = 0))
    },
    StepOption("Long press") { listOf(ClickInteraction(0.5f, 0.5f, duration = 800, delayBefore = 0)) },
    // Named by which way the finger travels, because "scroll down" means the
    // opposite thing to different people.
    StepOption("Swipe up") { swipe(0.5f, 0.7f, 0.5f, 0.3f) },
    StepOption("Swipe down") { swipe(0.5f, 0.3f, 0.5f, 0.7f) },
    StepOption("Swipe left") { swipe(0.7f, 0.5f, 0.3f, 0.5f) },
    StepOption("Swipe right") { swipe(0.3f, 0.5f, 0.7f, 0.5f) },
    StepOption("Wait") { listOf(WaitInteraction(delayBefore = 1000)) },
    StepOption("Toast") { listOf(ToastInteraction("", 0)) },
    StepOption("Text input") { listOf(TextInteraction(text = "", delayBefore = 0)) },
    StepOption("Set variable") { listOf(SetVariableInteraction("count", "0", 0)) },
    StepOption("Wait for code") {
        listOf(
            WaitCodeInteraction(
                variable = "codes",
                maxAgeSeconds = DEFAULT_CODE_MAX_AGE_S,
                timeoutMs = DEFAULT_CODE_TIMEOUT_MS,
                delayBefore = 0
            )
        )
    },
    StepOption("Key event") { listOf(KeyEventInteraction("BACK", 0)) },
    StepOption("Launch app") { listOf(LaunchAppInteraction("", 0)) },
    StepOption("Shell command") { listOf(ShellInteraction("", 0)) },
    StepOption("Repeat block") { listOf(LoopStartInteraction(repeatCount = 2), LoopEndInteraction()) },
    StepOption("While block") { listOf(WhileStartInteraction("1 == 1"), WhileEndInteraction()) },
    StepOption("If block") { listOf(IfStartInteraction("1 == 1"), IfEndInteraction()) },
    StepOption("Else if") { listOf(ElseIfInteraction("1 == 1")) },
    StepOption("Else") { listOf(ElseInteraction()) },
    StepOption("Random block") { listOf(RandomSelectStartInteraction(), RandomSelectEndInteraction()) },
    StepOption("Break") { listOf(BreakInteraction()) }
)

/**
 * What a step does and what can be typed into it, shown while it is expanded.
 *
 * Conditions, expressions and key names are the parts that cannot be guessed
 * from the field labels alone, so the examples concentrate there.
 */
private class StepHelp(val summary: String, val examples: List<String> = emptyList())

// Shared by every gesture, since the anchor works the same way for all of them.
private const val RELATIVE_HELP =
    "Relative to picks what the coordinates are measured from. On Screen they " +
        "are a place on the display; on a saved area they are pixels from " +
        "wherever that area is found when the step runs, and may be negative. " +
        "The area is searched for each time, which needs root and costs about " +
        "half a second, and the gesture is skipped if it is not on screen."

private fun helpFor(interaction: Interaction): StepHelp = when (interaction) {
    is ClickInteraction -> StepHelp(
        "Taps one point. Hold ms is how long the finger stays down, so 800 or " +
            "more is a long press; Taps repeats the press, so 2 is a double " +
            "tap. Rand px scatters the point a little on every replay. " +
            RELATIVE_HELP,
        listOf(
            "Hold 50, Taps 1     a normal tap",
            "Hold 50, Taps 2     a double tap",
            "Hold 800, Taps 1    a long press",
            "Relative to \"calc\", dX 20, dY 15    inside the found image",
            "Relative to \"calc\", dX 0, dY -60    above it"
        )
    )

    is DragInteraction -> StepHelp(
        "Swipes from one point to another. A swipe up scrolls the page down. " +
            "Swipe ms is how long the finger takes to travel -- slower reads as " +
            "a drag, faster as a fling. Rand start scatters the ends, Rand mid " +
            "the middle. A recorded swipe keeps its original path, pressure and " +
            "timing, and only its start can be moved. " + RELATIVE_HELP
    )

    is TextInteraction -> StepHelp(
        "Types into whatever field currently has focus. Tap the field first " +
            "with a Click step. Plain text is typed as written; anything in " +
            "braces is worked out first, which is how a code that was looked up " +
            "gets typed.",
        listOf(
            "hello world",
            "{codes[0]}",
            "user{count}@example.com"
        )
    )

    is WaitInteraction -> StepHelp("Pauses. The Wait ms field is the whole action.")

    is ToastInteraction -> StepHelp(
        "Shows a short message on screen. Plain text is shown as written; " +
            "anything in braces is worked out first.",
        listOf(
            "Finished",
            "attempt {count} of 3",
            "{total - done} left to go"
        )
    )

    is SetVariableInteraction -> StepHelp(
        "Stores a value under a name for the rest of this run. Variables start " +
            "at 0 and are forgotten when playback ends.",
        listOf(
            "0",
            "count + 1",
            "random(2, 5)",
            "\"page \" + count",
            "count % 3"
        )
    )

    is WaitCodeInteraction -> StepHelp(
        "Waits for six-digit verification codes from the gmail-six-digit " +
            "service, then stores them in the variable as a list, best guess " +
            "first. Max age s is what makes it a wait: the service keeps ten " +
            "minutes of history, so without it you would get the code from your " +
            "last login straight away. Set the service address in Settings. If " +
            "nothing arrives before the timeout the variable is left empty and " +
            "an error is shown.",
        listOf(
            "{codes[0]}          in a Text step, types the best code",
            "count(codes)        how many arrived",
            "count(codes) == 0   nothing came, use in an If",
            "{codes}             all of them, for a Toast"
        )
    )

    is KeyEventInteraction -> StepHelp(
        "Presses a system or hardware key, by its Android key name.",
        listOf(
            "BACK",
            "HOME",
            "APP_SWITCH  (recent apps)",
            "ENTER, TAB, DEL",
            "VOLUME_UP, VOLUME_DOWN, POWER"
        )
    )

    is LaunchAppInteraction -> StepHelp(
        "Opens an app by package name. Find one with a Shell step running " +
            "\"pm list packages\".",
        listOf(
            "com.android.settings",
            "com.android.chrome"
        )
    )

    is ShellInteraction -> StepHelp(
        "Runs a command as root. Output is not shown, only logged.",
        listOf(
            "am force-stop com.example.app",
            "pm clear com.example.app",
            "svc wifi disable"
        )
    )

    is IfStartInteraction, is ElseIfInteraction, is WhileStartInteraction -> StepHelp(
        "Runs the steps inside when the condition holds. While repeats them " +
            "for as long as it holds. Compare with == != < > <= >=, combine " +
            "with && || !, and ask about the screen with image() or " +
            "waitImage(), which waits up to the given milliseconds.",
        listOf(
            "count < 3",
            "image(\"start_button\")",
            "waitImage(\"popup\", 5000)",
            "!image(\"error\") && count > 0",
            "image(\"button\", 95)   (needs 95% match)"
        )
    )

    is LoopStartInteraction -> StepHelp(
        "Repeats the steps inside a set number of times. Set the count to 0 to " +
            "repeat until Break or the stop button."
    )

    is RandomSelectStartInteraction -> StepHelp(
        "Runs exactly one of the steps inside, chosen at random each time."
    )

    is BreakInteraction -> StepHelp("Leaves the innermost Repeat or While immediately.")

    is ElseInteraction -> StepHelp("Runs when none of the conditions above it held.")

    else -> StepHelp("Marks the end of the block above it.")
}

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
    var stepToAdd by remember { mutableStateOf(STEP_OPTIONS.first()) }
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

            // One row: the global delay, a picker for what to insert, and the
            // button that inserts it. Fourteen chips in a horizontally scrolling
            // strip hid most of the options off the right edge.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberField(
                    value = globalRandom.toLong(),
                    onValueChange = { globalRandom = it.toInt() },
                    label = "Rand ms",
                    modifier = Modifier.width(110.dp)
                )

                StepPicker(
                    selected = stepToAdd,
                    onSelected = { stepToAdd = it },
                    modifier = Modifier.weight(1f)
                )

                FilledIconButton(onClick = { stepToAdd.create().forEach { add(it) } }) {
                    Icon(Icons.Default.Add, contentDescription = "Add step")
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepPicker(
    selected: StepOption,
    onSelected: (StepOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Add step") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            STEP_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
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
                Spacer(modifier = Modifier.height(8.dp))
                StepHelpPanel(helpFor(interaction))
            }
        }
    }
}

@Composable
private fun StepHelpPanel(help: StepHelp) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = help.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (help.examples.isEmpty()) return@Column

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Examples",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            help.examples.forEach { example ->
                Text(
                    text = example,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp)
                )
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
                val anchored = interaction.anchor.isNotBlank()
                AnchorPicker(
                    selected = interaction.anchor,
                    onSelected = { onUpdate(interaction.copy(anchor = it)) }
                )
                CoordField(
                    value = interaction.x,
                    onValueChange = { onUpdate(interaction.copy(x = it)) },
                    label = if (anchored) "dX px" else "X px",
                    screenSize = screen.width,
                    anchored = anchored
                )
                CoordField(
                    value = interaction.y,
                    onValueChange = { onUpdate(interaction.copy(y = it)) },
                    label = if (anchored) "dY px" else "Y px",
                    screenSize = screen.height,
                    anchored = anchored
                )
                NumberField(
                    value = interaction.duration,
                    onValueChange = { onUpdate(interaction.copy(duration = it)) },
                    label = "Hold ms",
                    modifier = Modifier.width(100.dp)
                )
                NumberField(
                    value = interaction.taps.toLong(),
                    onValueChange = { onUpdate(interaction.copy(taps = it.toInt().coerceAtLeast(1))) },
                    label = "Taps",
                    modifier = Modifier.width(80.dp)
                )
                NumberField(
                    value = interaction.randomFactor.toLong(),
                    onValueChange = { onUpdate(interaction.copy(randomFactor = it.toInt())) },
                    label = "Rand px",
                    modifier = Modifier.width(100.dp)
                )
            }
            is DragInteraction -> {
                val anchored = interaction.anchor.isNotBlank()
                val start = interaction.points.firstOrNull()
                val end = interaction.points.lastOrNull()
                // A recorded path has many points and only its start is
                // meaningful to edit; a two-point swipe is defined by its ends.
                val simple = interaction.points.size == 2

                AnchorPicker(
                    selected = interaction.anchor,
                    onSelected = { onUpdate(interaction.copy(anchor = it)) }
                )
                if (start != null) {
                    // Editing the start translates the whole path, which is what
                    // you want when a recorded gesture landed slightly off.
                    CoordField(
                        value = start.x,
                        onValueChange = { onUpdate(interaction.translatedTo(it, start.y)) },
                        label = if (anchored) "Start dX" else "Start X px",
                        screenSize = screen.width,
                        anchored = anchored,
                        width = 110.dp
                    )
                    CoordField(
                        value = start.y,
                        onValueChange = { onUpdate(interaction.translatedTo(start.x, it)) },
                        label = if (anchored) "Start dY" else "Start Y px",
                        screenSize = screen.height,
                        anchored = anchored,
                        width = 110.dp
                    )
                }
                if (simple && end != null) {
                    CoordField(
                        value = end.x,
                        onValueChange = { onUpdate(interaction.withEnd(it, end.y)) },
                        label = if (anchored) "End dX" else "End X px",
                        screenSize = screen.width,
                        anchored = anchored,
                        width = 110.dp
                    )
                    CoordField(
                        value = end.y,
                        onValueChange = { onUpdate(interaction.withEnd(end.x, it)) },
                        label = if (anchored) "End dY" else "End Y px",
                        screenSize = screen.height,
                        anchored = anchored,
                        width = 110.dp
                    )
                    NumberField(
                        value = end.dt,
                        onValueChange = { onUpdate(interaction.withSwipeDuration(it)) },
                        label = "Swipe ms",
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
                    label = "Repeat (0 = forever)",
                    modifier = Modifier.width(180.dp)
                )
            }
            is ToastInteraction -> {
                TextFieldEntry(
                    value = interaction.message,
                    onValueChange = { onUpdate(interaction.copy(message = it)) },
                    label = "Message ({...} is evaluated)",
                    width = 280.dp
                )
            }
            is KeyEventInteraction -> {
                TextFieldEntry(
                    value = interaction.key,
                    onValueChange = { onUpdate(interaction.copy(key = it)) },
                    label = "Key (BACK, HOME, APP_SWITCH...)",
                    width = 280.dp
                )
            }
            is LaunchAppInteraction -> {
                TextFieldEntry(
                    value = interaction.packageName,
                    onValueChange = { onUpdate(interaction.copy(packageName = it)) },
                    label = "Package name",
                    width = 280.dp
                )
            }
            is ShellInteraction -> {
                TextFieldEntry(
                    value = interaction.command,
                    onValueChange = { onUpdate(interaction.copy(command = it)) },
                    label = "Shell command (root)",
                    width = 280.dp
                )
            }
            is SetVariableInteraction -> {
                TextFieldEntry(
                    value = interaction.variable,
                    onValueChange = { onUpdate(interaction.copy(variable = it)) },
                    label = "Variable",
                    width = 140.dp
                )
                ExpressionField(
                    value = interaction.expression,
                    onValueChange = { onUpdate(interaction.copy(expression = it)) },
                    label = "= expression"
                )
            }
            is WaitCodeInteraction -> {
                TextFieldEntry(
                    value = interaction.variable,
                    onValueChange = { onUpdate(interaction.copy(variable = it)) },
                    label = "Variable",
                    width = 140.dp
                )
                NumberField(
                    value = interaction.maxAgeSeconds,
                    onValueChange = { onUpdate(interaction.copy(maxAgeSeconds = it)) },
                    label = "Max age s",
                    modifier = Modifier.width(120.dp)
                )
                NumberField(
                    value = interaction.timeoutMs,
                    onValueChange = { onUpdate(interaction.copy(timeoutMs = it)) },
                    label = "Timeout ms",
                    modifier = Modifier.width(120.dp)
                )
            }
            is IfStartInteraction -> {
                ExpressionField(
                    value = interaction.condition,
                    onValueChange = { onUpdate(interaction.copy(condition = it)) },
                    label = "Condition"
                )
            }
            is ElseIfInteraction -> {
                ExpressionField(
                    value = interaction.condition,
                    onValueChange = { onUpdate(interaction.copy(condition = it)) },
                    label = "Condition"
                )
            }
            is WhileStartInteraction -> {
                ExpressionField(
                    value = interaction.condition,
                    onValueChange = { onUpdate(interaction.copy(condition = it)) },
                    label = "While condition"
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

@Composable
private fun TextFieldEntry(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    width: Dp
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.width(width),
        singleLine = true
    )
}

/**
 * Chooses what a gesture's coordinates are measured from: the screen, or a
 * saved area found on screen when the step runs.
 *
 * A dropdown rather than a text box because a mistyped area name would look
 * identical to one that simply is not on screen -- both just skip the gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnchorPicker(selected: AnchorImage, onSelected: (AnchorImage) -> Unit) {
    val revision by ScreenshotStore.revision.collectAsState()
    val areas = remember(revision) { ScreenshotStore.list().map { it.name } }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.width(200.dp)
    ) {
        OutlinedTextField(
            value = selected.ifBlank { ABSOLUTE_LABEL },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Relative to") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(ABSOLUTE_LABEL) + areas).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(if (option == ABSOLUTE_LABEL) "" else option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private const val ABSOLUTE_LABEL = "Screen (absolute)"

/**
 * One coordinate, always shown in pixels.
 *
 * Absolute coordinates are stored as a fraction of the screen and converted
 * here; anchored ones are already pixels from the anchor and can be negative,
 * for a point above or left of the image.
 */
@Composable
private fun CoordField(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    screenSize: Int,
    anchored: Boolean,
    width: Dp = 90.dp
) {
    NumberField(
        value = if (anchored) value.toLong() else (value * screenSize).toLong(),
        onValueChange = { onValueChange(if (anchored) it.toFloat() else it / screenSize.toFloat()) },
        label = label,
        allowNegative = anchored,
        modifier = Modifier.width(width)
    )
}

/**
 * Expression entry that reports a parse error as you type, so a broken
 * condition is caught in the editor rather than silently evaluating false at
 * playback time.
 */
@Composable
private fun ExpressionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    val error = remember(value) {
        if (value.isBlank()) null else try {
            parseExpression(value)
            null
        } catch (e: ExpressionException) {
            e.message
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.width(280.dp),
        singleLine = true
    )
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
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false
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
            // A lone "-" is kept so the sign can be typed before the number.
            val cleaned = if (allowNegative && input.startsWith("-")) "-$digits" else digits
            text = cleaned
            onValueChange(cleaned.toLongOrNull() ?: 0L)
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

private fun repeatLabel(count: Int): String =
    if (count <= 0) "Repeat forever" else "Repeat ${count}x"

private fun Interaction.isBlockMarker(): Boolean =
    opensBlock(this) || closesBlock(this) || isMidBlock(this)

private fun Interaction.isBlockEnd(): Boolean = closesBlock(this) || isMidBlock(this)

private fun describeInteraction(interaction: Interaction, screen: ScreenGeometry): String {
    // Anchored coordinates are already pixels, and are an offset rather than a
    // place, so they are shown signed to make that obvious.
    fun px(x: Float, y: Float, anchor: AnchorImage) =
        if (anchor.isBlank()) "(${(x * screen.width).toInt()}, ${(y * screen.height).toInt()})"
        else "(%+d, %+d)".format(x.toInt(), y.toInt())

    fun from(anchor: AnchorImage) = if (anchor.isBlank()) "" else "  from \"$anchor\""

    val wait = if (interaction.delayBefore > 0) "wait ${interaction.delayBefore}ms  " else ""
    val label = when (interaction) {
        is ClickInteraction -> {
            val what = when {
                interaction.taps > 1 -> "Tap x${interaction.taps}"
                interaction.duration >= 500 -> "Long press"
                else -> "Click"
            }
            "$what ${px(interaction.x, interaction.y, interaction.anchor)}" +
                "  ${interaction.duration}ms${from(interaction.anchor)}"
        }
        is DragInteraction -> {
            val start = interaction.points.firstOrNull()
            val end = interaction.points.lastOrNull()
            if (start == null || end == null) {
                "Drag (empty)"
            } else {
                "Drag ${px(start.x, start.y, interaction.anchor)} to " +
                    "${px(end.x, end.y, interaction.anchor)}  ${interaction.points.size} pts" +
                    from(interaction.anchor)
            }
        }
        is TextInteraction ->
            if (interaction.text.isBlank()) "Text (empty)" else "Text \"${interaction.text}\""
        is KeyEventInteraction -> "Key ${interaction.key}"
        is LaunchAppInteraction -> "Launch ${interaction.packageName.ifBlank { "(no package)" }}"
        is ShellInteraction -> "Shell: ${interaction.command.ifBlank { "(empty)" }}"
        is WaitInteraction -> "Wait"
        is ToastInteraction -> "Toast: ${interaction.message.ifBlank { "(empty)" }}"
        is SetVariableInteraction -> "Set ${interaction.variable} = ${interaction.expression}"
        is WaitCodeInteraction ->
            "Wait for code -> ${interaction.variable}  max age ${interaction.maxAgeSeconds}s"
        is BreakInteraction -> "Break"
        is LoopStartInteraction -> repeatLabel(interaction.repeatCount)
        is LoopEndInteraction -> "End repeat"
        is RandomSelectStartInteraction -> "Random one of"
        is RandomSelectEndInteraction -> "End random"
        is IfStartInteraction -> "If ${interaction.condition}"
        is ElseIfInteraction -> "Else if ${interaction.condition}"
        is ElseInteraction -> "Else"
        is IfEndInteraction -> "End if"
        is WhileStartInteraction -> "While ${interaction.condition}"
        is WhileEndInteraction -> "End while"
        is ForLoopInteraction -> repeatLabel(interaction.repeatCount)
        is RandomSelectInteraction -> "Random one of"
        is IfInteraction -> "If ${interaction.branches.firstOrNull()?.condition ?: ""}"
        is WhileInteraction -> "While ${interaction.condition}"
    }
    val name = if (interaction.name.isBlank()) "" else "  -  ${interaction.name}"
    return "$wait$label$name"
}

private fun opensBlock(item: Interaction): Boolean =
    item is LoopStartInteraction || item is RandomSelectStartInteraction ||
        item is WhileStartInteraction || item is IfStartInteraction

private fun closesBlock(item: Interaction): Boolean =
    item is LoopEndInteraction || item is RandomSelectEndInteraction ||
        item is WhileEndInteraction || item is IfEndInteraction

/** ElseIf and Else sit at the parent's level but keep the block open. */
private fun isMidBlock(item: Interaction): Boolean =
    item is ElseIfInteraction || item is ElseInteraction

/** Indent level of each row, so nested blocks can be drawn as nested. */
fun blockDepths(items: List<Interaction>): List<Int> {
    var depth = 0
    return items.map { item ->
        when {
            opensBlock(item) -> depth++
            closesBlock(item) -> {
                depth = (depth - 1).coerceAtLeast(0)
                depth
            }
            isMidBlock(item) -> (depth - 1).coerceAtLeast(0)
            else -> depth
        }
    }
}

fun isBalanced(items: List<Interaction>): Boolean {
    var depth = 0
    items.forEach { item ->
        when {
            opensBlock(item) -> depth++
            closesBlock(item) -> {
                depth--
                if (depth < 0) return false
            }
            // An ElseIf or Else outside any If has nothing to attach to.
            isMidBlock(item) -> if (depth == 0) return false
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
    is KeyEventInteraction -> copy(delayBefore = delay)
    is LaunchAppInteraction -> copy(delayBefore = delay)
    is ShellInteraction -> copy(delayBefore = delay)
    is WaitInteraction -> copy(delayBefore = delay)
    is ToastInteraction -> copy(delayBefore = delay)
    is SetVariableInteraction -> copy(delayBefore = delay)
    is WaitCodeInteraction -> copy(delayBefore = delay)
    is BreakInteraction -> copy(delayBefore = delay)
    is ForLoopInteraction -> copy(delayBefore = delay)
    is RandomSelectInteraction -> copy(delayBefore = delay)
    is IfInteraction -> copy(delayBefore = delay)
    is WhileInteraction -> copy(delayBefore = delay)
    is LoopStartInteraction -> copy(delayBefore = delay)
    is LoopEndInteraction -> copy(delayBefore = delay)
    is RandomSelectStartInteraction -> copy(delayBefore = delay)
    is RandomSelectEndInteraction -> copy(delayBefore = delay)
    is IfStartInteraction -> copy(delayBefore = delay)
    is ElseIfInteraction -> copy(delayBefore = delay)
    is ElseInteraction -> copy(delayBefore = delay)
    is IfEndInteraction -> copy(delayBefore = delay)
    is WhileStartInteraction -> copy(delayBefore = delay)
    is WhileEndInteraction -> copy(delayBefore = delay)
}

fun Interaction.withName(newName: String): Interaction = when (this) {
    is ClickInteraction -> copy(name = newName)
    is DragInteraction -> copy(name = newName)
    is TextInteraction -> copy(name = newName)
    is KeyEventInteraction -> copy(name = newName)
    is LaunchAppInteraction -> copy(name = newName)
    is ShellInteraction -> copy(name = newName)
    is WaitInteraction -> copy(name = newName)
    is ToastInteraction -> copy(name = newName)
    is SetVariableInteraction -> copy(name = newName)
    is WaitCodeInteraction -> copy(name = newName)
    is BreakInteraction -> copy(name = newName)
    is ForLoopInteraction -> copy(name = newName)
    is RandomSelectInteraction -> copy(name = newName)
    is IfInteraction -> copy(name = newName)
    is WhileInteraction -> copy(name = newName)
    is LoopStartInteraction -> copy(name = newName)
    is LoopEndInteraction -> copy(name = newName)
    is RandomSelectStartInteraction -> copy(name = newName)
    is RandomSelectEndInteraction -> copy(name = newName)
    is IfStartInteraction -> copy(name = newName)
    is ElseIfInteraction -> copy(name = newName)
    is ElseInteraction -> copy(name = newName)
    is IfEndInteraction -> copy(name = newName)
    is WhileStartInteraction -> copy(name = newName)
    is WhileEndInteraction -> copy(name = newName)
}

/** Moves the whole path so its first point lands on the given coordinates. */
fun DragInteraction.translatedTo(x: Float, y: Float): DragInteraction {
    val start = points.firstOrNull() ?: return this
    val dx = x - start.x
    val dy = y - start.y
    return copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
}

/** Moves only the last point, which for a two-point swipe is its destination. */
fun DragInteraction.withEnd(x: Float, y: Float): DragInteraction {
    if (points.isEmpty()) return this
    return copy(points = points.mapIndexed { index, point ->
        if (index == points.lastIndex) point.copy(x = x, y = y) else point
    })
}

/** How long the finger takes to travel; the last point carries the whole gap. */
fun DragInteraction.withSwipeDuration(ms: Long): DragInteraction {
    if (points.isEmpty()) return this
    return copy(points = points.mapIndexed { index, point ->
        if (index == points.lastIndex) point.copy(dt = ms) else point
    })
}

// ---------------------------------------------------------------------------
// Flattening / Unflattening
// ---------------------------------------------------------------------------

fun flatten(interactions: List<Interaction>): List<Interaction> {
    val flatList = mutableListOf<Interaction>()
    interactions.forEach { interaction ->
        when (interaction) {
            is ForLoopInteraction -> {
                flatList.add(LoopStartInteraction(interaction.repeatCount, interaction.delayBefore, interaction.name))
                flatList.addAll(flatten(interaction.interactions))
                flatList.add(LoopEndInteraction(0))
            }
            is RandomSelectInteraction -> {
                flatList.add(RandomSelectStartInteraction(interaction.delayBefore, interaction.name))
                flatList.addAll(flatten(interaction.interactions))
                flatList.add(RandomSelectEndInteraction(0))
            }
            is WhileInteraction -> {
                flatList.add(WhileStartInteraction(interaction.condition, interaction.delayBefore, interaction.name))
                flatList.addAll(flatten(interaction.interactions))
                flatList.add(WhileEndInteraction(0))
            }
            is IfInteraction -> {
                interaction.branches.forEachIndexed { index, branch ->
                    if (index == 0) {
                        flatList.add(IfStartInteraction(branch.condition, interaction.delayBefore, interaction.name))
                    } else {
                        flatList.add(ElseIfInteraction(branch.condition))
                    }
                    flatList.addAll(flatten(branch.interactions))
                }
                if (interaction.elseBranch.isNotEmpty()) {
                    flatList.add(ElseInteraction())
                    flatList.addAll(flatten(interaction.elseBranch))
                }
                flatList.add(IfEndInteraction(0))
            }
            else -> flatList.add(interaction)
        }
    }
    return flatList
}

fun buildHierarchy(flatInteractions: List<Interaction>): List<Interaction> =
    readSequence(flatInteractions, 0) { false }.children

private class ParsedSequence(val children: List<Interaction>, val terminator: Interaction?, val next: Int)

private fun isBlockOpener(item: Interaction): Boolean =
    item is LoopStartInteraction || item is RandomSelectStartInteraction ||
        item is WhileStartInteraction || item is IfStartInteraction

private fun isStrayMarker(item: Interaction): Boolean =
    item is LoopEndInteraction || item is RandomSelectEndInteraction ||
        item is WhileEndInteraction || item is IfEndInteraction ||
        item is ElseIfInteraction || item is ElseInteraction

/**
 * Reads interactions until [isTerminator] matches, recursing into any block it
 * meets. Returns which terminator stopped it, which is what lets an If chain
 * tell ElseIf from Else from End.
 */
private fun readSequence(
    flat: List<Interaction>,
    start: Int,
    isTerminator: (Interaction) -> Boolean
): ParsedSequence {
    val children = mutableListOf<Interaction>()
    var i = start
    while (i < flat.size) {
        val item = flat[i]
        if (isTerminator(item)) return ParsedSequence(children, item, i + 1)
        if (isBlockOpener(item)) {
            val (node, next) = readBlock(flat, item, i + 1)
            children.add(node)
            i = next
            continue
        }
        // An End with no matching Start cannot be represented; drop it. The
        // editor warns about this before saving.
        if (isStrayMarker(item)) {
            i++
            continue
        }
        children.add(item)
        i++
    }
    return ParsedSequence(children, null, i)
}

private fun readBlock(flat: List<Interaction>, opener: Interaction, start: Int): Pair<Interaction, Int> =
    when (opener) {
        is LoopStartInteraction -> {
            val body = readSequence(flat, start) { it is LoopEndInteraction || it is RandomSelectEndInteraction }
            ForLoopInteraction(opener.repeatCount, body.children, opener.delayBefore, opener.name) to body.next
        }
        is RandomSelectStartInteraction -> {
            val body = readSequence(flat, start) { it is LoopEndInteraction || it is RandomSelectEndInteraction }
            RandomSelectInteraction(body.children, opener.delayBefore, opener.name) to body.next
        }
        is WhileStartInteraction -> {
            val body = readSequence(flat, start) { it is WhileEndInteraction }
            WhileInteraction(opener.condition, body.children, opener.delayBefore, opener.name) to body.next
        }
        is IfStartInteraction -> readIf(flat, opener, start)
        else -> opener to start
    }

private fun readIf(flat: List<Interaction>, opener: IfStartInteraction, start: Int): Pair<Interaction, Int> {
    val branches = mutableListOf<ConditionBranch>()
    var condition = opener.condition
    var index = start

    while (true) {
        val body = readSequence(flat, index) {
            it is ElseIfInteraction || it is ElseInteraction || it is IfEndInteraction
        }
        index = body.next
        when (val terminator = body.terminator) {
            is ElseIfInteraction -> {
                branches.add(ConditionBranch(condition, body.children))
                condition = terminator.condition
            }
            is ElseInteraction -> {
                branches.add(ConditionBranch(condition, body.children))
                val elseBody = readSequence(flat, index) { it is IfEndInteraction }
                return IfInteraction(branches, elseBody.children, opener.delayBefore, opener.name) to elseBody.next
            }
            // IfEnd, or the list ran out
            else -> {
                branches.add(ConditionBranch(condition, body.children))
                return IfInteraction(branches, emptyList(), opener.delayBefore, opener.name) to index
            }
        }
    }
}
