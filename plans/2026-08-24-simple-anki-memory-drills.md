# Memory Drills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use jja-subagent-dev to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `specs/2026-08-24-simple-anki-memory-drills-design.md`

**Goal:** Add two timed memory-training drills - a grid of random two-digit numbers and a shuffled 52-card deck - each with self-scoring, per-run storage, and a stats table built on the existing Tabulator page.

**Architecture:** One drill implementation serves both disciplines; they differ only in a descriptor that supplies the item generator and the grid geometry. The correctness core - generation, the three-state tap cycle, accuracy, run summarisation, stats-table construction - is pure Kotlin with no Android import, so all of it is covered by JVM tests. The drill grid is Compose; the two stats screens build a `RenderedTable` and hand it to the existing `TableWebView` unchanged. Storage reuses `JsonStore`'s atomic write and quarantine.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose / Material3, `org.json`, the vendored Tabulator 6.3.1 page already in `assets/`. No new dependency.

**Testability Architecture:** Randomness is injected (`kotlin.random.Random`) so a seeded test asserts on an exact generated set. The clock is never read in testable code - the pure layer takes elapsed milliseconds as a parameter and only the composable reads the wall clock. The filesystem seam is the existing `AnkiPaths.at(File)`, so repository tests run against a `TemporaryFolder` on a plain JVM. The existing test mode gains the two run files.

**File Structure:**

```text
app/src/main/java/net/jacoblo/simpleanki/
-data/
--Models.kt                     # MODIFY: ItemStatus, DrillItem, DrillRun, NumbersSettings, PokerSettings, Settings
--AnkiPaths.kt                  # MODIFY: numbersRuns, pokerRuns
--SettingsRepository.kt         # MODIFY: round-trip the two new sections
--SettingsOps.kt                # MODIFY: field validators for the new integer settings
--DrillRunsRepository.kt        # CREATE: load/save one runs file, atomic + quarantine

-drill/
--DrillKind.kt                  # CREATE: the descriptor - name, item count, geometry, runs file
--DrillOps.kt                   # CREATE: pure - generate, tap cycle, tally, accuracy
--DrillStatsTable.kt            # CREATE: pure - runs + sort -> RenderedTable, and the display order
--DrillGrid.kt                  # CREATE: the scrolling cell grid and its tap handling
--DrillScreen.kt                # CREATE: timer, grid, button row, the five-state machine
--RunPicker.kt                  # CREATE: the recent-runs dialog
--DrillStatsScreen.kt           # CREATE: hosts TableWebView over a drill's runs

-DrillRoute.kt                  # CREATE: state ownership, storage, drill <-> stats navigation
-AnkiDrawer.kt                  # MODIFY: Screen.Drill / Screen.DrillStats, four new entries
-MainActivity.kt                # MODIFY: route the new screens
-AppContainer.kt                # MODIFY: the two run repositories
-SettingsScreen.kt              # MODIFY: Numbers and Poker sections

-table/
--TableBridge.kt                # MODIFY: rowTap callback
--TableScreen.kt                # MODIFY: pass a no-op rowTap

-testmode/TestMode.kt           # MODIFY: seed the two run files

app/src/main/assets/table.html  # MODIFY: rowClick handler

app/src/test/java/net/jacoblo/simpleanki/
-DrillOpsTest.kt                # CREATE
-DrillRunsRepositoryTest.kt     # CREATE
-DrillStatsTableTest.kt         # CREATE
-SettingsTest.kt                # MODIFY: round-trip the new sections
```

---

### Task 1: Data types, paths, and settings round-trip

**Files:**
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/Models.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/AnkiPaths.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/SettingsRepository.kt`
- Modify: `app/src/test/java/net/jacoblo/simpleanki/SettingsTest.kt`

**Context:**

Everything downstream needs these types, so they land first. `Models.kt` is the project's home for plain data types and has zero Android imports on purpose - keep it that way. `settings.json` gains two sections; the existing repository already has the pattern for reading a child object with per-key fallbacks and writing it back without disturbing unrelated keys, so follow it exactly rather than inventing a second style.

Poker's item count is FIXED at 52 and is deliberately NOT a setting. Do not add one.

**Architecture:**

```kotlin
enum class ItemStatus { UNSCORED, RIGHT, WRONG }

/**
 * One cell of a drill. [status] is the ONLY state a cell has - whether its value is
 * revealed while scoring is derived from it (UNSCORED hides, the other two reveal),
 * so there is no second flag that could disagree with the mark.
 */
data class DrillItem(val value: String, val status: ItemStatus = ItemStatus.UNSCORED)

data class DrillRun(
	val id: String,
	val startedAt: Long,
	val seconds: Float,
	val items: List<DrillItem>
) {
	val count: Int get() = items.size
	val right: Int get() = items.count { it.status == ItemStatus.RIGHT }
	val wrong: Int get() = items.count { it.status == ItemStatus.WRONG }

	/** right / count. Null for an empty run, which only a hand-edited file can produce. */
	val accuracy: Float? get() = if (count == 0) null else right.toFloat() / count

	/** seconds / count. Null for an empty run. */
	val secondsPerItem: Float? get() = if (count == 0) null else seconds / count
}

data class NumbersSettings(
	val count: Int = 50,
	val columns: Int = 5,
	val cellWidthDp: Int = 64,
	val cellHeightDp: Int = 56
)

/** No count: Poker is one full deck, always 52. */
data class PokerSettings(
	val columns: Int = 6,
	val cellWidthDp: Int = 56,
	val cellHeightDp: Int = 56
)

data class Settings(
	val metronome: MetronomeSettings = MetronomeSettings(),
	val table: TableSettings = TableSettings(),
	val history: HistorySettings = HistorySettings(),
	val counters: CounterSettings = CounterSettings(),
	val numbers: NumbersSettings = NumbersSettings(),
	val poker: PokerSettings = PokerSettings()
)
```

`AnkiPaths` gains two accessors beside the existing ones:

```kotlin
val numbersRuns: File get() = File(root, "numbers-runs.json")
val pokerRuns: File get() = File(root, "poker-runs.json")
```

`settings.json` shape for the two new sections:

```json
{
  "numbers": { "count": 50, "columns": 5, "cellWidthDp": 64, "cellHeightDp": 56 },
  "poker": { "columns": 6, "cellWidthDp": 56, "cellHeightDp": 56 }
}
```

**Requirements:**
- [ ] `Models.kt` stays free of Android imports.
- [ ] Default cell geometry keeps the DEFAULT grids inside 360dp: Numbers 5 x 64 = 320dp, Poker 6 x 56 = 336dp. Verify the arithmetic; do not change the defaults without recomputing it.
- [ ] `SettingsRepository.save` writes both sections through the existing `child(root, name)` helper, so unrelated keys a user hand-added survive a save.
- [ ] `SettingsRepository.load` falls back per key, not per section: a section present but missing one key uses the default for that key only.
- [ ] A section that is present but not a JSON object falls back to all defaults rather than throwing.
- [ ] `SettingsTest` gains a round-trip case for both sections, and a case for a partially-populated section.

**Dependencies:** none.

**Testability:** Pure data plus a repository that already has a `TemporaryFolder` test seam via `AnkiPaths.at(File)`. Assert structurally on the parsed `JSONObject`, NEVER on exact serialized text - the test classpath's `org.json` is `HashMap`-backed and does not preserve key order the way the device's `LinkedHashMap`-backed one does.

**Difficulty:** Low
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 2: DrillOps - the pure correctness core

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/DrillKind.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/DrillOps.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/DrillOpsTest.kt`

**Context:**

This is where the feature is either correct or not. Set generation, the three-state tap cycle, and the accuracy figure all live here, with no Android import anywhere, so the whole core runs under JVM tests.

The tap cycle order is deliberate and is NOT the conventional one: `UNSCORED -> WRONG -> RIGHT -> UNSCORED`. The first tap is the one that reveals the answer, and revealing defaults to "I got this wrong" until the user says otherwise. Do not "fix" this to green-first.

Accuracy is `right / count`. An unscored item counts against the user exactly as a wrong one does. The two remain distinct in storage but are worth the same in the percentage.

**Architecture:**

```kotlin
enum class DrillKind { NUMBERS, POKER }

data class DrillGeometry(val columns: Int, val cellWidthDp: Int, val cellHeightDp: Int)

/** Poker is always 52; Numbers reads its count from settings. */
fun DrillKind.itemCount(settings: Settings): Int

fun DrillKind.displayName(): String        // "Numbers" / "Poker"
fun DrillKind.statsName(): String          // "Numbers Stats" / "Poker Stats"
fun DrillKind.geometry(settings: Settings): DrillGeometry
fun DrillKind.runsFile(paths: AnkiPaths): File

object DrillOps {
	const val DECK_SIZE = 52

	/**
	 * Ranks, and the four SOLID suit glyphs.
	 *
	 * Written as \uXXXX escapes so the source file stays pure ASCII while the glyph
	 * itself reaches the screen. A string resource would deliver the same glyph but
	 * would drag an Android import into this object and cost it its JVM tests.
	 */
	val RANKS: List<String>   // A 2 3 4 5 6 7 8 9 10 J Q K
	val SUITS: List<String>   // "\u2660" "\u2665" "\u2666" "\u2663" - spade heart diamond club

	/** True for hearts and diamonds, which the grid renders in red. */
	fun isRedSuit(value: String): Boolean

	fun generate(kind: DrillKind, count: Int, random: Random): List<DrillItem>

	/** [count] draws from 00..99 WITH replacement, each zero-padded to two characters. */
	fun generateNumbers(count: Int, random: Random): List<DrillItem>

	/** All 52 rank/suit pairs, shuffled. Never fewer, never duplicated. */
	fun generateDeck(random: Random): List<DrillItem>

	/** UNSCORED -> WRONG -> RIGHT -> UNSCORED. */
	fun next(status: ItemStatus): ItemStatus

	/** [items] with the item at [index] advanced one step. Out-of-range returns [items]. */
	fun cycle(items: List<DrillItem>, index: Int): List<DrillItem>

	/**
	 * Whether a cell's VALUE is visible.
	 *
	 * Outside scoring every value shows. While scoring, only a marked one does - which is
	 * why status is the single source of truth and no reveal flag is stored.
	 */
	fun isRevealed(status: ItemStatus, scoring: Boolean): Boolean
}
```

**Requirements:**
- [ ] `generateNumbers` draws with replacement; a seeded test must show a duplicate is possible and is not filtered out.
- [ ] Numbers are zero-padded to exactly two characters, so `7` renders `07`.
- [ ] `generateDeck` returns exactly 52 items, every rank/suit pair exactly once. Assert the SET, not just the size - a shuffle bug that duplicates one card and drops another keeps the size correct.
- [ ] A card's value is its rank followed by its suit glyph, e.g. `A♠`, `10♦`.
- [ ] The suit glyphs are written as `\uXXXX` escapes. NO literal non-ASCII byte may appear in any `.kt` file - verify with `grep -P '[^\x00-\x7F]' ` over the sources and expect no match.
- [ ] `isRedSuit` is true for hearts and diamonds only, and is driven by the glyph rather than by position in `SUITS`.
- [ ] Both generators take an injected `Random` and are deterministic for a fixed seed.
- [ ] `next` implements `UNSCORED -> WRONG -> RIGHT -> UNSCORED` and is exhaustive over the enum.
- [ ] `cycle` returns a NEW list and does not mutate its argument.
- [ ] `isRevealed(UNSCORED, scoring = true)` is false; `isRevealed(anything, scoring = false)` is true.
- [ ] The accuracy test data MUST make `right/count` and `right/(right+wrong)` disagree - e.g. 3 right, 1 wrong, 6 unscored gives 30% under the correct definition and 75% under the wrong one. A test whose numbers agree under both proves nothing.
- [ ] The cycle test asserts BOTH the mark and the visibility at every one of the four steps.

**Dependencies:** Task 1.

**Testability:** Every function is pure and returns a value. Seeded `Random` gives exact expected sets. No clock, no filesystem, no Android import - `DrillKind.runsFile` takes an `AnkiPaths`, which is itself Android-free apart from its two factory methods.

**Difficulty:** Medium
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 3: DrillRunsRepository

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/DrillRunsRepository.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/DrillRunsRepositoryTest.kt`

**Context:**

One instance per drill, each pointed at its own file. Mirrors `HistoryRepository` closely - read the whole file, tolerate a bad record, quarantine a document that is not a parseable array at all - because the two files serve the same purpose and a second style here would be gratuitous.

Read `HistoryRepository.kt` before starting. In particular, copy the `store.isUnreadable()` refusal in `save`: without it, a load that failed transiently returns an empty list and the next save writes that empty list over every stored run.

**Architecture:**

```kotlin
/**
 * One drill's run file.
 *
 * @param file the runs file, from DrillKind.runsFile. Taken directly rather than as an
 *   AnkiPaths plus a kind, so the type says which file this instance owns.
 */
class DrillRunsRepository(private val file: File) {

	/** Never throws. An unparseable document is quarantined and an empty list returned. */
	fun load(): List<DrillRun>

	/**
	 * Writes the newest [maxEntries] runs, oldest first.
	 * @throws IOException when the file cannot be written, or something unreadable is at
	 *   that path - see HistoryRepository.save for why the second case must refuse.
	 */
	fun save(runs: List<DrillRun>, maxEntries: Int)

	companion object {
		/** [runs] with [run] appended, or replaced in place when its id already exists. */
		fun upsert(runs: List<DrillRun>, run: DrillRun): List<DrillRun>
	}
}
```

Wire format is a bare JSON array, matching `history.json`:

```json
[
  {
    "id": "1756000000000",
    "startedAt": 1756000000000,
    "seconds": 83.4,
    "items": [
      { "value": "07", "status": "" },
      { "value": "42", "status": "wrong" },
      { "value": "91", "status": "right" }
    ]
  }
]
```

`status` on the wire is `""` / `"right"` / `"wrong"`, mapping to `UNSCORED` / `RIGHT` / `WRONG`. An unrecognised status string reads back as `UNSCORED` rather than failing the record - these files are hand-editable.

**Requirements:**
- [ ] `load` on an absent file returns an empty list and does NOT create the file. An empty run file is a user who has not drilled yet, not an error.
- [ ] A document that is not a parseable JSON array is quarantined via `JsonStore.quarantine()` and an empty list is returned.
- [ ] A single malformed RECORD inside a valid array is skipped; the rest survive.
- [ ] An unrecognised `status` string reads back as `UNSCORED`.
- [ ] `save` refuses to write when `JsonStore.isUnreadable()` is true, throwing rather than clobbering.
- [ ] `save` keeps the newest `maxEntries`, using `takeLast` on a list held oldest-first.
- [ ] `upsert` replaces by `id` when present and appends otherwise, preserving position on replace.
- [ ] Tests use `AnkiPaths.at(TemporaryFolder)` and assert structurally on re-parsed JSON, never on exact serialized text.

**Dependencies:** Tasks 1, 2.

**Testability:** Real files in a `TemporaryFolder`, real `JsonStore`. Round-trip a run, corrupt a file and assert the `.corrupt` quarantine exists, and assert the cap by saving more than `maxEntries`.

**Difficulty:** Medium
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 4: DrillStatsTable

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/DrillStatsTable.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/DrillStatsTableTest.kt`

**Context:**

Turns a list of runs into the `RenderedTable` the existing Tabulator page already knows how to draw. This is what the spec's "reuse the table view JS" amounts to: `TableWebView` takes a `RenderedTable` and knows nothing about where its rows came from, so nothing in the existing table stack needs to change for the drill stats screens to render.

Read `RenderedTable.kt` first. Every cell is a finished string; the renderer sorts nothing and formats nothing.

The columns are FIXED. These tables do not appear in `views.json`, have no column sheet, and take no computed columns.

**Architecture:**

```kotlin
object DrillStatsTable {
	const val ID_INDEX = "#"
	const val ID_WHEN = "When"
	const val ID_TIME = "Time"
	const val ID_COUNT = "Count"
	const val ID_RIGHT = "Right"
	const val ID_WRONG = "Wrong"
	const val ID_ACCURACY = "Accuracy"
	const val ID_SEC_PER_ITEM = "Sec/Item"

	val DEFAULT_SORT = SortSpec(ID_WHEN, SortDir.DESC)

	/**
	 * The runs in display order for [sort].
	 *
	 * Public because a row tap arrives as a display index and has to be mapped back to a
	 * run. [render] MUST call this rather than ordering rows itself, or a tap on the
	 * stats table opens the wrong run the moment the two orderings drift.
	 */
	fun order(runs: List<DrillRun>, sort: SortSpec): List<DrillRun>

	fun render(
		runs: List<DrillRun>,
		kind: DrillKind,
		sort: SortSpec,
		highlightEvery: Int,
		zone: ZoneId = ZoneId.systemDefault()
	): RenderedTable

	/** Tapping a column sorts it descending; tapping the sorted one reverses it. */
	fun nextSort(current: SortSpec, columnId: String): SortSpec
}
```

Formatting, per column:

- `#` - the display position, 1-based
- `When` - `MM-dd HH:mm:ss`, the same pattern `TableEngine` uses for its `When`
- `Time` - `mm:ss`, whole seconds, matching the drill screen's timer
- `Count`, `Right`, `Wrong` - integers
- `Accuracy` - percent, e.g. `80%`
- `Sec/Item` - two decimals
- A null `accuracy` or `secondsPerItem` renders `TableEngine.EMPTY_CELL`

**Requirements:**
- [ ] `render` calls `order` for its row ordering. Do not duplicate the comparator.
- [ ] `#` is assigned AFTER sorting, so it numbers display position rather than storage position.
- [ ] Sorting is stable, and a null-valued row sorts last in BOTH directions (see `TableEngine.nullsLast` for the established rule).
- [ ] Default sort is `When` descending - newest run first.
- [ ] The `#` column is not sortable, matching `TableEngine`.
- [ ] `When` is frozen so it stays visible while the table scrolls sideways.
- [ ] Tests pin the `ZoneId` so assertions do not depend on the machine's timezone.
- [ ] A test asserts that `order(runs, sort)[i]` is the run whose figures appear in `render(...).rows[i]` - this is the invariant a row tap depends on.
- [ ] An empty run list renders a table with columns and zero rows, not an exception.

**Dependencies:** Tasks 1, 2.

**Testability:** Pure - runs in, a `RenderedTable` out. No Android import, so `RenderedTable`'s existing test conventions apply directly.

**Difficulty:** Medium
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 5: Row tap through the table bridge

**Files:**
- Modify: `app/src/main/java/net/jacoblo/simpleanki/table/TableBridge.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/table/TableScreen.kt`
- Modify: `app/src/main/assets/table.html`

**Context:**

The ONLY change this feature makes to existing shared code. A tap on a stats row has to reach Kotlin, and the bridge is the only way out of the WebView.

`table.html` is shared by the three flip-card views and by both drill stats screens. Those three must be behaviourally unchanged: they pass a no-op handler, so a row tap there does nothing, exactly as today.

Read the XSS notes around `buildColumns` in `table.html` before touching it. A row handler does not build markup and so is not a new sink, but the reasoning there is what the file expects a contributor to have read.

**Architecture:**

`TableBridge` gains one constructor parameter and one method, following the existing shape - every callback hops to the main thread inside the bridge, never at the call site:

```kotlin
private val onRowTap: (index: Int) -> Unit

/**
 * @param index the row's position in the payload, which is its DISPLAY position:
 *   Kotlin sends rows already in display order and the page never re-sorts them. The
 *   same fact rowFormatter's banding already relies on.
 */
@JavascriptInterface
fun rowTap(index: Int) {
	main.post { onRowTap(index) }
}
```

In `table.html`, beside the existing `columnResized` and `columnMoved` handlers:

```javascript
table.on("rowClick", function (e, row) {
  Android.rowTap(row.getData()._i);
});
```

`TableScreen` passes `onRowTap = {}` - the flip-card views have nothing to open.

**Requirements:**
- [ ] The three existing views behave exactly as before. Verify by tapping rows in History and confirming nothing happens and nothing is logged as an error.
- [ ] `rowTap` posts to the main thread like every other bridge method.
- [ ] `_i` is used, not Tabulator's own row position - they agree today only because Kotlin pre-sorts, and `_i` is the one that stays correct.
- [ ] Adding the handler must not break header-tap sorting or the header context menu. A row click and a header click are different targets; confirm both still fire.

**Dependencies:** none (can be done any time before Task 7).

**Testability:** The bridge method is one line and untestable off-device by design. Verify on the device: tap a row in a drill stats table and confirm the run opens; tap a row in History and confirm nothing changes. Check logcat for the existing `SimpleAnkiTable` tag.

**Difficulty:** Low
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 6: The drill grid and the drill screen

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/DrillGrid.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/DrillScreen.kt`

**Context:**

The biggest task. The grid, the timer, the five buttons, and the state machine that connects them.

Read the spec's "Run lifecycle" table before writing anything - the button enablement matrix is exact and is the specification of this screen.

The grid is Compose, not the Tabulator page. Cells are tap targets whose size the user chooses, and a bridge round-trip per tap would be the wrong shape entirely.

**Architecture:**

```kotlin
enum class DrillState { FRESH, RUNNING, FINISHED, EDITING, PAST_RUN }

/**
 * The cell grid.
 *
 * Scrolls in both directions rather than shrinking cells: the user chose the cell size in
 * settings, so a grid too wide for the viewport scrolls sideways instead of overriding it.
 *
 * @param scoring whether cells are scoring cells. Drives DrillOps.isRevealed, which is
 *   the only thing that decides whether a value shows.
 * @param onTap null when cells are not tappable, which is every state but EDITING and
 *   PAST_RUN.
 */
@Composable
fun DrillGrid(
	items: List<DrillItem>,
	geometry: DrillGeometry,
	scoring: Boolean,
	redValue: (String) -> Boolean,
	onTap: ((index: Int) -> Unit)?,
	modifier: Modifier = Modifier
)

/**
 * @param openRun non-null when a stored run is being re-scored. Its items and duration
 *   are shown and Start/Done are dead - the run's time is history.
 * @param onDone raised when the clock stops, with the finished run. The caller stores it.
 * @param onItemsChanged raised on every scoring tap, with the run as it now stands.
 */
@Composable
fun DrillScreen(
	kind: DrillKind,
	settings: Settings,
	openRun: DrillRun?,
	onDone: (DrillRun) -> Unit,
	onItemsChanged: (DrillRun) -> Unit,
	onOpenPicker: () -> Unit,
	onCloseRun: () -> Unit,
	modifier: Modifier = Modifier
)
```

Layout, top to bottom: the timer (`mm:ss`), the live tally while scoring (`40/50 = 80%`), the grid filling the remaining height, and the button row `Start | Done | New | Edit | Runs`.

The clock is wall-clock based: record `System.currentTimeMillis()` on Start and derive elapsed from it, so a recomposition cannot drift it. Tick with a `LaunchedEffect` that delays and recomputes; do not accumulate.

Backgrounding while `RUNNING` returns to `FRESH` holding the SAME items with the clock zeroed - observe the lifecycle exactly as `MainActivity` already does for `isResumed`.

**Requirements:**
- [ ] The button enablement matrix in the spec is implemented exactly. A disabled button is disabled, not hidden.
- [ ] `New` is enabled in every state, discards silently with no confirmation, and returns to `FRESH` with a freshly generated set.
- [ ] Entering the screen generates a set and lands in `FRESH` with the full grid drawn and every cell EMPTY - the shape is visible, no value is.
- [ ] `Start` is the only thing that reveals a set.
- [ ] `Done` freezes the clock and raises `onDone` once. Pressing it cannot be made to fire twice.
- [ ] `Edit` toggles scoring on and off; toggling off returns to the `FINISHED` view with values revealed.
- [ ] Every scoring tap raises `onItemsChanged` with the updated run.
- [ ] The live tally shows only while scoring.
- [ ] Backgrounding while `RUNNING` re-covers the SAME set and zeroes the clock. Backgrounding in any other state changes nothing.
- [ ] Hearts and diamonds render red; the grid takes that as a predicate rather than knowing about cards.
- [ ] Timer displays `mm:ss`; the value stored on the run is float seconds.
- [ ] The grid scrolls both ways when it overflows and never shrinks a cell below its configured size.

**Dependencies:** Tasks 1, 2.

**Testability:** The composables are Android and are verified on the device. Everything they decide - generation, the cycle, visibility, the tally - is already covered by `DrillOpsTest`, so this task must add NO new logic of its own: if a rule needs a test, it belongs in `DrillOps`, not here. Verify on device against the spec's state table, one state at a time.

**Difficulty:** High
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 7: Run picker and drill stats screen

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/RunPicker.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/drill/DrillStatsScreen.kt`

**Context:**

Two routes reach a stored run: the picker on the drill screen, and a row tap on the stats table. Both open the same thing.

`DrillStatsScreen` is a thin host: build a `RenderedTable` with `DrillStatsTable.render`, hand it to the existing `TableWebView`, and map a row tap back to a run through `DrillStatsTable.order`.

Read `TableScreen.kt` for how a bridge is remembered across recomposition - the same `rememberUpdatedState` discipline applies here, and getting it wrong means the bridge reads stale runs.

**Architecture:**

```kotlin
/** The 50 most recent runs, newest first. The stats screen holds everything. */
const val PICKER_LIMIT = 50

@Composable
fun RunPicker(
	runs: List<DrillRun>,
	onPick: (DrillRun) -> Unit,
	onDismiss: () -> Unit
)

@Composable
fun DrillStatsScreen(
	kind: DrillKind,
	runs: List<DrillRun>,
	tableSettings: TableSettings,
	onOpenRun: (DrillRun) -> Unit,
	onRendered: (RenderedTable) -> Unit,
	modifier: Modifier = Modifier
)
```

Each picker line shows when the run was, how long it took, and its accuracy.

**Requirements:**
- [ ] The picker lists the newest `PICKER_LIMIT` runs, newest first.
- [ ] An empty run list shows a "no runs yet" line rather than an empty dialog.
- [ ] `DrillStatsScreen` holds the sort as state and re-renders on a header tap, via `DrillStatsTable.nextSort`.
- [ ] A row tap maps through `DrillStatsTable.order` with the CURRENT sort. Mapping against the unsorted list opens the wrong run.
- [ ] An out-of-range tap index is ignored rather than crashing.
- [ ] The bridge is remembered once and reads current state through `rememberUpdatedState`, matching `TableScreen`.
- [ ] `onRendered` is wired to the existing test-mode dump, as `TableScreen` does.
- [ ] The five view-editing bridge callbacks (`resize`, `reorder`, `hide`, `freeze`, `move`) are no-ops here - these columns are fixed.

**Dependencies:** Tasks 3, 4, 5.

**Testability:** The row-tap mapping is the risk and it is already pure and tested in `DrillStatsTableTest`. On device, sort by Accuracy, tap a row, and confirm the run that opens is the one whose figures were on that row - the failure mode is off-by-one against the unsorted list, which is invisible under the default sort.

**Difficulty:** Medium
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 8: DrillRoute, container, and navigation

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/DrillRoute.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/AppContainer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/AnkiDrawer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`

**Context:**

Wires the pieces together. `DrillRoute` is to the drill screens what `TableRoute` is to the table screen: it owns the loaded runs, persists every change, and translates storage failures into toasts. Read `TableRoute.kt` first and follow it - in particular its "screen first, then disk" rule and the toast it raises on a failed write.

**Architecture:**

```kotlin
sealed interface Screen {
	data object FlipCards : Screen
	data class Table(val viewId: String) : Screen
	data class Drill(val kind: DrillKind, val openRunId: String? = null) : Screen
	data class DrillStats(val kind: DrillKind) : Screen
	data object Settings : Screen
}

@Composable
fun DrillRoute(
	container: AppContainer,
	kind: DrillKind,
	openRunId: String?,
	onSelect: (Screen) -> Unit
)
```

`AppContainer` gains the two repositories, built from `DrillKind.runsFile(paths)`.

Drawer order - each drill beside its own stats:

```text
Flip Cards
---
Numbers / Numbers Stats / Poker / Poker Stats
---
(the stored table views)
---
Settings
```

**AUTOSAVE MUST BE DEBOUNCED.** The spec says every scoring tap autosaves, and taken literally that rewrites the entire runs file per tap - at the 5000-run cap that is roughly 10 MB of JSON on the main thread for each tap. Coalesce instead: hold the run in memory, and write after about 400 ms of quiet, on a background dispatcher. Flush immediately when the run closes, when the screen leaves, and on `ON_PAUSE`. Nothing is ever pending for more than a moment, and no tap blocks on I/O.

**Requirements:**
- [ ] Runs load on entering a drill or stats screen, and on resume.
- [ ] `onDone` appends the finished run and persists it - a run is stored the moment the clock stops, before any scoring.
- [ ] `onItemsChanged` upserts by id through `DrillRunsRepository.upsert`.
- [ ] The autosave is debounced at about 400 ms and runs off the main thread, and is force-flushed on run close, screen leave, and `ON_PAUSE`.
- [ ] A failed write raises a toast and leaves the screen ahead of disk, matching `TableRoute`.
- [ ] `Screen.Drill(kind, openRunId)` with an id that names no stored run falls back to a fresh live drill rather than showing an empty grid.
- [ ] The drawer highlights the drill entry regardless of `openRunId`.
- [ ] The column-sheet top-bar action is null on all four new screens.
- [ ] The lifetime review counter is NOT advanced by drills.
- [ ] The metronome is untouched - it is already gated to `Screen.FlipCards`. Confirm the gate still holds with the new screens present.

**Dependencies:** Tasks 3, 6, 7.

**Testability:** Wiring, verified on device. The one behaviour worth deliberate checking is the debounced autosave: score several cells rapidly, background the app, and confirm the run file on disk holds every mark - a flush that misses the last tap is the failure mode, and it is invisible until the next load.

**Difficulty:** Medium
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 9: Settings sections

**Files:**
- Modify: `app/src/main/java/net/jacoblo/simpleanki/SettingsScreen.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/SettingsOps.kt`
- Modify: `app/src/test/java/net/jacoblo/simpleanki/SettingsScreenTest.kt`

**Context:**

Two new sections, "Numbers" and "Poker". Follow the existing `SectionHeader` + `ValidatedField` pattern exactly; the validators belong in `SettingsOps`, which is pure and tested, and the composable only wires fields to them.

**Architecture:**

Numbers: item count, column count, cell width, cell height. Poker: column count, cell width, cell height - and no item count, because Poker is fixed at 52.

A validator per field, in the shape `SettingsOps` already uses:

```kotlin
fun parseItemCount(text: String): FieldResult<Int>     // 1..1000
fun parseColumnCount(text: String): FieldResult<Int>   // 1..20
fun parseCellSizeDp(text: String): FieldResult<Int>    // 16..200
```

**Requirements:**
- [ ] Bounds are enforced with a message saying why, not silently clamped.
- [ ] A refused field shows inline and is not written, so nothing malformed reaches `settings.json` through the UI.
- [ ] Each accepted keystroke persists immediately, matching the rest of the screen - there is no save button.
- [ ] No item-count field appears under Poker.
- [ ] `SettingsOps` gains tests for each validator's boundaries, including both rejected sides.

**Dependencies:** Task 1.

**Testability:** The validators are pure and JVM-tested. The composable is wiring.

**Difficulty:** Low
Recommend Engineer to finish this task: `Claude Opus`

---

### Task 10: Test-mode fixtures and end-to-end verification

**Files:**
- Modify: `app/src/main/java/net/jacoblo/simpleanki/testmode/TestMode.kt`
- Modify: `app/src/test/java/net/jacoblo/simpleanki/TestModeTest.kt`

**Context:**

Test mode redirects every path to `/sdcard/SimpleAnki-test/` and seeds fixtures there. It gains the two run files so a drill can be driven against known data without touching production.

Read `TestMode.kt` first. Seeding runs once per launch and only with storage access.

**A HARD RULE, learned the expensive way on this project.** A previous agent resumed the app WITHOUT `--ez test_mode true` and wrote a fake record into the user's real history. Every device run in this task MUST pass `--ez test_mode true`, and any verification against production data must be read-only.

**Requirements:**
- [ ] `TestMode.seed` writes both run files with a small, known set of runs.
- [ ] Seeded runs cover the cases that matter: an unscored run, a fully-scored run, and a partially-scored one - so the stats table's 0% case is visible without drilling.
- [ ] The seed is deterministic.
- [ ] `TestModeTest` asserts both files are seeded and parse back through `DrillRunsRepository`.
- [ ] Full suite green, including the 327 tests that already pass.
- [ ] On device, under test mode, walk the spec's state table for both drills and confirm each transition.
- [ ] Confirm the three existing table views are unchanged by Task 5.

**Dependencies:** all previous tasks.

**Testability:** This task IS the verification step.

**Difficulty:** Medium
Recommend Engineer to finish this task: `Claude Opus`

---

## Spec coverage check

| Spec requirement | Task |
|---|---|
| Numbers: count settable, 00-99 with replacement, zero-padded | 1, 2 |
| Poker: fixed 52, one deck, rank + suit glyph, red hearts/diamonds | 2, 6 |
| Timer `mm:ss`, stored as float seconds | 6 |
| Grid: settable columns and cell size, scrolls both ways | 1, 6 |
| Five states and the button matrix | 6 |
| Start is the only reveal | 6 |
| Done stores the run immediately, unscored | 6, 8 |
| Tap cycle UNSCORED -> WRONG -> RIGHT -> UNSCORED, tap 3 re-hides | 2, 6 |
| Accuracy = right / count | 2, 4 |
| Live tally while scoring | 6 |
| Autosave on every tap (debounced) | 8 |
| New discards silently from any state | 6 |
| Backgrounding a running drill re-covers the same set | 6 |
| Two run files, bare array, hand-editable, 5000 cap | 1, 3 |
| Quarantine and atomic write | 3 |
| Stats columns, formatting, sorting, default newest-first | 4 |
| Stats reuses the Tabulator page unchanged | 4, 7 |
| Row tap opens a run | 5, 7 |
| Run picker, 50 most recent, both routes | 7 |
| Past run: Start/Done dead, New escapes | 6, 8 |
| Four drawer entries, each drill beside its stats | 8 |
| Counter stays flip-card only; metronome unaffected | 8 |
| Settings sections, no Poker item count | 1, 9 |
| Test-mode fixtures | 10 |
