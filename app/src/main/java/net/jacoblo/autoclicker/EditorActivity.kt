package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
private class StepOption(val label: String, val create: () -> List<Step>)

// New gestures start in the middle of the screen, which is always somewhere
// real; a corner default would look like the step was broken.
private const val NEW_GESTURE_MS = 300L

private fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float) = listOf(
	DragStep(
		points = listOf(DragPoint(fromX, fromY, 0), DragPoint(toX, toY, NEW_GESTURE_MS)),
		delayBefore = 0
	)
)

/** A named set of steps, so the picker is two short lists instead of one long one. */
private class StepGroup(val label: String, val options: List<StepOption>)

/**
 * What can be inserted, grouped by what you are trying to do.
 *
 * There is one step per *kind* of thing, not one per setting of it. A double
 * tap is a tap with Taps 2 and a long press is a tap with a longer Hold, so
 * they were three entries that all built the same step and then hid the field
 * that actually distinguished them. The four swipes were the same: a direction
 * is where the coordinates start and end, which the step already shows.
 */
private val STEP_GROUPS = listOf(
	StepGroup(
		"Touch",
		listOf(
			// Taps and Hold ms turn this one step into a double tap, a triple
			// tap or a long press.
			StepOption("Tap") { listOf(ClickStep(0.5f, 0.5f, duration = 50, delayBefore = 0)) },
			// Starts as a swipe up, which scrolls a page down; the ends are
			// editable and are what make it any other direction.
			StepOption("Swipe") { swipe(0.5f, 0.7f, 0.5f, 0.3f) }
		)
	),
	StepGroup(
		"Typing",
		listOf(
			StepOption("Type text") { listOf(TextStep(text = "", delayBefore = 0)) },
			StepOption("Focus field") { listOf(FocusFieldStep("field", 0)) },
			StepOption("Key press") { listOf(KeyEventStep("BACK", 0)) }
		)
	),
	StepGroup(
		"Waiting",
		listOf(
			StepOption("Wait") { listOf(WaitStep(delayBefore = 1000)) },
			StepOption("HTTP GET") {
				listOf(
					HttpGetStep(
						url = "",
						variable = "response",
						timeoutMs = DEFAULT_HTTP_TIMEOUT_MS,
						intervalMs = DEFAULT_HTTP_INTERVAL_MS,
						delayBefore = 0
					)
				)
			}
		)
	),
	StepGroup(
		"Logic",
		listOf(
			StepOption("Comment") { listOf(CommentStep()) },
			StepOption("Set variable") { listOf(SetVariableStep("count", "0", 0)) },
			StepOption("If block") { listOf(IfStartStep("1 == 1"), IfEndStep()) },
			StepOption("Else if") { listOf(ElseIfStep("1 == 1")) },
			StepOption("Else") { listOf(ElseStep()) },
			StepOption("Repeat block") { listOf(LoopStartStep(repeatCount = 2), LoopEndStep()) },
			StepOption("While block") { listOf(WhileStartStep("1 == 1"), WhileEndStep()) },
			StepOption("Random block") { listOf(RandomSelectStartStep(), RandomSelectEndStep()) },
			StepOption("Break") { listOf(BreakStep()) }
		)
	),
	StepGroup(
		"System",
		listOf(
			StepOption("Launch app") { listOf(LaunchAppStep("", 0)) },
			StepOption("Shell command") { listOf(ShellStep("", 0)) },
			StepOption("Toast") { listOf(ToastStep("", 0)) }
		)
	)
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
		"are a place on the display; on a saved area or a phrase they are " +
		"pixels from wherever that area is found or those words are read when " +
		"the step runs, and may be negative. Either is looked for each time, " +
		"which needs root, and the gesture is skipped if it is not there. A " +
		"saved area costs about half a second and matches only the pixels it " +
		"was cropped from; a phrase costs a little more and survives the words " +
		"moving or being restyled."

private fun helpFor(step: Step): StepHelp = when (step) {
	is ClickStep -> StepHelp(
		"Touches one point. Hold ms is how long the finger stays down and Taps " +
			"is how many times it presses, so this one step is also a double " +
			"tap, a triple tap and a long press. Rand px scatters the point a " +
			"little on every replay. " + RELATIVE_HELP,
		listOf(
			"Hold 50, Taps 1                     a normal tap",
			"Hold 50, Taps 2                     a double tap",
			"Hold 800, Taps 1                    a long press",
			"Text \"Continue\", dX 90, dY 16      the middle of that word",
			"Image \"calc\", dX 0, dY -60         just above the image"
		)
	)

	is DragStep -> StepHelp(
		"Drags from one point to another, which is how you scroll, flick or " +
			"move something. A swipe up scrolls the page down. Swipe ms is how " +
			"long the finger takes to travel, so slower reads as a drag and " +
			"faster as a fling. Rand start scatters the ends and Rand mid the " +
			"middle. A recorded swipe keeps its own path, pressure and timing, " +
			"and only its start can be moved. " + RELATIVE_HELP,
		listOf(
			"Start 540,1700 to end 540,700       swipe up, scrolls down",
			"Start 540,700 to end 540,1700       swipe down, scrolls up",
			"Swipe ms 120                        a fling",
			"Swipe ms 600                        a slow drag"
		)
	)

	is TextStep -> StepHelp(
		"Types into whatever field has focus, one character at a time with a " +
			"human-sized gap between them. Put a Focus field step in front of " +
			"it unless the screen already opened with the cursor in place. " +
			"Plain text is typed as written; anything in braces is worked out " +
			"first, which is how a code that was looked up gets typed.",
		listOf(
			"hello world",
			"{codes[0]}                          the best code found",
			"{codes[i]}                          the one this loop is on",
			"user{count}@example.com"
		)
	)

	is FocusFieldStep -> StepHelp(
		"Puts the cursor in the text field on screen by asking the window " +
			"where it is, rather than tapping a remembered spot. Touches " +
			"nothing if the field already has focus. The variable is left " +
			"holding how many characters the field already contains, so a " +
			"While guarded on it clears the field exactly. Needs root. If " +
			"there is no field, or several with none focused, nothing is " +
			"touched and an error is shown.",
		listOf(
			"field       then While field > 0:  Key DEL,  Set field = field - 1",
			"chars       any name you like"
		)
	)

	is CommentStep -> StepHelp(
		"A heading for whoever reads the script. It does nothing, waits for " +
			"nothing and costs nothing at playback. Every step has a Name, " +
			"which reads as a remark on that one step; put a Comment above a " +
			"group of them to say what the group as a whole is for. It heads " +
			"the steps below it as far as the next Comment beside it, and that " +
			"section is what the arrow folds away and what switching the " +
			"Comment off skips. Folding is also a long press on the row, which " +
			"is why a Comment can only be dragged by its handle.",
		listOf(
			"Enter the email address",
			"Find and type the 6 digit code",
			"Fill in the birthday wheels"
		)
	)

	is WaitStep -> StepHelp(
		"Pauses and does nothing else. The Wait ms field is the whole step. " +
			"Every other step has the same field, so use this one only where a " +
			"pause is the point.",
		listOf("1000        a second", "5000        five seconds")
	)

	is ToastStep -> StepHelp(
		"Shows a short message on screen, which is the simplest way to see " +
			"what a run is doing. Plain text is shown as written; anything in " +
			"braces is worked out first.",
		listOf(
			"Finished",
			"attempt {count} of 3",
			"{codes[i]} is next",
			"{total - done} left to go"
		)
	)

	is SetVariableStep -> StepHelp(
		"Stores a value under a name for the rest of this run. Variables start " +
			"at 0 and are forgotten when playback ends. The same expressions " +
			"work here as in a condition, so this is also how you ask about " +
			"the screen and keep the answer.",
		listOf(
			"0",
			"count + 1",
			"random(2, 5)                        a number in that range",
			"count % 3",
			"\"page \" + count",
			"waitTextAppear(\"Inbox\", 8000)      wait, and store whether it came"
		)
	)

	is HttpGetStep -> StepHelp(
		"Polls a URL with GET every interval until it answers 2xx (or the " +
			"timeout), then stores the raw response body as text in the variable. " +
			"An endpoint with nothing yet should answer non-2xx (e.g. 404) so the " +
			"poll keeps waiting. Pull values out with jq() in a Set step. If " +
			"nothing arrives before the timeout the variable is left empty and an " +
			"error is shown.",
		listOf(
			"set codes = jq(response, \"[.[].code]\")   extract a field into a list",
			"set token = jq(response, \".token\")         a single value",
			"While i < count(codes)                     then use it"
		)
	)

	is KeyEventStep -> StepHelp(
		"Presses a hardware or system key by name. Several names separated by " +
			"spaces are pressed in order. Needs root.",
		listOf(
			"BACK, HOME, APP_SWITCH",
			"ENTER, TAB, DEL",
			"MOVE_END                            cursor to the end of the field",
			"MOVE_END DEL DEL DEL                and rub out three characters",
			"VOLUME_UP, VOLUME_DOWN, POWER"
		)
	)

	is LaunchAppStep -> StepHelp(
		"Opens an app by package name, as though it were tapped on the home " +
			"screen. Find one with a Shell step running \"pm list packages\". " +
			"Needs root.",
		listOf(
			"com.android.settings",
			"com.android.chrome"
		)
	)

	is ShellStep -> StepHelp(
		"Runs a command as root. Output is not shown, only logged, so this is " +
			"for doing something rather than reading something back.",
		listOf(
			"am force-stop com.example.app       close an app",
			"pm clear com.example.app            wipe its data",
			"svc wifi disable",
			"input keycombination KEYCODE_CTRL_LEFT KEYCODE_A     select all"
		)
	)

	is IfStartStep, is ElseIfStep, is WhileStartStep -> StepHelp(
		"Runs the steps inside when the condition holds; While repeats them " +
			"for as long as it does. Compare with == != < > <= >=, combine " +
			"with && || !, and ask about the screen with textAppear() or " +
			"image(). The waiting forms, waitTextAppear() and waitImage(), " +
			"poll up to the milliseconds given and answer as soon as it shows " +
			"up. Text is read by the on-device recogniser and matches " +
			"capitals unless a final false says otherwise; an image is matched " +
			"against a saved area and takes an optional percentage instead.",
		listOf(
			"count < 3",
			"textAppear(\"Continue\")             those words are on screen",
			"textAppear(\"continue\", false)      ignoring capitals",
			"waitTextAppear(\"Inbox\", 5000)      wait up to 5s for them",
			"!textAppear(\"Wrong code\")          they are not there",
			"image(\"start_button\")              a saved area is on screen",
			"image(\"button\", 95)                needing a 95% match",
			"i < count(codes)                    once per code found"
		)
	)

	is LoopStartStep -> StepHelp(
		"Repeats the steps inside a set number of times. Set the count to 0 to " +
			"repeat until a Break step or the stop button.",
		listOf(
			"3           three times",
			"0           until Break or stop"
		)
	)

	is RandomSelectStartStep -> StepHelp(
		"Runs exactly one of the steps inside, chosen at random each time it " +
			"is reached. Useful for varying a run so it does not repeat itself " +
			"identically."
	)

	is BreakStep -> StepHelp(
		"Leaves the innermost Repeat or While immediately and carries on after " +
			"it. Usually sits inside an If, so the loop stops once something " +
			"has worked.",
		listOf("If textAppear(\"Welcome\") then Break      stop once it is done")
	)

	is ElseStep -> StepHelp("Runs when none of the conditions above it held.")

	else -> StepHelp("Marks the end of the block above it.")
}

/** An step plus a stable id, so reordering can key rows by identity. */
private data class EditorRow(val id: Long, val step: Step)

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

/**
 * Opening a recording is a file read and a JSON parse, which is not work for
 * the thread that draws.
 *
 * Nothing below is composed until it has landed. Every remember in the editor
 * seeds itself from the loaded recording -- the rows, the id counter, the
 * global random, and the baseline that decides whether there are unsaved
 * changes -- so they have to see it once and complete. Loading in place would
 * compose an empty editor first, and then Save would write that emptiness over
 * the file and Back would ask about discarding changes nobody made.
 */
@Composable
fun EditorScreen(file: File, onBack: () -> Unit) {
	val loaded by produceState<RecordingData?>(null, file) {
		value = withContext(Dispatchers.IO) { RecordingManager.loadRecording(file) }
	}

	loaded?.let { LoadedEditorScreen(file, it, onBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadedEditorScreen(file: File, recordingData: RecordingData, onBack: () -> Unit) {
	// The hierarchy is flattened for editing and rebuilt on save.
	val initialSteps = remember { flatten(recordingData.events) }
	// Rows carry an id because reordering needs stable identity: keying by
	// position would attach a row's field state to the slot, not the item.
	val rows = remember {
		mutableStateListOf<EditorRow>().apply {
			initialSteps.forEachIndexed { index, item -> add(EditorRow(index.toLong(), item)) }
		}
	}
	var nextRowId by remember { mutableLongStateOf(initialSteps.size.toLong()) }

	var globalRandom by remember { mutableIntStateOf(recordingData.globalRandom) }
	var groupToAdd by remember { mutableStateOf(STEP_GROUPS.first()) }
	var stepToAdd by remember { mutableStateOf(STEP_GROUPS.first().options.first()) }
	// Only one row is expanded at a time; collapsed rows are a single summary line.
	var expandedId by remember { mutableStateOf<Long?>(null) }
	var confirmDiscard by remember { mutableStateOf(false) }
	// Comments whose sections are folded out of sight. Not saved with the
	// recording: what you have folded away is how you are reading the script,
	// not part of it.
	val folded = remember { mutableStateListOf<Long>() }

	val steps = rows.map { it.step }
	val dirty = steps != initialSteps || globalRandom != recordingData.globalRandom
	val depths = blockDepths(steps)
	val disabled = disabledRows(steps)

	// Indices into rows, because every edit below addresses a row by position.
	val hidden = buildSet {
		rows.forEachIndexed { index, row ->
			if (row.step is CommentStep && row.id in folded) addAll(commentSection(steps, index))
		}
	}
	val visible = rows.indices.filter { it !in hidden }

	fun add(step: Step) {
		rows.add(EditorRow(nextRowId++, step))
	}

	/**
	 * A folded comment travels with the steps it is hiding.
	 *
	 * They are not on screen to be dragged themselves, so leaving them behind
	 * would regroup the script under whatever comment they landed beneath --
	 * and it would do it invisibly, which is the part that matters.
	 */
	fun move(from: Int, to: Int) {
		val row = rows[from]
		val carried =
			if (row.step is CommentStep && row.id in folded) 1 + commentSection(steps, from).count()
			else 1
		val moving = List(carried) { rows.removeAt(from) }
		// Everything after the gap has shifted up by what was lifted out of it.
		val target = if (to > from) to - carried + 1 else to
		rows.addAll(target.coerceIn(0, rows.size), moving)
	}

	fun save() {
		RecordingManager.saveRecordingToFile(file, buildHierarchy(steps), globalRandom)
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
				DropdownPicker(
					label = "Group",
					selected = groupToAdd.label,
					options = STEP_GROUPS.map { it.label },
					onSelected = { picked ->
						groupToAdd = STEP_GROUPS.first { it.label == picked }
						// The step below it has to belong to the group above, or
						// the button would insert whatever was left selected.
						stepToAdd = groupToAdd.options.first()
					},
					modifier = Modifier.weight(1f)
				)

				DropdownPicker(
					label = "Step",
					selected = stepToAdd.label,
					options = groupToAdd.options.map { it.label },
					onSelected = { picked ->
						stepToAdd = groupToAdd.options.first { it.label == picked }
					},
					modifier = Modifier.weight(1f)
				)

				FilledIconButton(onClick = { stepToAdd.create().forEach { add(it) } }) {
					Icon(Icons.Default.Add, contentDescription = "Add step")
				}
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 8.dp)
					.padding(bottom = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				NumberField(
					value = globalRandom.toLong(),
					onValueChange = { globalRandom = it.toInt() },
					label = "Rand ms",
					modifier = Modifier.width(110.dp)
				)
				Text(
					"added to every step's wait, so a run is not identically timed",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}

			if (!isBalanced(steps)) {
				Text(
					"Unbalanced blocks: every Repeat/Random needs a matching End, or the extra one is dropped on save.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
				)
			}

			val listState = rememberLazyListState()
			// from/to count the rows on screen, which is not the same list once
			// something is folded away.
			val reorderState = rememberReorderableLazyListState(listState) { from, to ->
				move(visible[from.index], visible[to.index])
				expandedId = null
			}

			LazyColumn(
				state = listState,
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
			) {
				itemsIndexed(visible, key = { _, index -> rows[index].id }) { _, index ->
					val row = rows[index]
					val section = if (row.step is CommentStep) commentSection(steps, index) else IntRange.EMPTY
					ReorderableItem(reorderState, key = row.id) { dragging ->
						StepRow(
							step = row.step,
							depth = depths.getOrElse(index) { 0 },
							disabled = disabled.getOrElse(index) { false },
							sectionSize = section.count(),
							folded = row.id in folded,
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
							onToggleFold = {
								if (!folded.remove(row.id)) folded.add(row.id)
								expandedId = null
							},
							onToggleEnabled = {
								rows[index] = row.copy(step = row.step.withEnabled(!row.step.enabled))
							},
							onUpdate = { updated -> rows[index] = row.copy(step = updated) },
							onDelete = {
								rows.removeAt(index)
								expandedId = null
							},
							// Long-pressing the row drags it too. The handle
							// alone was a 28dp target at the screen edge, so a
							// finger that missed it just scrolled or expanded.
							// On a comment that gesture folds instead, and the
							// handle is the only way to drag one.
							modifier = if (row.step is CommentStep) Modifier
							else Modifier.longPressDraggableHandle(
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

/** A read-only dropdown over a list of labels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownPicker(
	label: String,
	selected: String,
	options: List<String>,
	onSelected: (String) -> Unit,
	modifier: Modifier = Modifier
) {
	var expanded by remember { mutableStateOf(false) }

	ExposedDropdownMenuBox(
		expanded = expanded,
		onExpandedChange = { expanded = !expanded },
		modifier = modifier
	) {
		OutlinedTextField(
			value = selected,
			onValueChange = {},
			readOnly = true,
			singleLine = true,
			label = { Text(label) },
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
		)
		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			options.forEach { option ->
				DropdownMenuItem(
					text = { Text(option) },
					onClick = {
						onSelected(option)
						expanded = false
					}
				)
			}
		}
	}
}

/**
 * [disabled] is whether the step will actually run, which is not the same as
 * its own switch: a live step inside a switched-off block shows its own switch
 * on and is still struck through, because that is the truth of it.
 *
 * [sectionSize] is how many rows a comment heads, and 0 for anything else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StepRow(
	step: Step,
	depth: Int,
	disabled: Boolean,
	sectionSize: Int,
	folded: Boolean,
	expanded: Boolean,
	dragging: Boolean,
	dragHandle: @Composable () -> Unit,
	onToggleExpand: () -> Unit,
	onToggleFold: () -> Unit,
	onToggleEnabled: () -> Unit,
	onUpdate: (Step) -> Unit,
	onDelete: () -> Unit,
	modifier: Modifier = Modifier
) {
	val accent = blockAccent(depth)
	val screen = remember { ScreenGeometry.current(AppSettings.appContext) }
	// A comment is the one row that is not an instruction, so it is drawn as a
	// band rather than as another line of text: what makes a heading worth
	// having is being findable without being read.
	val comment = step is CommentStep
	val background = when {
		dragging -> MaterialTheme.colorScheme.surfaceVariant
		comment -> MaterialTheme.colorScheme.secondaryContainer
		else -> Color.Transparent
	}

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

		val foldable = comment && sectionSize > 0
		Column(
			modifier = Modifier
				.weight(1f)
				.combinedClickable(
					onClick = onToggleExpand,
					onLongClick = if (foldable) onToggleFold else null
				)
				.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (foldable) {
					IconButton(onClick = onToggleFold, modifier = Modifier.size(24.dp)) {
						Icon(
							imageVector = if (folded) Icons.Default.ChevronRight
							else Icons.Default.ExpandMore,
							contentDescription = if (folded) "Show these steps" else "Hide these steps"
						)
					}
				}
				Text(
					text = describeStep(step, screen) +
						if (folded && sectionSize > 0) "   ($sectionSize hidden)" else "",
					style = MaterialTheme.typography.bodyMedium,
					fontStyle = if (comment) FontStyle.Italic else null,
					// Struck through rather than merely faded, because faded is
					// also what a long list looks like on a dim screen.
					textDecoration = if (disabled) TextDecoration.LineThrough else null,
					color = when {
						disabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
						comment -> MaterialTheme.colorScheme.onSecondaryContainer
						step.isBlockMarker() -> accent
						else -> MaterialTheme.colorScheme.onSurface
					},
					modifier = Modifier.weight(1f)
				)
				// Shows the step's own switch, not whether it will run: a step
				// can only be switched back on by its own switch.
				IconButton(onClick = onToggleEnabled, modifier = Modifier.size(28.dp)) {
					Icon(
						imageVector = if (step.enabled) Icons.Default.CheckCircle
						else Icons.Default.Block,
						contentDescription = if (step.enabled) "Switch off" else "Switch on"
					)
				}
				IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
					Icon(Icons.Default.Delete, contentDescription = "Delete")
				}
				dragHandle()
			}

			if (expanded) {
				Spacer(modifier = Modifier.height(8.dp))
				StepFields(step = step, onUpdate = onUpdate)
				Spacer(modifier = Modifier.height(8.dp))
				StepHelpPanel(helpFor(step))
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
private fun StepFields(step: Step, onUpdate: (Step) -> Unit) {
	// Coordinates are stored as fractions of the screen so scripts stay
	// portable, but they are shown and edited as pixels for this display.
	val screen = remember { ScreenGeometry.current(AppSettings.appContext) }

	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp)
	) {
		// End markers are discarded when the hierarchy is rebuilt, so a delay on
		// them would go nowhere; a comment does nothing at all, so a wait on one
		// would be a pause with nothing to show for it.
		if (!step.isBlockEnd() && step !is CommentStep) {
			NumberField(
				value = step.delayBefore,
				onValueChange = { onUpdate(step.withDelay(it)) },
				label = "Wait ms",
				modifier = Modifier.width(100.dp)
			)
		}

		when (step) {
			is ClickStep -> {
				var mode by remember {
					mutableStateOf(AnchorMode.of(step.anchor, step.anchorText))
				}
				// A mode that is picked but not yet filled in still means the
				// coordinates are offsets, or dX 100 would be reread as 108000.
				val anchored = mode != AnchorMode.SCREEN
				AnchorPicker(
					selected = step.anchor,
					selectedText = step.anchorText,
					mode = mode,
					onModeChange = { mode = it },
					onAnchor = { image, phrase ->
						onUpdate(step.copy(anchor = image, anchorText = phrase))
					}
				)
				CoordField(
					value = step.x,
					onValueChange = { onUpdate(step.copy(x = it)) },
					label = if (anchored) "dX px" else "X px",
					screenSize = screen.width,
					anchored = anchored
				)
				CoordField(
					value = step.y,
					onValueChange = { onUpdate(step.copy(y = it)) },
					label = if (anchored) "dY px" else "Y px",
					screenSize = screen.height,
					anchored = anchored
				)
				NumberField(
					value = step.duration,
					onValueChange = { onUpdate(step.copy(duration = it)) },
					label = "Hold ms",
					modifier = Modifier.width(100.dp)
				)
				NumberField(
					value = step.taps.toLong(),
					onValueChange = { onUpdate(step.copy(taps = it.toInt().coerceAtLeast(1))) },
					label = "Taps",
					modifier = Modifier.width(80.dp)
				)
				NumberField(
					value = step.randomFactor.toLong(),
					onValueChange = { onUpdate(step.copy(randomFactor = it.toInt())) },
					label = "Rand px",
					modifier = Modifier.width(100.dp)
				)
			}
			is DragStep -> {
				var mode by remember {
					mutableStateOf(AnchorMode.of(step.anchor, step.anchorText))
				}
				val anchored = mode != AnchorMode.SCREEN
				val start = step.points.firstOrNull()
				val end = step.points.lastOrNull()
				// A recorded path has many points and only its start is
				// meaningful to edit; a two-point swipe is defined by its ends.
				val simple = step.points.size == 2

				AnchorPicker(
					selected = step.anchor,
					selectedText = step.anchorText,
					mode = mode,
					onModeChange = { mode = it },
					onAnchor = { image, phrase ->
						onUpdate(step.copy(anchor = image, anchorText = phrase))
					}
				)
				if (start != null) {
					// Editing the start translates the whole path, which is what
					// you want when a recorded gesture landed slightly off.
					CoordField(
						value = start.x,
						onValueChange = { onUpdate(step.translatedTo(it, start.y)) },
						label = if (anchored) "Start dX" else "Start X px",
						screenSize = screen.width,
						anchored = anchored,
						width = 110.dp
					)
					CoordField(
						value = start.y,
						onValueChange = { onUpdate(step.translatedTo(start.x, it)) },
						label = if (anchored) "Start dY" else "Start Y px",
						screenSize = screen.height,
						anchored = anchored,
						width = 110.dp
					)
				}
				if (simple && end != null) {
					CoordField(
						value = end.x,
						onValueChange = { onUpdate(step.withEnd(it, end.y)) },
						label = if (anchored) "End dX" else "End X px",
						screenSize = screen.width,
						anchored = anchored,
						width = 110.dp
					)
					CoordField(
						value = end.y,
						onValueChange = { onUpdate(step.withEnd(end.x, it)) },
						label = if (anchored) "End dY" else "End Y px",
						screenSize = screen.height,
						anchored = anchored,
						width = 110.dp
					)
					NumberField(
						value = end.dt,
						onValueChange = { onUpdate(step.withSwipeDuration(it)) },
						label = "Swipe ms",
						modifier = Modifier.width(110.dp)
					)
				}
				NumberField(
					value = step.randomFactorStart.toLong(),
					onValueChange = { onUpdate(step.copy(randomFactorStart = it.toInt())) },
					label = "Rand start",
					modifier = Modifier.width(110.dp)
				)
				NumberField(
					value = step.randomFactorHighest.toLong(),
					onValueChange = { onUpdate(step.copy(randomFactorHighest = it.toInt())) },
					label = "Rand mid",
					modifier = Modifier.width(110.dp)
				)
			}
			is TextStep -> {
				OutlinedTextField(
					value = step.text,
					onValueChange = { onUpdate(step.copy(text = it)) },
					label = { Text("Text") },
					modifier = Modifier.width(220.dp),
					singleLine = true
				)
			}
			is LoopStartStep -> {
				NumberField(
					value = step.repeatCount.toLong(),
					onValueChange = { onUpdate(step.copy(repeatCount = it.toInt())) },
					label = "Repeat (0 = forever)",
					modifier = Modifier.width(180.dp)
				)
			}
			is ToastStep -> {
				TextFieldEntry(
					value = step.message,
					onValueChange = { onUpdate(step.copy(message = it)) },
					label = "Message ({...} is evaluated)",
					width = 280.dp
				)
			}
			is KeyEventStep -> {
				TextFieldEntry(
					value = step.key,
					onValueChange = { onUpdate(step.copy(key = it)) },
					label = "Key (BACK, HOME, APP_SWITCH...)",
					width = 280.dp
				)
			}
			is LaunchAppStep -> {
				TextFieldEntry(
					value = step.packageName,
					onValueChange = { onUpdate(step.copy(packageName = it)) },
					label = "Package name",
					width = 280.dp
				)
			}
			is ShellStep -> {
				TextFieldEntry(
					value = step.command,
					onValueChange = { onUpdate(step.copy(command = it)) },
					label = "Shell command (root)",
					width = 280.dp
				)
			}
			is SetVariableStep -> {
				TextFieldEntry(
					value = step.variable,
					onValueChange = { onUpdate(step.copy(variable = it)) },
					label = "Variable",
					width = 140.dp
				)
				ExpressionField(
					value = step.expression,
					onValueChange = { onUpdate(step.copy(expression = it)) },
					label = "= expression"
				)
			}
			is FocusFieldStep -> {
				TextFieldEntry(
					value = step.variable,
					onValueChange = { onUpdate(step.copy(variable = it)) },
					label = "Length into",
					width = 140.dp
				)
			}
			is HttpGetStep -> {
				TextFieldEntry(
					value = step.url,
					onValueChange = { onUpdate(step.copy(url = it)) },
					label = "URL",
					width = 260.dp
				)
				TextFieldEntry(
					value = step.variable,
					onValueChange = { onUpdate(step.copy(variable = it)) },
					label = "Into",
					width = 140.dp
				)
				NumberField(
					value = step.timeoutMs,
					onValueChange = { onUpdate(step.copy(timeoutMs = it)) },
					label = "Timeout ms",
					modifier = Modifier.width(120.dp)
				)
				NumberField(
					value = step.intervalMs,
					onValueChange = { onUpdate(step.copy(intervalMs = it)) },
					label = "Every ms",
					modifier = Modifier.width(120.dp)
				)
			}
			is IfStartStep -> {
				ExpressionField(
					value = step.condition,
					onValueChange = { onUpdate(step.copy(condition = it)) },
					label = "Condition"
				)
			}
			is ElseIfStep -> {
				ExpressionField(
					value = step.condition,
					onValueChange = { onUpdate(step.copy(condition = it)) },
					label = "Condition"
				)
			}
			is WhileStartStep -> {
				ExpressionField(
					value = step.condition,
					onValueChange = { onUpdate(step.copy(condition = it)) },
					label = "While condition"
				)
			}
			else -> {}
		}

		// On every other step this is a remark alongside the fields that do the
		// work. On a Comment it is the entire step, so it says so and is given
		// the room a heading needs.
		val comment = step is CommentStep
		OutlinedTextField(
			value = step.name,
			onValueChange = { onUpdate(step.withName(it)) },
			label = { Text(if (comment) "Comment" else "Name") },
			modifier = Modifier.width(if (comment) 280.dp else 180.dp),
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
/**
 * What the coordinates are measured from: the screen, a saved image, or words.
 *
 * The three are shown as a row of choices with only the one that is picked
 * carrying any detail below it. Folding them into a single list meant the
 * phrase and the image name were both on screen at once even though a step can
 * only ever use one, and "Text on screen" sat in the same list as the image
 * names as though it were another image.
 */
@Composable
private fun AnchorPicker(
	selected: AnchorImage,
	selectedText: String,
	mode: AnchorMode,
	onModeChange: (AnchorMode) -> Unit,
	onAnchor: (AnchorImage, String) -> Unit
) {
	val revision by ScreenshotStore.revision.collectAsState()
	val areas = remember(revision) { ScreenshotStore.list().map { it.name } }

	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("Relative to", style = MaterialTheme.typography.labelMedium)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			AnchorMode.entries.forEach { option ->
				FilterChip(
					selected = mode == option,
					onClick = {
						onModeChange(option)
						// Only ever one origin, so picking a mode clears the other.
						when (option) {
							AnchorMode.SCREEN -> onAnchor("", "")
							AnchorMode.IMAGE -> onAnchor(selected, "")
							AnchorMode.TEXT -> onAnchor("", selectedText)
						}
					},
					label = { Text(option.label) }
				)
			}
		}

		when (mode) {
			AnchorMode.SCREEN -> {}
			AnchorMode.IMAGE -> DropdownPicker(
				label = "Saved image",
				selected = selected.ifBlank { "Pick one" },
				options = areas,
				onSelected = { onAnchor(it, "") },
				modifier = Modifier.width(220.dp)
			)
			AnchorMode.TEXT -> TextFieldEntry(
				value = selectedText,
				onValueChange = { onAnchor("", it) },
				label = "Words on screen",
				width = 220.dp
			)
		}
	}
}

/** Which of the three kinds of origin a gesture is using. */
enum class AnchorMode(val label: String) {
	SCREEN("Screen"),
	IMAGE("Image"),
	TEXT("Text");

	companion object {
		fun of(anchor: AnchorImage, anchorText: String) = when {
			anchorText.isNotBlank() -> TEXT
			anchor.isNotBlank() -> IMAGE
			else -> SCREEN
		}
	}
}


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

private fun Step.isBlockMarker(): Boolean =
	this is BlockStart || this is BlockEnd || this is BlockMid

private fun Step.isBlockEnd(): Boolean = this is BlockEnd || this is BlockMid

private fun describeStep(step: Step, screen: ScreenGeometry): String {
	// Anchored coordinates are already pixels, and are an offset rather than a
	// place, so they are shown signed to make that obvious.
	fun px(x: Float, y: Float, anchor: AnchorImage, anchorText: String = "") =
		if (anchor.isBlank() && anchorText.isBlank())
			"(${(x * screen.width).toInt()}, ${(y * screen.height).toInt()})"
		else "(%+d, %+d)".format(x.toInt(), y.toInt())

	fun from(anchor: AnchorImage, anchorText: String = "") = when {
		anchorText.isNotBlank() -> "  from text \"$anchorText\""
		anchor.isNotBlank() -> "  from \"$anchor\""
		else -> ""
	}

	val wait = if (step.delayBefore > 0) "wait ${step.delayBefore}ms  " else ""
	val label = when (step) {
		is ClickStep -> {
			val what = when {
				step.taps > 1 -> "Tap x${step.taps}"
				step.duration >= 500 -> "Long press"
				else -> "Click"
			}
			"$what ${px(step.x, step.y, step.anchor, step.anchorText)}" +
				"  ${step.duration}ms${from(step.anchor, step.anchorText)}"
		}
		is DragStep -> {
			val start = step.points.firstOrNull()
			val end = step.points.lastOrNull()
			if (start == null || end == null) {
				"Drag (empty)"
			} else {
				"Drag ${px(start.x, start.y, step.anchor, step.anchorText)} to " +
					"${px(end.x, end.y, step.anchor, step.anchorText)}" +
					"  ${step.points.size} pts" + from(step.anchor, step.anchorText)
			}
		}
		is TextStep ->
			if (step.text.isBlank()) "Text (empty)" else "Text \"${step.text}\""
		is KeyEventStep -> "Key ${step.key}"
		is LaunchAppStep -> "Launch ${step.packageName.ifBlank { "(no package)" }}"
		is ShellStep -> "Shell: ${step.command.ifBlank { "(empty)" }}"
		is WaitStep -> "Wait"
		is CommentStep -> "// ${step.name.ifBlank { "comment" }}"
		is ToastStep -> "Toast: ${step.message.ifBlank { "(empty)" }}"
		is SetVariableStep -> "Set ${step.variable} = ${step.expression}"
		is FocusFieldStep -> "Focus field  length -> ${step.variable}"
		is HttpGetStep ->
			"GET ${step.url.ifBlank { "(no url)" }} -> ${step.variable}"
		is BreakStep -> "Break"
		is LoopStartStep -> repeatLabel(step.repeatCount)
		is LoopEndStep -> "End repeat"
		is RandomSelectStartStep -> "Random one of"
		is RandomSelectEndStep -> "End random"
		is IfStartStep -> "If ${step.condition}"
		is ElseIfStep -> "Else if ${step.condition}"
		is ElseStep -> "Else"
		is IfEndStep -> "End if"
		is WhileStartStep -> "While ${step.condition}"
		is WhileEndStep -> "End while"
		is ForLoopStep -> repeatLabel(step.repeatCount)
		is RandomSelectStep -> "Random one of"
		is IfStep -> "If ${step.branches.firstOrNull()?.condition ?: ""}"
		is WhileStep -> "While ${step.condition}"
	}
	// A comment is nothing but its name, which the label above already is.
	val name =
		if (step.name.isBlank() || step is CommentStep) "" else "  -  ${step.name}"
	return "$wait$label$name"
}

// ---------------------------------------------------------------------------
// Step copy helpers -- every subclass carries its own fields
// ---------------------------------------------------------------------------

fun Step.withDelay(delay: Long): Step = when (this) {
	is ClickStep -> copy(delayBefore = delay)
	is DragStep -> copy(delayBefore = delay)
	is TextStep -> copy(delayBefore = delay)
	is KeyEventStep -> copy(delayBefore = delay)
	is LaunchAppStep -> copy(delayBefore = delay)
	is ShellStep -> copy(delayBefore = delay)
	is WaitStep -> copy(delayBefore = delay)
	is CommentStep -> copy(delayBefore = delay)
	is ToastStep -> copy(delayBefore = delay)
	is SetVariableStep -> copy(delayBefore = delay)
	is HttpGetStep -> copy(delayBefore = delay)
	is FocusFieldStep -> copy(delayBefore = delay)
	is BreakStep -> copy(delayBefore = delay)
	is ForLoopStep -> copy(delayBefore = delay)
	is RandomSelectStep -> copy(delayBefore = delay)
	is IfStep -> copy(delayBefore = delay)
	is WhileStep -> copy(delayBefore = delay)
	is LoopStartStep -> copy(delayBefore = delay)
	is LoopEndStep -> copy(delayBefore = delay)
	is RandomSelectStartStep -> copy(delayBefore = delay)
	is RandomSelectEndStep -> copy(delayBefore = delay)
	is IfStartStep -> copy(delayBefore = delay)
	is ElseIfStep -> copy(delayBefore = delay)
	is ElseStep -> copy(delayBefore = delay)
	is IfEndStep -> copy(delayBefore = delay)
	is WhileStartStep -> copy(delayBefore = delay)
	is WhileEndStep -> copy(delayBefore = delay)
}

fun Step.withName(newName: String): Step = when (this) {
	is ClickStep -> copy(name = newName)
	is DragStep -> copy(name = newName)
	is TextStep -> copy(name = newName)
	is KeyEventStep -> copy(name = newName)
	is LaunchAppStep -> copy(name = newName)
	is ShellStep -> copy(name = newName)
	is WaitStep -> copy(name = newName)
	is CommentStep -> copy(name = newName)
	is ToastStep -> copy(name = newName)
	is SetVariableStep -> copy(name = newName)
	is HttpGetStep -> copy(name = newName)
	is FocusFieldStep -> copy(name = newName)
	is BreakStep -> copy(name = newName)
	is ForLoopStep -> copy(name = newName)
	is RandomSelectStep -> copy(name = newName)
	is IfStep -> copy(name = newName)
	is WhileStep -> copy(name = newName)
	is LoopStartStep -> copy(name = newName)
	is LoopEndStep -> copy(name = newName)
	is RandomSelectStartStep -> copy(name = newName)
	is RandomSelectEndStep -> copy(name = newName)
	is IfStartStep -> copy(name = newName)
	is ElseIfStep -> copy(name = newName)
	is ElseStep -> copy(name = newName)
	is IfEndStep -> copy(name = newName)
	is WhileStartStep -> copy(name = newName)
	is WhileEndStep -> copy(name = newName)
}

/** Switching one off leaves it in place, unrun -- see [Step.enabled]. */
fun Step.withEnabled(on: Boolean): Step = when (this) {
	is ClickStep -> copy(enabled = on)
	is DragStep -> copy(enabled = on)
	is TextStep -> copy(enabled = on)
	is KeyEventStep -> copy(enabled = on)
	is LaunchAppStep -> copy(enabled = on)
	is ShellStep -> copy(enabled = on)
	is WaitStep -> copy(enabled = on)
	is CommentStep -> copy(enabled = on)
	is ToastStep -> copy(enabled = on)
	is SetVariableStep -> copy(enabled = on)
	is HttpGetStep -> copy(enabled = on)
	is FocusFieldStep -> copy(enabled = on)
	is BreakStep -> copy(enabled = on)
	is ForLoopStep -> copy(enabled = on)
	is RandomSelectStep -> copy(enabled = on)
	is IfStep -> copy(enabled = on)
	is WhileStep -> copy(enabled = on)
	is LoopStartStep -> copy(enabled = on)
	is LoopEndStep -> copy(enabled = on)
	is RandomSelectStartStep -> copy(enabled = on)
	is RandomSelectEndStep -> copy(enabled = on)
	is IfStartStep -> copy(enabled = on)
	is ElseIfStep -> copy(enabled = on)
	is ElseStep -> copy(enabled = on)
	is IfEndStep -> copy(enabled = on)
	is WhileStartStep -> copy(enabled = on)
	is WhileEndStep -> copy(enabled = on)
}

/** Moves the whole path so its first point lands on the given coordinates. */
fun DragStep.translatedTo(x: Float, y: Float): DragStep {
	val start = points.firstOrNull() ?: return this
	val dx = x - start.x
	val dy = y - start.y
	return copy(points = points.map { it.copy(x = it.x + dx, y = it.y + dy) })
}

/** Moves only the last point, which for a two-point swipe is its destination. */
fun DragStep.withEnd(x: Float, y: Float): DragStep {
	if (points.isEmpty()) return this
	return copy(points = points.mapIndexed { index, point ->
		if (index == points.lastIndex) point.copy(x = x, y = y) else point
	})
}

/** How long the finger takes to travel; the last point carries the whole gap. */
fun DragStep.withSwipeDuration(ms: Long): DragStep {
	if (points.isEmpty()) return this
	return copy(points = points.mapIndexed { index, point ->
		if (index == points.lastIndex) point.copy(dt = ms) else point
	})
}
