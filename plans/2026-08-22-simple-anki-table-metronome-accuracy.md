# Simple Anki: Unified Table View, Metronome, and Pivot Accuracy - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use jja-subagent-dev to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `specs/2026-08-22-simple-anki-table-metronome-accuracy-design.md`

**Goal:** Replace the four icon buttons and three screens with a drawer plus one config-driven table view, add a per-card metronome that records timeouts, and add generic pivot/window computed columns of which accuracy is one instance.

**Architecture:** All display logic funnels through one pure function, `TableEngine.render(history, deckQuestions, view, sort) -> RenderedTable`, which filters, sorts, computes partitioned aggregates, collapses duplicates, and formats every cell to a string. Rendering is Tabulator inside a WebView that receives a pre-formatted payload over a `WebViewAssetLoader` virtual URL and owns only the resize and reorder gestures. Everything outside the WebView and the Android framework is a pure Kotlin object with no Android imports, wired together by a hand-rolled `AppContainer`; there is no ViewModel because the metronome's entire lifecycle rule is expressed by `LaunchedEffect` key cancellation.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.09.00, unchanged), Material3, `androidx.webkit:webkit` for `WebViewAssetLoader`, Tabulator 6.x vendored into assets (MIT), `org.json` from the framework, `SoundPool` for audio. No serialization library and no ViewModel are added.

**Testability Architecture:** `AnkiPaths` is the single class that touches `Environment`, and its `at(File)` factory lets JVM tests run the whole repository layer over a `TemporaryFolder`. All computation - `Aggregates`, `MemberSelector`, `FormulaParser`, `FormulaWriter`, `TableEngine` - is pure and has zero Android imports, so it is asserted directly on the `RenderedTable` return value with no emulator. The two genuinely external boundaries get interfaces with fakes: `ClickPlayer` has `SoundPoolClickPlayer` and `NoOpClickPlayer`, and time is controlled not by a fake clock but by `metronome.intervalSeconds`, which a test fixture sets to `0.3`. On-device verification reads `dump.json`, the serialized `RenderedTable` written after every render under test mode.

**File Structure:**

```text
simple-anki/
-app/build.gradle                          # MODIFY: add androidx.webkit dependency
-gradle/libs.versions.toml                 # MODIFY: add webkit version and library entry

-app/src/main/assets/
--tabulator/
---tabulator.min.js                        # CREATE: vendored Tabulator 6.x, MIT
---tabulator.min.css                       # CREATE: vendored Tabulator stylesheet
--table.html                               # CREATE: WebView host page, fetches payload, builds the grid
--click.wav                                # CREATE: synthesized 20ms metronome click

-app/src/main/java/net/jacoblo/simpleanki/
--MainActivity.kt                          # MODIFY: activity, AppContainer, drawer scaffold, screen dispatch
--GameView.kt                              # CREATE: flip-card screen extracted from MainActivity
--AppContainer.kt                          # CREATE: manual DI, chooses real or fake ClickPlayer and paths
--StatsScreen.kt                           # DELETE: replaced by the stats TableView
--HistoryScreen.kt                         # DELETE: replaced by the history TableView
--QuestionsScreen.kt                       # DELETE: tile grid removed, becomes the list_rows TableView

--data/
---Models.kt                               # CREATE: AnkiCard, HistoryEntry, TableView, ColumnSpec, Settings
---AnkiPaths.kt                            # CREATE: resolves the data dir; only file that touches Environment
---JsonStore.kt                            # CREATE: read, write, quarantine one JSON file
---DeckRepository.kt                       # CREATE: load simple-anki.json, create the sample deck
---HistoryRepository.kt                    # CREATE: load, append, trim history.json; runs the migration
---SettingsRepository.kt                   # CREATE: settings.json with defaults and recovery
---ViewsRepository.kt                      # CREATE: views.json with defaults and recovery
---DefaultViews.kt                         # CREATE: the three built-in TableView constants

--table/
---RenderedTable.kt                        # CREATE: RenderedTable, RenderedColumn, output types
---Aggregates.kt                           # CREATE: pure MIN MAX AVG MEDIAN SUM COUNT ACCURACY STDDEV
---MemberSelector.kt                       # CREATE: pure group, bucket, and rolling partitioning
---FormulaParser.kt                        # CREATE: pure formula string to ComputedSpec
---FormulaWriter.kt                        # CREATE: pure ComputedSpec to formula string
---TableEngine.kt                          # CREATE: pure render pipeline
---TableScreen.kt                          # CREATE: composable host for the WebView
---ColumnSheet.kt                          # CREATE: column visibility and view lifecycle bottom sheet
---TableWebView.kt                         # CREATE: AndroidView wrapper, WebViewAssetLoader wiring
---TableBridge.kt                          # CREATE: @JavascriptInterface, JS to Kotlin
---PayloadPathHandler.kt                   # CREATE: serves the row payload as a virtual URL

--metronome/
---ClickPlayer.kt                          # CREATE: interface, SoundPool impl, no-op fake
---MetronomeEffect.kt                      # CREATE: composable per-card countdown

--testmode/
---TestMode.kt                             # CREATE: activation, fixture seeding, dump.json

-app/src/test/java/net/jacoblo/simpleanki/
--AggregatesTest.kt                        # CREATE: aggregate math, timeout exclusion, empty partitions
--MemberSelectorTest.kt                    # CREATE: group, bucket, rolling boundary conditions
--FormulaTest.kt                           # CREATE: parse, write, round-trip, error cases
--MigrationTest.kt                         # CREATE: history.json without timedOut to with
--RepositoryTest.kt                        # CREATE: defaults, corrupt recovery, trimming
--TableEngineTest.kt                       # CREATE: full pipeline over a temp dir
--ExampleUnitTest.kt                       # DELETE: template stub
```

---

## Task 1: Data Models and File Access Seam

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/Models.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/AnkiPaths.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/JsonStore.kt`

**Context:**
Today six top-level functions in `MainActivity.kt` each call `Environment.getExternalStorageDirectory()` directly, which makes every one of them impossible to test off-device and impossible to redirect for test mode. This task creates the seam that the entire rest of the plan depends on: one class that knows where files live, one class that reads and writes a single JSON file safely, and the shared data types. Nothing is wired up yet and no behaviour changes.

`AnkiPaths.at(File)` is the critical piece. It takes a root directory and touches no Android API, so every JVM test in this plan constructs one over a JUnit `TemporaryFolder` and exercises real repository code with no emulator.

**Architecture:**

```kotlin
// Models.kt
package net.jacoblo.simpleanki.data

data class AnkiCard(val question: String, val answer: String)

data class HistoryEntry(
	val question: String,
	val answer: String,
	val timeTaken: Float,
	val timestamp: Long,
	val timedOut: Boolean
)

enum class ColumnType { TEXT, NUMBER, TIME, BOOL }

enum class CellFormat { TEXT, INT, ONE_DP, TWO_DP, PERCENT, TIME }

enum class Aggregate { MIN, MAX, AVG, MEDIAN, SUM, COUNT, ACCURACY, STDDEV }

sealed interface Partition {
	data class Group(val by: String) : Partition
	data class Bucket(val size: Int) : Partition
	data class Rolling(val size: Int) : Partition
}

data class ComputedSpec(
	val aggregate: Aggregate,
	val source: String,
	val partition: Partition,
	val limit: Int
)

data class ColumnSpec(
	val id: String,
	val title: String,
	val width: Int,
	val visible: Boolean = true,
	val frozen: Boolean = false,
	val format: CellFormat? = null,
	val computed: ComputedSpec? = null,
	val formula: String? = null,
	val formulaError: String? = null
)

enum class SortDir { ASC, DESC }

data class SortSpec(val column: String, val dir: SortDir)

data class TableView(
	val id: String,
	val name: String,
	val filterToCurrentDeck: Boolean,
	val collapseDuplicatesOn: String?,
	val highlightEvery: Int,
	val defaultSort: SortSpec,
	val columns: List<ColumnSpec>
)

data class MetronomeSettings(
	val enabled: Boolean = false,
	val intervalSeconds: Float = 10.0f,
	val soundPath: String? = null
)

data class TableSettings(
	val defaultLimit: Int = 10,
	val highlightEvery: Int = 5,
	val defaultWindowSize: Int = 100
)

data class HistorySettings(val maxEntries: Int = 5000)

data class Settings(
	val metronome: MetronomeSettings = MetronomeSettings(),
	val table: TableSettings = TableSettings(),
	val history: HistorySettings = HistorySettings()
)
```

```kotlin
// AnkiPaths.kt
package net.jacoblo.simpleanki.data

import java.io.File

/** Resolves every file the app reads or writes. The only class aware of Environment. */
class AnkiPaths(val root: File) {
	val deck: File get() = File(root, "simple-anki.json")
	val history: File get() = File(root, "history.json")
	val historyBackup: File get() = File(root, "history.json.bak")
	val settings: File get() = File(root, "settings.json")
	val views: File get() = File(root, "views.json")
	val dump: File get() = File(root, "dump.json")

	/** Creates the root directory if absent. Safe to call repeatedly. */
	fun ensureRoot()

	companion object {
		/** /sdcard/SimpleAnki - touches Environment. */
		fun production(): AnkiPaths

		/** /sdcard/SimpleAnki-test - touches Environment. */
		fun testMode(): AnkiPaths

		/** Arbitrary root. Touches no Android API; this is the JVM test seam. */
		fun at(root: File): AnkiPaths = AnkiPaths(root)
	}
}
```

```kotlin
// JsonStore.kt
package net.jacoblo.simpleanki.data

import java.io.File

/** Reads and writes one JSON file, quarantining it when it cannot be parsed. */
class JsonStore(private val file: File) {
	/** File contents, or null when the file is missing or unreadable. */
	fun readOrNull(): String?

	/**
	 * Writes atomically via a temp file plus rename.
	 * @throws IOException when the file cannot be written.
	 */
	fun write(text: String)

	/**
	 * Renames the file to "<name>.corrupt", overwriting any previous quarantine.
	 * Returns false when there was no file to move, or when the rename failed.
	 */
	fun quarantine(): Boolean

	fun exists(): Boolean
}
```

`ColumnSpec.formula` is a passthrough field carrying the human-readable mirror of `computed`. It lives here, in Task 1, rather than arriving with the parser in Task 12, because Task 8 must round-trip every field of `views.json` and lands first - without this field Task 8 would silently drop a hand-written formula on load and save. Task 1 stores it and nothing more; generating and parsing it is Task 12's job.

**Requirements:**
- [ ] `Models.kt` contains every type above with no Android imports
- [ ] `AnkiPaths.at(File)` has no Android imports on its code path, so it is constructible in a JVM test
- [ ] `AnkiPaths.production()` resolves `/sdcard/SimpleAnki`, `testMode()` resolves `/sdcard/SimpleAnki-test`
- [ ] `ensureRoot()` creates the directory and does not throw when it already exists
- [ ] `JsonStore.readOrNull()` returns null rather than throwing for a missing or unreadable file
- [ ] `JsonStore.quarantine()` overwrites any existing `.corrupt` file rather than failing
- [ ] `JsonStore.write()` is atomic: it writes a temp file and renames, so a process kill cannot truncate the target. Task 3 rewrites `history.json` in full on every card flip, so this path runs hundreds of times per session. Process-kill safety only; no `fsync`
- [ ] Nothing in `MainActivity.kt` is modified by this task

**Dependencies:** None.

**Testability:** `AnkiPaths.at(File)` is the injection point the whole plan hangs off. Verified by a JVM test that constructs `AnkiPaths.at(tempFolder.root)`, writes through a `JsonStore`, reads it back, corrupts it, and asserts that `quarantine()` produced a `.corrupt` file and that `readOrNull()` on a missing file returns null.

**Difficulty:**
Low
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 2: History and Deck Repositories with Timeout Migration

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/HistoryRepository.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/DeckRepository.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/MigrationTest.kt`

**Context:**
`history.json` becomes the single source of truth for the whole app; `stats.json` is deleted in Task 3 and every per-card figure is derived from history instead. To carry the new failure signal, each record gains a `timedOut` boolean. Existing files on the user's device have no such key, so a one-time migration infers it.

The migration rule is that a record without a `timedOut` key gets `timedOut = (timeTaken >= 10.0)`. The 10.0 threshold is a hardcoded constant and must NOT be read from settings, because it describes attempts that already happened under the old fixed behaviour, not the user's current metronome interval. The presence of the key is itself the migration marker, so no schema version field is needed in this file.

`timeTaken` stays positive in every case. For a timeout it holds the interval that elapsed. Nothing anywhere may test the sign of `timeTaken` to detect failure; `timedOut` is the only failure signal.

**Architecture:**

```kotlin
// HistoryRepository.kt
package net.jacoblo.simpleanki.data

class HistoryRepository(private val paths: AnkiPaths) {

	/**
	 * Loads all records oldest-first, running the timeout migration on first
	 * encounter with a pre-migration file.
	 */
	fun load(): List<HistoryEntry>

	/** Appends one record and trims to the newest [maxEntries] before writing. */
	fun append(entry: HistoryEntry, maxEntries: Int): List<HistoryEntry>

	fun save(entries: List<HistoryEntry>, maxEntries: Int)

	companion object {
		/** Threshold for inferring timedOut on pre-migration records. Never configurable. */
		const val LEGACY_TIMEOUT_SECONDS = 10.0f

		/**
		 * Returns null when no migration is needed, otherwise the migrated list.
		 * Pure: takes and returns parsed JSON text so it is directly unit-testable.
		 */
		fun migrate(rawJson: String): String?
	}
}
```

```kotlin
// DeckRepository.kt
package net.jacoblo.simpleanki.data

class DeckRepository(private val paths: AnkiPaths) {
	fun load(): List<AnkiCard>

	/** Writes the five-card sample deck used when no file exists. */
	fun createSample()
}
```

Migration sequence inside `load()`:

1. Read `paths.history`; if absent, return an empty list.
2. Call `migrate(raw)`. If it returns null, parse and return - the file was already current.
3. Otherwise copy the original text to `paths.historyBackup`, overwriting any previous backup.
4. Write the migrated text to `paths.history`.
5. Parse and return the migrated records.

**Requirements:**
- [ ] `migrate` returns null when every record already carries a `timedOut` key
- [ ] `migrate` sets `timedOut = timeTaken >= 10.0f` for records lacking the key, so exactly 10.0 counts as timed out
- [ ] `load()` writes `history.json.bak` before rewriting `history.json`, and overwrites any previous backup
- [ ] `load()` returns an empty list rather than throwing for a missing or malformed file
- [ ] `append` and `save` keep the newest `maxEntries` records and store them oldest-first
- [ ] `DeckRepository.createSample()` writes the same five cards the current `createSampleFile()` writes
- [ ] `MigrationTest` covers: already-migrated file returns null; mixed file migrates only the records missing the key; a record at exactly 10.0 becomes timed out; a record at 9.99 does not; the backup file is written with the original content

**Dependencies:** Task 1.

**Testability:** `migrate` is a pure `String -> String?` function, so every migration case is a JVM test with no filesystem at all. The stateful half is verified by constructing `HistoryRepository(AnkiPaths.at(tempFolder.root))`, writing a pre-migration fixture, calling `load()`, and asserting both the returned records and the on-disk backup.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 3: Retire stats.json and Rewire MainActivity

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/GameView.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/AppContainer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/StatsScreen.kt`
- Delete: `app/src/test/java/net/jacoblo/simpleanki/ExampleUnitTest.kt`

**Context:**
`MainActivity.kt` is 542 lines holding the activity, every screen's state, and all file I/O. This task moves the file I/O to the repositories built in Tasks 1 and 2, extracts `GameView` into its own file, and deletes `stats.json` and `CardStats` entirely.

The key change is conceptual: per-card figures are no longer stored, they are derived. `CardStats` and its `bestTime`, `averageTime`, `medianTime`, and `lastTime` properties disappear, along with the `statsUpdateCount` badge in the top bar. `GameView` needs only Best and Avg for the current card, so a small helper derives those from history rather than reading a second file.

The app must look and behave identically when this task lands. All four screens still work. This is deliberately a pure refactor so that the WebView work in Task 5 starts from a clean base.

**Architecture:**

```kotlin
// AppContainer.kt
package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.*
import net.jacoblo.simpleanki.metronome.ClickPlayer

/**
 * Hand-rolled dependency graph, constructed once in MainActivity.onCreate.
 * Context is a constructor parameter from the outset because Task 14 builds
 * SoundPoolClickPlayer from it; taking it later would churn every call site.
 */
class AppContainer(
	private val context: Context,
	val paths: AnkiPaths,
	val testMode: Boolean
) {
	val deckRepository = DeckRepository(paths)
	val historyRepository = HistoryRepository(paths)
	// Task 8 adds: settingsRepository, viewsRepository, and a loaded `settings`.
	// Task 14 adds: clickPlayer, selected on `testMode`.

	/** Releases held native resources. Called from MainActivity.onDestroy. */
	fun release()
}
```

```kotlin
// GameView.kt
package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.HistoryEntry

/** Best and average of a single card, derived from history. Replaces CardStats. */
data class CardSummary(val best: Float?, val average: Float?)

/**
 * Derives the summary for one question from history, over the newest [limit]
 * attempts. Timed-out attempts are excluded from both figures. Returns nulls
 * when the card has no successful attempt.
 */
fun summarize(history: List<HistoryEntry>, question: String, limit: Int = 10): CardSummary

@Composable
fun GameView(
	cards: List<AnkiCard>,
	currentCardIndex: Int,
	isShowingAnswer: Boolean,
	summary: CardSummary,
	currentRoundTime: Float,
	onNextCard: () -> Unit,
	onFlip: () -> Unit
)
```

Removals, all of which must be complete:

- `data class CardStats` and every reference to it
- `loadStats()`, `saveStats()`, and the `stats.json` file - it is not migrated, not read, not written
- The `statsUpdateCount` state, its `Text` in the top bar, and its persistence
- The `onResetCard` callback and the per-card reset `IconButton` in `GameView`, which existed only to clear a `CardStats` entry
- `loadCards()`, `createSampleFile()`, `loadHistory()`, `saveHistory()` as top-level functions, now on the repositories
- `HISTORY_MAX`, replaced by `Settings.history.maxEntries` once Task 8 lands; until then pass the literal 5000

`StatsScreen.kt` is modified rather than deleted here because it is still the live stats UI until Task 6 replaces it. Change it to take `List<HistoryEntry>` and derive its rows with `summarize`, keeping its existing columns and sorting.

**Error policy - this task owns it.** Task 2's repositories propagate `IOException` from `JsonStore.write` rather than swallowing it, which is correct for a repository but means the policy decision lands here. Two paths regress without handling: `HistoryRepository.load()` writes twice during migration, and `DeckRepository.createSample()` propagates where the legacy `createSampleFile()` swallowed with `printStackTrace()`. Since both run inside the `ON_RESUME` observer, an unhandled throw is a crash on resume where the legacy code degraded to an empty card list. Wrap both call sites, Toast the failure, and carry on with whatever data loaded.

Do NOT instead make `load()` degrade by returning parsed-but-unmigrated records. Unmigrated records read through `optBoolean("timedOut", false)`, so every pre-existing timeout would silently render as a success and feed wrong numbers to the stats view - a visible crash traded for corrupted data.

**Requirements:**
- [ ] `stats.json` is never read or written; `CardStats` no longer exists anywhere
- [ ] The `statsUpdateCount` badge is gone from the top bar
- [ ] The per-card reset button is gone from `GameView`
- [ ] `summarize` excludes timed-out attempts and returns nulls when no successful attempt exists
- [ ] `GameView` renders `-` where it previously rendered a time, when `summarize` returns null
- [ ] All four screens still render and navigate exactly as before
- [ ] `MainActivity.kt` contains no `File` or `Environment` usage
- [ ] `MainActivity.kt` is under 200 lines after the extraction
- [ ] Repository calls in the `ON_RESUME` observer and in the answer handler are wrapped so an `IOException` shows a Toast instead of crashing. See the note below - without this, Task 3 ships a crash where the legacy code showed an empty state

**Dependencies:** Tasks 1, 2.

**Testability:** `summarize` is pure and gets JVM tests for the empty case, the all-timed-out case, and the limit boundary. The refactor itself is verified by the app behaving identically; there is no new observable behaviour to assert.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 3b: Lifetime Review Counter

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/SettingsRepository.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/SettingsTest.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/Models.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/AppContainer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`

**Context:**
Task 3 deleted the `statsUpdateCount` badge along with `stats.json`, treating it as an artifact of the retired file. That was wrong: it is a lifetime count of cards ever reviewed, and the user's device holds **15700**. It cannot be recomputed from `history.json`, which is a rolling window capped at `maxEntries`. The only surviving copy is the `statsUpdateCount` key in `stats.json`, which Task 3 ignores rather than deletes, so the value is recoverable but frozen.

This task gives the counter a new home in `settings.json`, seeds it once from `stats.json`, resumes incrementing, and restores the badge.

This task creates `settings.json` earlier than planned. **Task 8 now extends `SettingsRepository` rather than creating it.**

**Counting rule:** `lifetimeReviews` increments **exactly once per history record appended**. A metronome timeout appends a record, so timeouts count - which is the user's stated intent, "every card shown, timeouts included". Tying the counter to record-append rather than to a UI event is what keeps the two from drifting.

**Architecture:**

```kotlin
// Added to Models.kt
data class CounterSettings(val lifetimeReviews: Int = 0)

data class Settings(
	val metronome: MetronomeSettings = MetronomeSettings(),
	val table: TableSettings = TableSettings(),
	val history: HistorySettings = HistorySettings(),
	val counters: CounterSettings = CounterSettings()
)
```

```kotlin
// SettingsRepository.kt
class SettingsRepository(private val paths: AnkiPaths) {
	/** Loads settings, creating defaults and quarantining a corrupt file. */
	fun load(): Settings

	fun save(settings: Settings)

	companion object {
		/**
		 * One-time seed of lifetimeReviews from the retired stats.json.
		 * Returns 0 when the file is absent or unreadable. Only ever called
		 * when settings.json does not yet exist.
		 */
		fun seedLifetimeReviews(statsJson: String?): Int
	}
}
```

`settings.json` on disk gains one section:

```json
{ "schemaVersion": 1,
  "metronome": { "enabled": false, "intervalSeconds": 10.0, "soundPath": null },
  "table": { "defaultLimit": 10, "highlightEvery": 5, "defaultWindowSize": 100 },
  "history": { "maxEntries": 5000 },
  "counters": { "lifetimeReviews": 15700 } }
```

**Seeding sequence, which must be idempotent:**

1. If `settings.json` exists and parses, use it. **Never look at `stats.json` again.**
2. If absent, read `stats.json` and take its `statsUpdateCount`, defaulting to 0 when the file is missing, unparseable, or lacks the key.
3. Write the new `settings.json` with that seed.

Step 1 is what makes this safe to run repeatedly. Once `settings.json` exists the seed can never re-fire, so a user who later resets their count does not have it silently restored from a stale `stats.json`.

**Requirements:**
- [ ] `lifetimeReviews` increments exactly once per history record appended, in the same code path that appends
- [ ] The badge is restored to the top bar, showing `lifetimeReviews`, in the same position and style as the retired `statsUpdateCount`
- [ ] `settings.json` is created on first run with the seed from `stats.json`
- [ ] The seed fires only when `settings.json` is absent; an existing file is never overwritten from `stats.json`
- [ ] A missing, unparseable, or key-less `stats.json` seeds 0 rather than throwing
- [ ] A corrupt `settings.json` is quarantined to `settings.json.corrupt` before defaults are written
- [ ] `stats.json` is still never written to
- [ ] The counter survives an app restart
- [ ] `SettingsTest` covers: seeding from a real `stats.json` fixture; seeding 0 from a missing file; seeding 0 from a corrupt file; an existing `settings.json` suppressing the seed; round-trip save/load equality; corrupt-file quarantine

**Dependencies:** Tasks 1, 2, 3.

**Testability:** `seedLifetimeReviews` is a pure `String? -> Int`, so every seed case is a JVM test with no filesystem. The stateful half runs through `AnkiPaths.at(tempFolder.root)`. Assert round-trip equality structurally rather than by exact JSON text - the test classpath's `org.json` is `HashMap`-backed and key order is arbitrary there.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Opu`

---

## Task 4: Table Render Pipeline, Without Computed Columns

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/RenderedTable.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/TableEngine.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/TableEngineTest.kt`

**Context:**
This is the heart of the feature: one pure function that turns raw history records plus a view definition into a fully formatted table. Everything else in the table half of this plan is either feeding it or rendering its output.

Computed columns are deliberately deferred to Task 13. This task builds the pipeline with base columns only, so that the WebView work in Task 5 has a real type to render and the pivot engine has a real place to plug into.

The pipeline order is load-bearing and must not be rearranged:

1. Filter to `deckQuestions` when `view.filterToCurrentDeck`
2. Sort by the active `SortSpec`
3. Compute computed columns - a no-op in this task
4. Collapse duplicates on `view.collapseDuplicatesOn`, keeping the first row of each key
5. Number the survivors 1..N for the `#` column
6. Format every cell to a string

Step 3 must precede step 4. Once Task 13 lands, collapsing first would strip partitions of their members and make every aggregate wrong.

**Sorting must be stable, and the base order before any user sort is `When` descending.** This is what makes collapse well-defined: when a view is sorted by a column whose value is shared across a group, stability falls back to newest-first and the surviving row is the most recent attempt. That is why the stats view's `Seconds` column reproduces the old `Last` column for free. Implement by sorting the input to `When` descending first, then applying the user's sort with a stable sort.

**Architecture:**

```kotlin
// RenderedTable.kt
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.SortSpec

data class RenderedColumn(
	val id: String,
	val title: String,
	val width: Int,
	val frozen: Boolean,
	val type: ColumnType,
	val sortable: Boolean,
	/** Non-null when the column's formula failed; every cell renders "#ERR". */
	val error: String? = null
)

data class RenderedTable(
	val viewId: String,
	val sort: SortSpec,
	/** Visible columns only, in display order. */
	val columns: List<RenderedColumn>,
	/** Formatted cell strings, outer list is rows, inner aligns with [columns]. */
	val rows: List<List<String>>,
	val highlightEvery: Int,
	val visibleRowCount: Int,
	/** Human-readable problems, surfaced in the column sheet. */
	val warnings: List<String>
)
```

```kotlin
// TableEngine.kt
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.*

/** Definition of one base column: its type and how to read and format it. */
data class BaseColumn(
	val id: String,
	val type: ColumnType,
	val format: CellFormat,
	val sortable: Boolean
)

object TableEngine {

	/** The eight base columns. Defined in Kotlin, never in config. */
	val BASE_COLUMNS: List<BaseColumn> = listOf(
		BaseColumn("#",        ColumnType.NUMBER, CellFormat.INT,    sortable = false),
		BaseColumn("When",     ColumnType.TIME,   CellFormat.TIME,   sortable = true),
		BaseColumn("Date",     ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn("Time",     ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn("Question", ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn("Answer",   ColumnType.TEXT,   CellFormat.TEXT,   sortable = true),
		BaseColumn("Seconds",  ColumnType.NUMBER, CellFormat.TWO_DP, sortable = true),
		BaseColumn("TimedOut", ColumnType.BOOL,   CellFormat.TEXT,   sortable = true)
	)

	fun baseColumn(id: String): BaseColumn?

	/**
	 * Raw comparable value of a base column for one row, used for sorting.
	 * Returns null for a timed-out row on a numeric column so that such rows
	 * can be forced last in both sort directions.
	 */
	fun rawValue(entry: HistoryEntry, columnId: String, displayIndex: Int): Comparable<*>?

	/** Formats one value for display. Null renders "-". */
	fun format(value: Any?, format: CellFormat): String

	/** The whole pipeline. Pure: no Android imports, no file access, no clock. */
	fun render(
		history: List<HistoryEntry>,
		deckQuestions: Set<String>,
		view: TableView,
		sort: SortSpec
	): RenderedTable
}
```

Formatting and sorting rules:

- `TEXT` renders verbatim; `INT` renders with no decimals; `ONE_DP` and `TWO_DP` render fixed decimals; `PERCENT` renders one decimal followed by `%`; `TIME` renders `MM-dd HH:mm:ss` from epoch millis
- A null value renders `-`
- `Seconds` renders `-` for a timed-out row; the stored `timeTaken` is not surfaced
- `TimedOut` renders `x` when true and the empty string when false
- `TEXT` sorts case-insensitively; `NUMBER` numerically; `TIME` on raw epoch millis, never on the formatted string; `BOOL` false before true
- Rows whose numeric value is null sort last in BOTH directions, so timed-out rows never masquerade as the worst time
- `#` is not sortable
- A `defaultSort` naming a nonexistent column falls back to `When` descending; naming a hidden column still applies, since visibility is presentation only

**Requirements:**
- [ ] `TableEngine.kt` and `RenderedTable.kt` have zero Android imports
- [ ] Sorting is stable and the pre-sort base order is `When` descending
- [ ] Numeric nulls sort last in both ascending and descending order
- [ ] Collapse keeps the first row of each key in the current sort order and drops the rest
- [ ] `#` numbers only surviving rows, starting at 1
- [ ] `columns` contains visible columns only, in `view.columns` order
- [ ] A `collapseDuplicatesOn` naming a nonexistent column is ignored and adds a warning
- [ ] `TableEngineTest` covers: empty history; deck filter removing every row; stable-sort survivor selection; every-format rendering; timed-out rows sorting last both ways; collapse plus renumbering
- [ ] `ExampleUnitTest.kt` is deleted

**Dependencies:** Task 1.

**Testability:** The entire task is one pure function returning a value object, so tests construct a `List<HistoryEntry>` and a `TableView` in memory and assert on the returned `RenderedTable` with no filesystem, no emulator, and no Compose. This is the highest-value test surface in the plan and should carry the most cases.

**Difficulty:**
High
Recommend Engineer to finish this task:
`Claude Opu`

---

## Task 5: Tabulator, WebView, and the Payload Transport

**Files:**
- Create: `app/src/main/assets/tabulator/tabulator.min.js`
- Create: `app/src/main/assets/tabulator/tabulator.min.css`
- Create: `app/src/main/assets/table.html`
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/PayloadPathHandler.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/TableBridge.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/TableWebView.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`

**Context:**
This is the only genuinely unpredictable part of the plan and it is deliberately isolated into one task. Everything else is pure Kotlin that can be tested blind; this touches offline asset loading, CORS, payload size limits, and touch handling on a real device.

At 5000 rows a full payload is roughly 700 KB of JSON, which is not safe to push through `evaluateJavascript` as a script string. Instead the payload is served as a virtual URL through a custom `WebViewAssetLoader.PathHandler` and fetched by JavaScript. `evaluateJavascript` is used only for tiny control messages.

Serving the page and Tabulator from the same `WebViewAssetLoader` origin also avoids the CORS restrictions that apply to `file:///android_asset/`.

Tabulator owns exactly two gestures, resize and reorder, and reports both back over the bridge. Kotlin remains authoritative: it persists what Tabulator reports and re-pushes the full column state on the next render. Tabulator never sorts and never computes; it receives pre-formatted strings.

**Architecture:**

Gradle additions:

```toml
# gradle/libs.versions.toml
webkit = "1.12.1"

androidx-webkit = { group = "androidx.webkit", name = "webkit", version.ref = "webkit" }
```

```groovy
// app/build.gradle
implementation libs.androidx.webkit
```

```kotlin
// PayloadPathHandler.kt
package net.jacoblo.simpleanki.table

import androidx.webkit.WebViewAssetLoader

/**
 * Serves the current table payload as a virtual URL so JavaScript can fetch it,
 * avoiding the size limit on evaluateJavascript script strings.
 */
class PayloadPathHandler : WebViewAssetLoader.PathHandler {
	@Volatile
	var payload: String = "{}"

	override fun handle(path: String): WebResourceResponse?
}
```

```kotlin
// TableBridge.kt
package net.jacoblo.simpleanki.table

/**
 * JavaScript to Kotlin channel. @JavascriptInterface methods may only take
 * primitives and String, which is why the reorder payload is a CSV string.
 */
class TableBridge(
	private val onSort: (columnId: String) -> Unit,
	private val onResize: (columnId: String, width: Int) -> Unit,
	private val onReorder: (columnIds: List<String>) -> Unit,
	private val onRenderComplete: (rowCount: Int) -> Unit
) {
	@JavascriptInterface fun sort(columnId: String)
	@JavascriptInterface fun resize(columnId: String, width: Int)
	@JavascriptInterface fun reorder(columnIdsCsv: String)
	@JavascriptInterface fun renderComplete(rowCount: Int)
}
```

```kotlin
// TableWebView.kt
package net.jacoblo.simpleanki.table

/** Serializes a RenderedTable into the JSON shape table.html expects. */
fun RenderedTable.toPayloadJson(darkTheme: Boolean): String

@Composable
fun TableWebView(
	table: RenderedTable,
	bridge: TableBridge,
	modifier: Modifier = Modifier
)
```

Payload contract between Kotlin and `table.html`:

```json
{
  "viewId": "history",
  "sort": { "column": "When", "dir": "desc" },
  "highlightEvery": 5,
  "dark": false,
  "columns": [
    { "id": "When", "title": "When", "width": 140, "frozen": false, "sortable": true, "error": null }
  ],
  "rows": [ ["08-22 10:05", "03", "Al Pacino", "2.40", ""] ]
}
```

`table.html` responsibilities:

- Load `tabulator.min.js` and `tabulator.min.css` from the same virtual origin
- `fetch("https://appassets.androidplatform.net/payload/table.json")` and build the grid
- Configure `layout: "fitColumns"` off, fixed widths from the payload, horizontal scroll on, `virtualDom: true` for 5000-row performance, `movableColumns: true`, `resizableColumns: true`, and header sorting DISABLED so Kotlin owns sort
- Header click calls `Android.sort(columnId)`; column resize calls `Android.resize(id, width)`; column move calls `Android.reorder(csv)`; after render calls `Android.renderComplete(n)`
- Apply a background tint to every `highlightEvery`-th visible row, disabled when `highlightEvery` is 0
- Render `#ERR` in every cell of a column whose `error` is non-null
- Read colors from CSS custom properties so the `dark` flag switches the palette to match Material3

Kotlin refreshes by setting `PayloadPathHandler.payload` then calling `evaluateJavascript("reload()")`, which re-fetches. This keeps the large payload off the script-string path entirely.

**Requirements:**
- [ ] Tabulator 6.x is vendored into assets; no CDN reference anywhere, since the app must work offline
- [ ] `WebViewAssetLoader` serves `/assets/` for the page and Tabulator, and `/payload/` through `PayloadPathHandler`
- [ ] The full payload is delivered by `fetch`, never by `evaluateJavascript`
- [ ] `evaluateJavascript` is used only for `reload()`
- [ ] Header sorting inside Tabulator is disabled; a header tap calls `Android.sort` and nothing else
- [ ] Column resize and column move both report back over the bridge
- [ ] `virtualDom` is enabled and a 5000-row payload scrolls without stutter on device
- [ ] Horizontal scrolling works and a column with `frozen: true` stays pinned
- [ ] `javaScriptEnabled` is on; `allowFileAccess` and `allowContentAccess` are off
- [ ] A row count of 0 renders headers with no rows and does not error

**Dependencies:** Task 4.

**Testability:** This task's output is verified on device rather than on the JVM, which is precisely why it is isolated. `toPayloadJson` is pure and gets a JVM test asserting the serialized shape against a fixture. The bridge is verified by the `dump.json` written in Task 7: an agent resizes a column, reads `views.json`, and confirms the new width, which proves the whole JS-to-Kotlin path end to end.

**Difficulty:**
High
Recommend Engineer to finish this task:
`Claude Opu`

---

## Task 6: Navigation Drawer and the Three Built-in Views

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/DefaultViews.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/TableScreen.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`
- Delete: `app/src/main/java/net/jacoblo/simpleanki/StatsScreen.kt`
- Delete: `app/src/main/java/net/jacoblo/simpleanki/HistoryScreen.kt`
- Delete: `app/src/main/java/net/jacoblo/simpleanki/QuestionsScreen.kt`

**Context:**
The four `IconButton`s in the top bar are replaced by a single hamburger opening a Material3 `ModalNavigationDrawer` with written-out text labels. All three old screens are deleted and replaced by one `TableScreen` driven by a `TableView` definition.

The three built-in views live in `DefaultViews.kt` as Kotlin constants in this task, and become the seed content of `views.json` in Task 8. This reconciles keeping Stats, List Rows, and History in the menu with deleting the tile-grid screen: List Rows survives as a table preset rather than as a grid.

The stats view's computed columns cannot be expressed yet because the pivot engine does not exist until Task 13. Define `stats` here with its base columns only - Question and Seconds titled "Last" - and add the Best, Avg, Med, Accuracy, and Attempts columns in Task 13. Do not hardcode a temporary aggregate implementation; the columns simply are not there yet.

**Architecture:**

```kotlin
// DefaultViews.kt
package net.jacoblo.simpleanki.data

object DefaultViews {
	fun statsView(tableSettings: TableSettings): TableView
	fun historyView(tableSettings: TableSettings): TableView
	fun listRowsView(tableSettings: TableSettings): TableView

	fun all(tableSettings: TableSettings): List<TableView> =
		listOf(statsView(tableSettings), historyView(tableSettings), listRowsView(tableSettings))
}
```

| id | name | Columns | defaultSort | collapseDuplicatesOn |
|---|---|---|---|---|
| `stats` | Stats | Question (frozen), Seconds titled "Last" | Question asc | Question |
| `history` | History | #, When, Question, Answer, Seconds, TimedOut | When desc | null |
| `list_rows` | List Rows | #, Question | When desc | null |

All three set `filterToCurrentDeck = true` and `highlightEvery` from `TableSettings`.

```kotlin
// TableScreen.kt
package net.jacoblo.simpleanki.table

@Composable
fun TableScreen(
	history: List<HistoryEntry>,
	deckQuestions: Set<String>,
	view: TableView,
	onViewChanged: (TableView) -> Unit,
	/** Fired after each render. Task 7 wires this to TestMode.writeDump. */
	onRendered: (RenderedTable) -> Unit,
	modifier: Modifier = Modifier
)
```

`TableScreen` holds the active `SortSpec` in `remember`, seeded from `view.defaultSort`, calls `TableEngine.render`, and passes the result to `TableWebView`. A header tap through the bridge toggles direction when the same column is tapped and otherwise switches column and resets to ascending. Resize and reorder callbacks rebuild the `TableView` and raise it through `onViewChanged`, which Task 8 wires to autosave.

Drawer structure in `MainActivity`:

```text
Simple Anki
-----------------
  Flip Cards
-----------------
  Stats
  History
  List Rows
  <custom views from views.json>
-----------------
  Metronome   [ ]      <- switch added in Task 15
```

`Screen` becomes a sealed type rather than the current four-value enum, because the table entries are now data-driven:

```kotlin
sealed interface Screen {
	data object FlipCards : Screen
	data class Table(val viewId: String) : Screen
}
```

**Requirements:**
- [ ] All four `IconButton`s are gone from the top bar, replaced by one hamburger
- [ ] The lifetime review counter badge from Task 3b survives the top-bar rebuild
- [ ] Drawer entries are written-out text labels, not icons
- [ ] Every view in the view list appears in the drawer automatically, built-in or custom
- [ ] `StatsScreen.kt`, `HistoryScreen.kt`, and `QuestionsScreen.kt` are deleted, along with the `SortColumn` enum and `HeaderCell` composable that lived in `StatsScreen.kt`
- [ ] Tapping a header sorts ascending, and tapping the same header again reverses
- [ ] Selecting a drawer entry closes the drawer and switches the screen
- [ ] An `activeViewId` naming a missing view falls back to the first view in the list
- [ ] The `stats` view carries only its base columns in this task
- [ ] `TableScreen` accepts an `onRendered` callback and this task passes an empty lambda

**Two things inherited from Task 3.** `MainActivity.kt` lands at exactly 199 lines against its 200-line limit, with no headroom, and this task adds a `ModalNavigationDrawer` on top. Plan to extract the `ON_RESUME` observer into a `rememberAppState`-style helper rather than discovering the ceiling mid-task. Separately, `recentTimes` in `GameView.kt` is `internal` only so the deleted `StatsScreen` could reach it; narrow it to `private` once this task removes its last outside caller.

**Note on null sort order.** The interim `StatsScreen` sorted null figures FIRST ascending, where the retired `9999f` sentinel sorted them last. That file dies here, and Section 8.3's rule is the correct one: nulls and timed-out rows sort LAST in both directions. Do not carry the interim behaviour forward.

**Dependencies:** Tasks 4, 5.

**Testability:** `DefaultViews` is pure data and gets a JVM test asserting each built-in's column ids, sort, and collapse key. Combined with Task 4's engine tests this means the built-in views' rendered output is fully asserted on the JVM before ever reaching a device.

**Difficulty:**
Low
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 7: Test Mode

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/testmode/TestMode.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/AppContainer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/table/TableScreen.kt`

**Context:**
This is production code whose purpose is to let an AI agent drive and verify the app on a device. It is gated on the debug build variant so it cannot be reached in a release build.

Activation:

```bash
adb shell am start -n net.jacoblo.simpleanki/.MainActivity --ez test_mode true
```

When active, four things change and nothing else. `AnkiPaths` resolves to `/sdcard/SimpleAnki-test/` instead of `/sdcard/SimpleAnki/`. That directory is wiped and re-seeded from fixtures on launch. `dump.json` is written after every table render. `ClickPlayer` is the no-op implementation, added in Task 14.

The seeded `settings.json` sets `metronome.intervalSeconds` to `0.3`, which is how metronome timeouts are tested without a fake clock and without a ten-second wait. The configuration value is the test seam.

**Architecture:**

```kotlin
// TestMode.kt
package net.jacoblo.simpleanki.testmode

object TestMode {
	const val EXTRA = "test_mode"

	/** True only when BuildConfig.DEBUG and the intent carries the extra. */
	fun isActive(activity: Activity): Boolean

	/** Wipes the root and writes the deck, history, settings, and views fixtures. */
	fun seed(paths: AnkiPaths)

	/** Serializes a RenderedTable to paths.dump. Called after every render. */
	fun writeDump(paths: AnkiPaths, table: RenderedTable)
}
```

Fixture contents, written by `seed`:

- `simple-anki.json`: a deterministic six-card deck with questions `01` through `06`
- `history.json`: a deterministic set of roughly 30 records across those questions, including at least four with `timedOut: true` and at least one question whose every attempt timed out, so that the `-` empty-aggregate path is exercised
- `settings.json`: defaults except `metronome.intervalSeconds` of `0.3` and `metronome.enabled` of `false`
- `views.json`: the three built-ins

`dump.json` shape, which is the `RenderedTable` serialized directly:

```json
{
  "viewId": "stats",
  "sort": { "column": "Question", "dir": "asc" },
  "highlightEvery": 5,
  "visibleRowCount": 6,
  "columns": [ { "id": "Question", "title": "Question", "width": 160, "frozen": true, "type": "TEXT", "sortable": true, "error": null } ],
  "rows": [ ["01", "2.40"] ],
  "warnings": []
}
```

**Requirements:**
- [ ] `isActive` returns false in a release build regardless of the intent extra
- [ ] The test root is wiped before seeding, so every run starts from an identical state
- [ ] Fixtures are deterministic: no clock reads, no randomness, fixed timestamps
- [ ] The history fixture contains a question whose every attempt timed out
- [ ] `dump.json` is written after `onRenderComplete` fires, so it reflects what was actually rendered
- [ ] `dump.json` is written only under test mode
- [ ] Production paths are untouched; the only branches are inside `AnkiPaths` selection and the `ClickPlayer` factory

**Dependencies:** Tasks 1, 4, 6.

**Testability:** This task IS the testability infrastructure. It is self-verifying: launch with the extra, confirm `/sdcard/SimpleAnki-test/` exists and `/sdcard/SimpleAnki/` is untouched, then `adb pull` `dump.json` and diff it against an expected fixture. `seed` is also JVM-testable through `AnkiPaths.at(tempFolder.root)`.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 8: Settings and Views Persistence

**Files:**
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/SettingsRepository.kt` (created in Task 3b; extend it, do not recreate)
- Create: `app/src/main/java/net/jacoblo/simpleanki/data/ViewsRepository.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/RepositoryTest.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/AppContainer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`

**Context:**
The three built-in views move out of Kotlin constants and into `views.json`, where the user can hand-edit them. Both files auto-create on first run.

Recovery is deliberately more careful than "just recreate it": a file that is missing, unreadable, or unparseable is first renamed to `<name>.corrupt` and only then rewritten from defaults. Losing a set of hand-tuned views to a stray comma is worth one cheap backup.

The `formula` field is written on save but ignored on load whenever a column also carries structured `aggregate` fields. That relationship is implemented in Task 12; this task must round-trip the field faithfully so nothing is lost in the meantime.

**Architecture:**

```kotlin
// SettingsRepository.kt
package net.jacoblo.simpleanki.data

class SettingsRepository(paths: AnkiPaths) {
	/** Loads settings, creating defaults and quarantining a corrupt file. */
	fun load(): Settings

	fun save(settings: Settings)
}
```

```kotlin
// ViewsRepository.kt
package net.jacoblo.simpleanki.data

data class ViewsFile(val activeViewId: String, val views: List<TableView>)

class ViewsRepository(paths: AnkiPaths) {
	fun load(tableSettings: TableSettings): ViewsFile

	fun save(file: ViewsFile)

	/** Replaces the three built-ins, leaving custom views untouched. */
	fun resetBuiltIns(current: ViewsFile, tableSettings: TableSettings): ViewsFile
}
```

`settings.json` on-disk shape:

```json
{
  "schemaVersion": 1,
  "metronome": { "enabled": false, "intervalSeconds": 10.0, "soundPath": null },
  "table": { "defaultLimit": 10, "highlightEvery": 5, "defaultWindowSize": 100 },
  "history": { "maxEntries": 5000 }
}
```

`views.json` on-disk shape:

```json
{
  "schemaVersion": 1,
  "activeViewId": "history",
  "views": [
    {
      "id": "stats",
      "name": "Stats",
      "filterToCurrentDeck": true,
      "collapseDuplicatesOn": "Question",
      "highlightEvery": 5,
      "defaultSort": { "column": "Question", "dir": "asc" },
      "columns": [
        { "id": "Question", "title": "Question", "width": 160, "visible": true, "frozen": true },
        { "id": "best10", "title": "Best", "width": 70, "visible": true, "format": "0.00",
          "aggregate": "MIN", "source": "Seconds", "limit": 10,
          "partition": { "mode": "group", "by": "Question" },
          "formula": "=MIN(Seconds, group:Question, last:10)" }
      ]
    }
  ]
}
```

`format` maps to `CellFormat` by these exact strings: `text`, `int`, `0.0`, `0.00`, `percent`, `time`. `partition.mode` is one of `group`, `bucket`, or `rolling`; `group` carries `by`, the other two carry `size`. A column is computed if and only if it carries `aggregate` or `formula`.

Recovery, applied to each file independently:

1. Missing file: write defaults, no quarantine, since there is nothing to preserve
2. Unparseable or structurally invalid, which includes a `views` array of length zero: quarantine to `<name>.corrupt`, then write defaults
3. Valid but with unknown keys: preserve and ignore them, so a future field does not destroy an old app's config

**Requirements:**
- [ ] Both files auto-create with defaults when absent
- [ ] A corrupt file is quarantined to `<name>.corrupt` before defaults are written
- [ ] A `views` array of length zero is treated as corrupt
- [ ] An `activeViewId` naming no view falls back to the first view
- [ ] Every field round-trips: save then load yields an equal object, including `formula` and `frozen`
- [ ] `resetBuiltIns` replaces `stats`, `history`, and `list_rows` and leaves all other views untouched
- [ ] Built-in views are directly mutable; there is no read-only prompt
- [ ] `HISTORY_MAX` is gone; the cap comes from `settings.history.maxEntries`
- [ ] The answer handler no longer calls `HistoryRepository.append`. `append` internally calls `load()`, which parses the whole file twice before rewriting it - once in `migrate` and once in `parse` - so every card flip did full-file I/O on the UI thread, over up to 5000 records. `MainActivity` already holds the authoritative list, so use `save(history + entry, maxEntries)` and assign the result afterwards, which preserves the "screen matches disk" semantics on write failure. This lands here because Task 8 must edit that exact line anyway to swap the literal for the setting
- [ ] `RepositoryTest` covers: absent file; corrupt file quarantined then recreated; empty views array; round-trip equality; `resetBuiltIns` preserving custom views

**Dependencies:** Tasks 1, 6.

**Testability:** Every case runs on the JVM through `AnkiPaths.at(tempFolder.root)`. Corruption is simulated by writing `{` to the file and asserting that a `.corrupt` file appears alongside a valid regenerated one. Round-trip equality is the strongest single assertion here and catches most serialization mistakes.

**Never assert exact serialized JSON text of a re-serialized object.** Unit tests link `org.json:json:20240303`, whose `JSONObject` is `HashMap`-backed, while the device uses Android's `LinkedHashMap`-backed implementation. Key order is therefore stable on device and effectively arbitrary in tests. Assert structurally - parse the output and compare fields - or compare whole objects for equality. This is exactly why round-trip equality is the right assertion here: it is order-agnostic. Numeric coercion (`optDouble`, `optBoolean`) and whole-double serialization were verified to agree between the two implementations, so only key order diverges.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 9: Column Sheet, View Lifecycle, and Autosave

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/ColumnSheet.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/table/TableScreen.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`

**Context:**
Users need to show and hide columns, and to create, rename, and delete views. Autosave plus directly-mutable built-ins would otherwise leave no way to branch off a new view, since every edit would land on the current one.

Column reorder and resize are NOT here. Both are Tabulator gestures already reported over the bridge in Task 5, which is the right home for them: they are grid interactions, and building drag-reorder by hand in a Compose list would be a large amount of pointer math for a worse result than the library already provides. This sheet owns visibility and the view lifecycle only.

Any change - a visibility toggle here, or a resize or reorder from the bridge - autosaves immediately to `views.json`. There is no explicit save action.

**Architecture:**

```kotlin
// ColumnSheet.kt
package net.jacoblo.simpleanki.table

@Composable
fun ColumnSheet(
	view: TableView,
	warnings: List<String>,
	onToggleVisible: (columnId: String) -> Unit,
	onAddComputed: (ColumnSpec) -> Unit,
	onRemoveColumn: (columnId: String) -> Unit,
	onSaveAsNew: (name: String) -> Unit,
	onRename: (name: String) -> Unit,
	onDelete: () -> Unit,
	onResetDefaults: () -> Unit,
	onDismiss: () -> Unit
)
```

Sheet contents, top to bottom:

1. View name with a rename affordance
2. Checkbox list of every column in the view, showing `title` and, for computed columns, the generated `formula` string beneath it
3. Any `warnings` from the last render, one line each, which is where a `#ERR` column's explanation appears
4. "Add computed column", opening the builder described below

Requirement 2.1, "add and remove columns", splits along the base/computed line and the sheet must reflect that. A base column is never truly added or removed - all eight always exist in the view and the checkbox toggles `visible`. A computed column is genuinely created by the builder and genuinely deleted by `onRemoveColumn`, which drops it from `view.columns` entirely. Only computed columns show a delete affordance; base columns show only their checkbox.
5. "Save as new view", "Delete view", "Reset to defaults"

The computed-column builder collects: an `Aggregate`, a source column chosen from the base columns, a partition mode with either a `by` column for `group` or a `size` for `bucket` and `rolling`, and a `limit` for `group`. It constructs a `ComputedSpec` directly - it does not ask the user to type a formula. The `formula` mirror is generated on save in Task 12.

View lifecycle semantics:

- "Save as new view" copies the current view under a fresh `id`, derived from the entered name and made unique, and switches to it
- "Rename" changes `name` only; `id` is never touched, so nothing that references the view breaks
- "Delete" removes the view, including a built-in, and falls back to the first remaining view. Deleting the last remaining view is refused
- "Reset to defaults" calls `ViewsRepository.resetBuiltIns`, restoring the three built-ins and leaving custom views alone

**Requirements:**
- [ ] The sheet opens from a top-bar action while a table view is showing
- [ ] Toggling visibility re-renders the table and autosaves immediately
- [ ] Resize and reorder arriving from the bridge also autosave immediately
- [ ] Computed columns display their generated formula string beneath the title
- [ ] Render warnings appear in the sheet, one line each
- [ ] "Save as new view" produces a unique `id` and switches to the new view
- [ ] Rename changes `name` and never `id`
- [ ] Deleting the last remaining view is refused with a message
- [ ] "Reset to defaults" leaves custom views untouched
- [ ] Hiding every column renders an empty table and the sheet is still reachable to unhide

**Dependencies:** Tasks 6, 8.

**Testability:** The pure half is view mutation. Extract each lifecycle operation as a pure function over `ViewsFile` - `saveAsNew`, `rename`, `delete`, `toggleColumn` - and unit-test those on the JVM, including unique-id generation and the refuse-last-delete rule. The Compose layer then only wires callbacks. On device, autosave is confirmed by toggling a column and reading `views.json`.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 10: Aggregate Functions

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/Aggregates.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/AggregatesTest.kt`

**Context:**
The eight aggregate functions, as pure math over arrays. No partitioning, no formulas, no table - just values in, one number or null out.

The timeout rule is the subtle part. `MIN`, `MAX`, `AVG`, `MEDIAN`, `SUM`, and `STDDEV` exclude timed-out members. `COUNT` and `ACCURACY` include them. This is what "make sure it does not count negative numbers" means in the original request, expressed through the `timedOut` flag rather than a sign test.

`ACCURACY` reads only the `timedOut` flags and ignores the values entirely. Its source column is syntactically required for grammatical consistency but semantically unused, which is exactly what makes `=ACCURACY(Question, group:Question)` and `=ACCURACY(Seconds, group:Question)` equivalent, and why "the column can even be Question" works without special-casing.

**Architecture:**

```kotlin
// Aggregates.kt
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.Aggregate

object Aggregates {

	/**
	 * Computes one aggregate over a member set.
	 *
	 * @param values   source value per member; NaN where the source is non-numeric
	 * @param timedOut whether each member's row timed out; same length as [values]
	 * @return the result, or null when undefined, which renders as "-"
	 */
	fun compute(fn: Aggregate, values: DoubleArray, timedOut: BooleanArray): Double?

	/** True when [fn] requires a numeric source column. */
	fun requiresNumericSource(fn: Aggregate): Boolean
}
```

Exact semantics:

| Function | Members used | Empty result | Notes |
|---|---|---|---|
| `MIN` `MAX` `AVG` `MEDIAN` `SUM` | not timed out | null | null when every member timed out |
| `STDDEV` | not timed out | null | population standard deviation, so one member yields 0.0 |
| `COUNT` | all | never null | member count |
| `ACCURACY` | all | never null | `notTimedOut / total * 100` |

`requiresNumericSource` returns true for everything except `COUNT` and `ACCURACY`. Task 12 uses it to reject `=AVG(Question, group:Date)` at parse time.

`MEDIAN` of an even-sized set is the mean of the two middle values, matching the existing `CardStats.medianTime` behaviour being replaced.

`COUNT` and `ACCURACY` can never be empty, because a row is always a member of its own partition.

**Requirements:**
- [ ] Every function is pure with zero Android imports
- [ ] `MIN`, `MAX`, `AVG`, `MEDIAN`, `SUM`, `STDDEV` return null when every member timed out
- [ ] `COUNT` and `ACCURACY` count timed-out members
- [ ] `ACCURACY` never reads `values`
- [ ] `ACCURACY` returns a 0-to-100 percentage, not a 0-to-1 fraction. Task 4's `CellFormat.PERCENT` formats to one decimal and appends `%` WITHOUT rescaling, so returning a fraction would render `0.9%` where `87.5%` is meant
- [ ] `STDDEV` is population, returning 0.0 for a single member rather than null
- [ ] `MEDIAN` averages the two middle values for an even count
- [ ] `AggregatesTest` covers each function for: all-successful, mixed, all-timed-out, single member, and empty input

**Dependencies:** Task 1.

**Testability:** Pure functions over primitive arrays, so every case is a direct JVM assertion with no setup at all. The all-timed-out case is the one most likely to be got wrong and must be tested for every function.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 11: Partition Selection

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/MemberSelector.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/MemberSelectorTest.kt`

**Context:**
Every partition mode answers one question: for row `i`, which rows form its member set? Group partitions by a column's value, bucket by fixed blocks of position, rolling by a trailing range of position.

The interface returns partitions rather than per-row member lists, which matters for both correctness and speed. Under `group` and `bucket` many rows share a partition, so the aggregate is computed once per partition and broadcast to its members. A naive per-row implementation would be O(n squared) and would take 25 million operations per column at 5000 rows.

Under `rolling` every row genuinely has its own window, so partition count equals row count. That is inherent to the mode, not a flaw.

The input rows are already sorted by the time this runs; position means position in the current sort order.

**Architecture:**

```kotlin
// MemberSelector.kt
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.Partition

/**
 * Assignment of rows to partitions.
 *
 * @param partitionOfRow    row index -> partition id
 * @param membersOfPartition partition id -> the row indices it contains
 */
data class PartitionResult(
	val partitionOfRow: IntArray,
	val membersOfPartition: List<IntArray>
)

fun interface MemberSelector {
	/** @param rows already filtered and sorted; position means sort position. */
	fun partition(rows: List<HistoryEntry>): PartitionResult
}

object MemberSelectors {
	/**
	 * @param keyOf  reads the partition key from a row, e.g. its Question
	 * @param limit  keeps only the newest [limit] members by timestamp; 0 means all
	 */
	fun group(keyOf: (HistoryEntry) -> String, limit: Int): MemberSelector

	/** Fixed blocks of [size] by sort position. */
	fun bucket(size: Int): MemberSelector

	/** Trailing window of [size] by sort position, clamped at the start. */
	fun rolling(size: Int): MemberSelector

	fun forPartition(
		partition: Partition,
		limit: Int,
		keyOf: (HistoryEntry, String) -> String
	): MemberSelector
}
```

Exact semantics:

- `group`: one partition per distinct key. Members are trimmed to the newest `limit` by `timestamp` descending, ties broken by lower row index. A row trimmed out of its own partition still maps to that partition in `partitionOfRow`, so it displays its group's aggregate - the limit bounds what the aggregate sees, not who sees the aggregate
- `bucket`: partition id is `position / size`. The final bucket may be short
- `rolling`: partition id equals row index; members are positions `max(0, i - size + 1)` through `i` inclusive. A partial window near the top of the table is computed from however many rows exist and is never left blank
- A `size` of 0 or less is clamped to 1

Note the documented consequence: `bucket:999999` places every row in partition 0 and yields a true grand total, whereas `rolling:999999` yields a running cumulative, because each row still sees only the rows above it.

**Requirements:**
- [ ] Pure, with zero Android imports
- [ ] `group` produces one partition per distinct key
- [ ] `group` with `limit` of 0 keeps every member
- [ ] `group` trims to the newest members by timestamp, not by sort position
- [ ] A row trimmed out of its group still maps to that group in `partitionOfRow`
- [ ] `bucket` assigns `position / size` and tolerates a short final bucket
- [ ] `rolling` clamps at the start and computes partial windows rather than leaving them blank
- [ ] `size` of 0 or less is clamped to 1 rather than dividing by zero
- [ ] No implementation is O(n squared); `group` and `bucket` build their partitions in one pass
- [ ] `MemberSelectorTest` covers: empty input; single row; `bucket` where the row count is an exact multiple of size and where it is not; `rolling` at positions 0, 1, and size-1; `group` with and without a limit; `group` where the limit exceeds the member count

**Dependencies:** Task 1.

**Testability:** Pure functions over in-memory lists returning index arrays, so assertions are exact and require no fixtures. The boundary cases - the short final bucket and the clamped rolling window at the top of the table - are the ones most likely to be off by one and each has a dedicated test.

**Difficulty:**
High
Recommend Engineer to finish this task:
`Claude Opu`

---

## Task 12: Formula Parser and Writer

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/FormulaParser.kt`
- Create: `app/src/main/java/net/jacoblo/simpleanki/table/FormulaWriter.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/FormulaTest.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/ViewsRepository.kt`

**Context:**
The structured `ComputedSpec` is canonical; the `formula` string is a human-readable mirror regenerated from it on every save. The two therefore cannot drift. If a column carries structured `aggregate` fields, those are used and the formula is rewritten to match. If it carries only `formula`, the string is parsed and the struct filled in.

This ordering is the whole point of the hybrid design: a hand-edit typo produces one `#ERR` column instead of a broken view.

The grammar is deliberately NOT an expression language. One function call, no nesting, no arithmetic, no cell references, no `IF`. This constraint is a stated non-goal in the spec and must not be relaxed. If a ratio is wanted later, the answer is a new named aggregate, not an operator.

**Architecture:**

```text
formula := "=" FUNC "(" source ( "," arg )* ")"
FUNC    := MIN | MAX | AVG | MEDIAN | SUM | COUNT | ACCURACY | STDDEV
source  := <column name> | "*"
arg     := "group:" <column name>
         | "bucket:" <int>
         | "rolling:" <int>
         | "last:" <int>
```

```kotlin
// FormulaParser.kt
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.ComputedSpec

sealed interface ParseResult {
	data class Ok(val spec: ComputedSpec) : ParseResult
	data class Err(val message: String) : ParseResult
}

object FormulaParser {
	/**
	 * @param knownColumns base column ids; a source or group key outside this
	 *                     set is an error, which is what prevents a computed
	 *                     column from referencing another computed column
	 */
	fun parse(formula: String, knownColumns: Set<String>): ParseResult
}
```

```kotlin
// FormulaWriter.kt
package net.jacoblo.simpleanki.table

object FormulaWriter {
	/** Round-trip property: parse(write(spec), known) == Ok(spec). */
	fun write(spec: ComputedSpec): String
}
```

Validation rules, each producing a distinct message:

| Condition | Message |
|---|---|
| Missing leading `=` | `formula must start with "="` |
| Unknown function name | `unknown function "AVERAGE"` |
| Source column not in `knownColumns` | `unknown column "Secnods"` |
| `*` source with a function other than `COUNT` | `only COUNT accepts "*" as a source` |
| No partition argument | `a partition argument is required: group:, bucket:, or rolling:` |
| More than one partition argument | `only one partition argument is allowed` |
| `last:` without `group:` | `last: is only valid with group:` |
| `group:` key not in `knownColumns` | `unknown column "Quesiton"` |
| Non-numeric source for a function where `Aggregates.requiresNumericSource` is true | `AVG requires a numeric column, but "Question" is text` |
| Unparseable integer, or a size below 1 | `bucket size must be a positive integer` |
| Unbalanced parentheses or trailing text | `malformed formula` |

`write` emits arguments in a fixed order - source, then the partition argument, then `last:` when the partition is `group` and `limit` is above 0 - so the round-trip property holds and saved files are stable rather than churning.

`ViewsRepository` changes: on load, a column carrying `aggregate` builds its `ComputedSpec` from the structured fields; a column carrying only `formula` parses it, and on failure stores the message in `ColumnSpec.formulaError` rather than throwing. On save, `formula` is always regenerated with `FormulaWriter.write`.

**Requirements:**
- [ ] Every validation rule above produces its distinct message
- [ ] Round-trip holds for every valid spec: `parse(write(spec), known) == Ok(spec)`
- [ ] A parse failure yields `Err` and never throws
- [ ] A parse failure populates `ColumnSpec.formulaError` and leaves other columns untouched
- [ ] Structured fields win over `formula` when both are present, and `formula` is rewritten to match
- [ ] Only base column ids are accepted, so a computed column cannot reference another computed column
- [ ] Whitespace around commas and arguments is tolerated
- [ ] Function names parse case-insensitively; `write` always emits upper case
- [ ] No arithmetic, nesting, or cell references are accepted, and each is rejected as `malformed formula`
- [ ] `FormulaTest` covers every message above plus a round-trip test over all eight functions and all three partition modes

**Dependencies:** Tasks 1, 8, 10.

**Testability:** Both objects are pure `String`-to-value functions. The round-trip property is the strongest assertion available and should be a table-driven test over every function crossed with every partition mode. Error cases are a table of malformed inputs mapped to expected messages, which also serves as the grammar's documentation.

**Difficulty:**
High
Recommend Engineer to finish this task:
`Claude Opu`

---

## Task 13: Computed Columns in the Render Pipeline

**Files:**
- Modify: `app/src/main/java/net/jacoblo/simpleanki/table/TableEngine.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/data/DefaultViews.kt`
- Modify: `app/src/test/java/net/jacoblo/simpleanki/TableEngineTest.kt`

**Context:**
This connects Tasks 10, 11, and 12 into the pipeline built in Task 4, filling in step 3 which was a no-op. It is also where the stats view finally gets the columns that reproduce the old `StatsScreen`, proving the central claim of the spec: the per-card screen is a per-attempt table with group-pivot columns plus collapse, not a second data model.

Step 3 must run before step 4. Computed columns see every row that survived filtering and sorting, before any collapsing. Collapsing first would strip partitions of their members and make every aggregate wrong.

**Architecture:**

Extend `TableEngine.render` to, for each computed column:

1. Build a `MemberSelector` from the column's `Partition` and `limit` through `MemberSelectors.forPartition`
2. Call `partition(rows)` once for the column
3. For each partition, gather its members' source values and `timedOut` flags and call `Aggregates.compute` once
4. Broadcast each partition's result to every row mapped to it through `partitionOfRow`
5. Format with the column's `CellFormat`, defaulting to `TWO_DP`, with null rendering `-`

```kotlin
// Added to TableEngine
/** Source value of a base column for aggregation. NaN when non-numeric. */
fun numericSource(entry: HistoryEntry, columnId: String): Double

/** Cache key so two columns with identical partitioning share one partition pass. */
private data class PartitionKey(val partition: Partition, val limit: Int)
```

Identical `PartitionKey`s share one `partition(rows)` pass within a single render. The stats view has four columns all partitioned by `group:Question, last:10`, so this turns four passes into one.

A column whose `formulaError` is non-null gets a `RenderedColumn` with `error` set and `sortable` false, every cell rendering `#ERR`, and its message appended to `warnings`. Every other column still renders normally.

Computed columns are always `ColumnType.NUMBER` and sort numerically, with `-` cells sorting last in both directions for the same reason timed-out rows do.

The full stats view, replacing the base-columns-only placeholder from Task 6:

| Column | Formula | Format |
|---|---|---|
| Question | base column, frozen | text |
| Last | base column `Seconds` | 0.00 |
| Best | `=MIN(Seconds, group:Question, last:10)` | 0.00 |
| Avg | `=AVG(Seconds, group:Question, last:10)` | 0.00 |
| Med | `=MEDIAN(Seconds, group:Question, last:10)` | 0.00 |
| Attempts | `=COUNT(*, group:Question)` | int |
| Accuracy | `=ACCURACY(Seconds, group:Question)` | percent |

**Requirements:**
- [ ] Computed columns are calculated after sorting and before collapsing
- [ ] Each distinct `PartitionKey` triggers exactly one partition pass per render
- [ ] Each partition's aggregate is computed once and broadcast, not recomputed per row
- [ ] A column with `formulaError` renders `#ERR` in every cell, is not sortable, and adds a warning
- [ ] Other columns render normally alongside an errored one
- [ ] Computed columns sort numerically with nulls last in both directions
- [ ] Re-sorting recomputes `bucket` and `rolling` columns; `group` results are sort-independent
- [ ] The stats view reproduces the old `StatsScreen` figures, allowing for the deliberate change that timed-out attempts are now excluded
- [ ] `TableEngineTest` gains: a group-pivot column asserted against hand-computed values; a bucket column across an inexact block boundary; a rolling column at the top of the table; an errored column rendering `#ERR` while its neighbours render; the worked example from the spec, Q03 with attempts 2.4, 8.0, 1.0, 0.5 giving MIN 0.5 and AVG 2.98 at limit 0, and MIN 2.4 and AVG 5.20 at limit 2

**Dependencies:** Tasks 4, 10, 11, 12.

**Testability:** Still one pure function returning a value object, so the entire pivot engine is asserted on the JVM with no device. The spec's worked example is the single most valuable test, because it was derived independently of the implementation during design and pins down the limit semantics exactly.

**Difficulty:**
High
Recommend Engineer to finish this task:
`Claude Opu`

---

## Task 14: Click Sound

**Files:**
- Create: `app/src/main/assets/click.wav`
- Create: `app/src/main/java/net/jacoblo/simpleanki/metronome/ClickPlayer.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/AppContainer.kt`

**Context:**
A short click played when a card times out. The default asset is synthesized rather than downloaded, so there is no licence to track and the file stays around 2 KB.

`ClickPlayer` is an interface with a real and a no-op implementation. The no-op is what test mode and JVM tests use, so an automated run stays silent and nothing tries to open a `SoundPool` off-device.

Generate `click.wav` with Python standard library only - no ffmpeg, no sox, no package install, nothing that would need the dev container:

```python
import math, struct, wave

RATE, MS, FREQ = 44100, 20, 2000.0
n = RATE * MS // 1000
frames = bytearray()
for i in range(n):
	t = i / RATE
	# Exponentially decaying sine burst: a dry click rather than a beep.
	amp = math.exp(-t * 260.0)
	frames += struct.pack("<h", int(32767 * 0.8 * amp * math.sin(2 * math.pi * FREQ * t)))
with wave.open("app/src/main/assets/click.wav", "wb") as w:
	w.setnchannels(1)
	w.setsampwidth(2)
	w.setframerate(RATE)
	w.writeframes(bytes(frames))
```

**Architecture:**

```kotlin
// ClickPlayer.kt
package net.jacoblo.simpleanki.metronome

interface ClickPlayer {
	fun play()
	fun release()
}

/**
 * Plays through the media stream so it respects volume and silent mode.
 * Resolution order: [soundPath] when set and readable, else the bundled
 * click.wav asset, else silence.
 */
class SoundPoolClickPlayer(
	context: Context,
	soundPath: String?,
	private val onLoadFailure: (String) -> Unit
) : ClickPlayer

/** Used by test mode and JVM tests. */
object NoOpClickPlayer : ClickPlayer {
	override fun play() = Unit
	override fun release() = Unit
}
```

`AppContainer` selects the implementation:

```kotlin
val clickPlayer: ClickPlayer =
	if (testMode) NoOpClickPlayer
	else SoundPoolClickPlayer(context, settings.metronome.soundPath, onLoadFailure)
```

`AudioAttributes` use `USAGE_MEDIA` with `CONTENT_TYPE_SONIFICATION`. An unreadable configured path calls `onLoadFailure` once per app launch, which shows a Toast, and falls through to the bundled asset.

**Requirements:**
- [ ] `click.wav` is generated by the script above, is mono 16-bit 44.1 kHz, and is roughly 2 KB
- [ ] Playback is on the media stream and respects volume and silent mode
- [ ] Resolution order is configured path, then bundled asset, then silence
- [ ] An unreadable configured path shows a Toast once per launch and falls back to the bundled asset
- [ ] `NoOpClickPlayer` is selected under test mode
- [ ] `release()` frees the `SoundPool` and is called when the container is torn down
- [ ] The same sound plays for every tick; there is no distinct timeout sound

**Dependencies:** Tasks 3, 8.

**Testability:** `ClickPlayer` is the external audio boundary and `NoOpClickPlayer` is its fake, selected by the `AppContainer` factory. Sound output is not machine-verifiable and no attempt is made to assert on it; what gets asserted is the `timedOut` record the metronome writes alongside the click, which Task 15 covers.

**Difficulty:**
Low
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Task 15: Metronome

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/metronome/MetronomeEffect.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/GameView.kt`
- Modify: `app/src/main/java/net/jacoblo/simpleanki/MainActivity.kt`

**Context:**
A per-card countdown, not a free-running beat. Each card gets a fresh full interval, default 10.0 seconds.

The whole lifecycle rule falls out of `LaunchedEffect` key cancellation, which is why no ViewModel, no service, and no manual timer bookkeeping are needed. When any key changes the coroutine is cancelled and restarted, which is exactly the required stop-and-reset behaviour. The keys are: enabled, interval, the current card, whether the flip screen is showing, and whether the activity is resumed.

Backgrounding needs an explicit lifecycle observer, because composition survives it. `MainActivity` already establishes this pattern with a `DisposableEffect` and a `LifecycleEventObserver`; follow it and hoist an `isResumed` boolean.

**Architecture:**

```kotlin
// MetronomeEffect.kt
package net.jacoblo.simpleanki.metronome

/**
 * Runs a per-card countdown. Cancels and restarts whenever any parameter
 * changes, which is what implements the stop-and-reset rule: leaving the flip
 * screen or backgrounding the app resets the current card's timer, and
 * returning restarts it from zero with no record written for the abandoned
 * attempt.
 */
@Composable
fun MetronomeEffect(
	enabled: Boolean,
	intervalSeconds: Float,
	cardKey: Any?,
	isFlipScreen: Boolean,
	isResumed: Boolean,
	clickPlayer: ClickPlayer,
	onFire: () -> Unit
)
```

Implemented as a single `LaunchedEffect(enabled, intervalSeconds, cardKey, isFlipScreen, isResumed)` that returns immediately unless all gates are open, then delays and calls `clickPlayer.play()` followed by `onFire()`.

`onFire` in `MainActivity` does:

1. If the answer is NOT showing, append a `HistoryEntry` with `timedOut = true` and `timeTaken = intervalSeconds`, and increment `counters.lifetimeReviews` - the counter tracks records appended, so a timeout counts as a review. If the answer IS showing, the successful record was already written at flip time, so write nothing and do not increment
2. Advance to a new random card, which changes `cardKey` and restarts the timer

Behavioural rules, all of which follow from the above:

- Reading the answer consumes the same interval; flipping at 4.0 s leaves 6.0 s before the card advances. This is intended, not a bug
- Manual advance changes `cardKey`, cancelling the pending timer, and the next card gets a fresh full interval
- The timer never runs off the flip screen, so no record is ever written while a table view is showing
- With no cards loaded, `cardKey` is null and the effect does not start

The drawer gains a `Switch` bound to `settings.metronome.enabled`, writing through to `settings.json` on change.

**Requirements:**
- [ ] The timer runs only when enabled, foregrounded, and on the flip screen
- [ ] Leaving the flip screen or backgrounding stops and resets, writing no record
- [ ] Returning to the flip screen restarts the current card from zero
- [ ] Firing before a flip writes one record with `timedOut = true` and `timeTaken` equal to the interval
- [ ] Firing after a flip writes no second record
- [ ] Manual advance cancels the pending timer and the next card gets a full interval
- [ ] `timeTaken` is positive for a timeout; nothing anywhere tests its sign
- [ ] The drawer switch persists to `settings.json` immediately
- [ ] With no cards loaded the timer does not start
- [ ] There is no visual countdown

**Dependencies:** Tasks 3, 8, 14.

**Testability:** Time is controlled by configuration rather than by a fake clock: test mode seeds `metronome.intervalSeconds` of `0.3`, so an agent enables the metronome, waits about a second, and reads `history.json` to find records with `timedOut: true`. The no-record-on-flip rule is verified by flipping quickly and confirming the record count increases by exactly one. The reset rule is verified by opening a table view mid-countdown, waiting past the interval, returning, and confirming no timeout record was written.

**Difficulty:**
Medium
Recommend Engineer to finish this task:
`Claude Sonnet`

---

## Spec Coverage Review

Every numbered requirement from the original request, mapped to the task that delivers it.

| Requirement | Task |
|---|---|
| 1. Remove four icon buttons, one menu, written-out labels | 6 |
| 2. Consolidate three screens into one table view | 4, 5, 6 |
| 2.1 Add and remove columns | 9 |
| 2.2 Resize column width | 5 |
| 2.3 Rearrange column order | 5 |
| 2.4 Sort by clicking, comparator from datatype | 4, 6 |
| 2.5 Better styling, every 5th row highlighted | 5 |
| 2.6 Stats, list rows, and history remain as menu selections | 6 |
| 2.7 Use a feature-rich library rather than reinventing | 5 |
| 2.8 Save and load custom layouts to a settings file | 8, 9 |
| 3.1 Metronome on and off | 15 |
| 3.2 Short click sound | 14 |
| 3.3 Floating-point interval, default 10.0 | 8, 15 |
| 3.4 Settings saved to JSON | 8 |
| 3.5 Auto-advance recording a failure; stats ignore failures | 2, 10, 15 |
| 4.1 Accuracy counts timeouts | 10 |
| 4.2 Accuracy over the last N records | 11, 13 |
| 4.3 Accuracy per block of N sorted rows | 11, 13 |
| 4.4 Generic over any column, Excel-like | 12, 13 |
| 4.5 Works on Question, giving per-question accuracy | 11, 13 |

Spec sections mapped to tasks: 6.1 and 6.2 to Task 2; 6.3 to Task 3; 6.4, 6.5, and 6.6 to Task 8; 7 to Task 6; 8.1 through 8.5 to Task 4; 8.6 to Tasks 5 and 9; 8.7 to Task 5; 9.1 to Task 11; 9.2 to Task 10; 9.3 to Tasks 11 and 13; 9.4 and 9.5 to Task 12; 9.6 to Tasks 6 and 13; 10 to Tasks 14 and 15; 11 to the file structure above; 12 to Tasks 1 and 7; 13 to Tasks 4, 8, 12, and 13.

## Build Order

Tasks are numbered in dependency order and may be executed sequentially. One parallel run is available: Tasks 10 and 11 are pure and depend only on Task 1, so they can proceed alongside the WebView work in Tasks 5 through 7. Task 12 cannot join them, because it needs `ViewsRepository` from Task 8 and `requiresNumericSource` from Task 10.

Milestone boundaries where the app should be built and run on device: after Task 3, the app is unchanged but reorganized; after Task 6, the drawer and table view replace the old screens; after Task 9, views are configurable and persistent; after Task 13, the stats view is fully restored; after Task 15, the metronome is live.
