# Simple Anki: Unified Table View, Metronome, and Pivot Accuracy

Date: 2026-08-22
Status: Approved design, ready for `jja-writing-plans`

## 1. Summary

Four changes to the Simple Anki Android app, delivered as five milestones.

1. Replace the four top-bar icon buttons with a single navigation drawer holding written-out entries.
2. Collapse the three separate table/grid screens into one generic table view screen driven by JSON configuration, rendered by Tabulator inside a WebView.
3. Add a metronome that auto-advances the current card after a configurable interval and records the attempt as a timeout.
4. Add generic pivot/window computed columns, of which "accuracy" is one instance.

The central design insight, arrived at during brainstorming: **there is only one row grain, per-attempt.** The existing per-card Stats screen is not a second data model. It is a per-attempt table with group-pivot columns plus collapse-on-duplicate-question. This removes `CardStats`, removes `stats.json`, and makes the Stats screen a configuration file rather than code.

## 2. Goals

- One table screen replaces `StatsScreen`, `HistoryScreen`, and `QuestionsScreen`.
- Columns can be shown, hidden, reordered, and resized by the user, and those choices persist.
- Sorting picks its comparator from the column's declared datatype.
- Table layouts are saved to and loaded from a JSON file that a human can hand-edit.
- Cards can auto-advance on a timer, recording a failure when the user did not flip in time.
- Aggregate columns work over three partition shapes: group by a column value, fixed buckets of N sorted rows, and a trailing window of N sorted rows.
- Every piece of logic that is not WebView integration is a pure Kotlin function with JVM unit tests.

## 3. Non-goals

These are explicitly out of scope. They are listed so that they are declined deliberately rather than drifted into.

- **No expression language.** The formula grammar accepts exactly one function call. No arithmetic between columns, no nesting, no cell references, no `IF`. If a ratio is wanted later, the answer is a new named aggregate, not an operator.
- **No SQLite or Room.** The hand-editable JSON files are a deliberate product property.
- **No precomputed aggregate cache.** At 5000 rows it solves a problem that does not exist.
- **No fake clock.** `metronome.intervalSeconds` is already a float in config, so a test fixture sets it to `0.3` and timeouts fire in 300 ms. The config is the test seam.
- **No config-driven base column vocabulary.** Base columns are defined in Kotlin. Only computed columns are config-driven.
- **No grid renderer.** The 5-wide tile layout is deleted, not preserved as a render mode.
- **No card editing, no deck import, no sync.**

## 4. Current state

Single `ComponentActivity` hosting one `@Composable AnkiScreen()` that holds all state in `remember`. No ViewModel, no repository layer, no dependency injection. Every file read and write is a top-level function that hard-codes `Environment.getExternalStorageDirectory()`. The only tests are the two Android template stubs.

Data lives in `/sdcard/SimpleAnki/` behind `MANAGE_EXTERNAL_STORAGE`.

| File | Shape | Grain |
|---|---|---|
| `simple-anki.json` | `[{question, answer}]` | per-card |
| `stats.json` | `{statsUpdateCount, "<question>": {history: [float x <=10]}}` | per-card |
| `history.json` | `[{question, answer, timeTaken, timestamp}]`, capped at 300 | per-attempt |

Screens: `GameView` (flip cards), `StatsScreen` (per-card table), `HistoryScreen` (per-attempt table), `QuestionsScreen` (5-wide tile grid, every 10th tile red-bordered).

## 5. Milestone plan

**Milestone 1 - Foundation.** Extract all file I/O out of `MainActivity` behind a storage interface and a path provider. Add `HistoryEntry.timedOut`. Run the in-place migration. Raise the history cap to 5000. Delete `stats.json` and `CardStats`, deriving per-card figures from history. Add test-mode activation and data-directory redirection. The app looks identical when this lands and all four existing screens still work.

**Milestone 2a - Thin slice.** Vendor Tabulator, build the WebView host page and the bridge, add the navigation drawer, and ship three hardcoded views. Delete `QuestionsScreen`. Write `dump.json` on every render in test mode. This milestone exists to retire WebView integration risk before anything depends on it. Its three hardcoded views become the literal content of the default `views.json` in the next milestone.

**Milestone 2b - Config and UI.** `settings.json` and `views.json` schemas with auto-create and corrupt-file recovery. Autosave on resize, reorder, and hide. Column-management bottom sheet. Collapse-duplicates. Row highlight. Deck filter.

**Milestone 3 - Pivot engine.** `MemberSelector`, the eight aggregates, `limit`, the formula parser and generator, `#ERR` cells. Almost entirely pure functions, so this milestone carries the heaviest unit-test coverage.

**Milestone 4 - Metronome.** Settings, click sound, per-card countdown, lifecycle gating, timeout records.

Milestones 1, 3, and 4 are almost entirely JVM-testable. Milestones 2a and 2b require a device.

## 6. Data model and on-disk formats

All files live in the data directory resolved by `AnkiPaths`: `/sdcard/SimpleAnki/` normally, `/sdcard/SimpleAnki-test/` under test mode.

### 6.1 history.json

The only record of what happened. Everything else is derived from it.

```json
[
  { "question": "03", "answer": "Al Pacino", "timeTaken": 2.4, "timestamp": 1755859500000, "timedOut": false },
  { "question": "08", "answer": "John Krasinski", "timeTaken": 10.0, "timestamp": 1755859440000, "timedOut": true }
]
```

`timedOut` is the authoritative failure signal. `timeTaken` stays positive in all cases: for a timeout it holds the metronome interval that elapsed, which preserves the information without inventing a negative sentinel. Nothing in the codebase tests the sign of `timeTaken`.

Records are stored oldest-first. On write, the array is trimmed to the newest `history.maxEntries` records, default 5000.

### 6.2 history.json migration

Runs once, on first load by a build that understands `timedOut`.

1. If every record already has a `timedOut` key, do nothing.
2. Otherwise copy the file to `history.json.bak`, overwriting any previous backup.
3. For each record lacking `timedOut`, set `timedOut = (timeTaken >= 10.0)`. The 10.0 threshold is a hardcoded migration constant, not read from settings, because it describes the past rather than the present. A record at exactly 10.0 counts as timed out.
4. Write the result back to `history.json` in place.

The presence or absence of the `timedOut` key on each record is the migration marker. No `schemaVersion` field is needed in this file.

### 6.3 stats.json

Deleted. Not migrated, not read, not written. `CardStats` is deleted with it. Per-card figures are now group-pivot columns over history.

The `statsUpdateCount` badge in the top bar is removed along with it.

### 6.4 settings.json

```json
{
  "schemaVersion": 1,
  "metronome": {
    "enabled": false,
    "intervalSeconds": 10.0,
    "soundPath": null
  },
  "table": {
    "defaultLimit": 10,
    "highlightEvery": 5,
    "defaultWindowSize": 100
  },
  "history": {
    "maxEntries": 5000
  }
}
```

`metronome.soundPath` is an absolute path to a WAV file, or `null` to use the bundled asset.

### 6.5 views.json

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
        { "id": "Seconds",  "title": "Last",     "width": 70,  "visible": true },
        { "id": "best10",   "title": "Best",     "width": 70,  "visible": true, "format": "0.00",
          "aggregate": "MIN", "source": "Seconds", "limit": 10,
          "partition": { "mode": "group", "by": "Question" },
          "formula": "=MIN(Seconds, group:Question, last:10)" },
        { "id": "acc",      "title": "Accuracy", "width": 80,  "visible": true, "format": "percent",
          "aggregate": "ACCURACY", "source": "Seconds", "limit": 0,
          "partition": { "mode": "group", "by": "Question" },
          "formula": "=ACCURACY(Seconds, group:Question)" }
      ]
    }
  ]
}
```

Array order of `columns` is display order, so reordering is persisted by rewriting the array. A column is a computed column if and only if it carries an `aggregate` or a `formula`; otherwise `id` must name a base column.

`id` is stable and internal. `name` is the drawer label and is freely renameable.

`format` controls cell rendering and is a fixed enum: `text`, `int`, `0.0`, `0.00`, `percent`, or `time`. It defaults to the base column's natural format, and to `0.00` for computed columns. It is deliberately an enum rather than a format-string mini-language, for the same reason the formula grammar is not an expression language.

### 6.6 File recovery rules

If `settings.json` or `views.json` is missing, unreadable, or fails to parse, the app renames the offending file to `<name>.corrupt`, overwriting any previous one, then writes a fresh file from defaults. Losing a set of hand-tuned views to a stray comma is worth one cheap backup.

"Reset to defaults" restores the three built-in views only. Custom views are left untouched.

Built-in views are directly mutable. Editing one and letting autosave fire simply changes it. There is no read-only template prompt.

## 7. Navigation

The four `IconButton`s in the top bar are replaced by a single hamburger opening a Material3 `ModalNavigationDrawer`.

```text
Simple Anki
-----------------
  Flip Cards
-----------------
  Stats
  History
  List Rows
  <custom views...>
-----------------
  Metronome   [x]
```

Entries are written-out text labels rather than icons. Every table view in `views.json`, built-in or custom, appears in the middle section automatically. The metronome switch writes straight through to `settings.metronome.enabled`.

## 8. Table view engine

### 8.1 Render pipeline

Order is load-bearing and must not be rearranged.

1. **Load** all records from `history.json`.
2. **Filter** to questions present in the currently loaded deck, if `filterToCurrentDeck`.
3. **Sort** by the active sort column and direction.
4. **Compute** all computed columns. Partitions see every row from step 3, before any collapsing.
5. **Collapse** duplicates on `collapseDuplicatesOn`, keeping the first row of each key in the current sort order.
6. **Number** the surviving rows `1..N` for the `#` column.
7. **Render.**

Step 4 must precede step 5, or partitions lose their members and every aggregate becomes wrong.

Re-sorting only invalidates `bucket` and `rolling` columns. `group` results are sort-independent and can be reused.

**All sorts are stable, and the base order before any user sort is applied is `When` descending.** This is what makes collapse well-defined: when a view is sorted by a group-pivot column, every row within a group shares that column's value, so stability falls back to newest-first and the surviving row is the most recent attempt. That in turn is why the Stats view's `Seconds` column reproduces the old `Last` column for free.

### 8.2 Base columns

Defined in Kotlin, not config. Per-attempt grain.

| id | Type | Rendering |
|---|---|---|
| `#` | int | display index of visible rows, not sortable |
| `When` | time | `MM-dd HH:mm:ss` |
| `Date` | text | `yyyy-MM-dd` |
| `Time` | text | `HH:mm:ss` |
| `Question` | text | verbatim |
| `Answer` | text | verbatim |
| `Seconds` | number | `0.00`, or `-` when the row timed out |
| `TimedOut` | bool | `x` when true, blank when false |

A timed-out row shows `-` in `Seconds` and `x` in `TimedOut`. The stored `timeTaken` for such a row is not surfaced; it exists for forensic value in the raw file.

### 8.3 Sorting

Tapping a header sorts ascending; tapping the same header again reverses. The comparator is chosen from the column's declared type, satisfying the "auto choose between A-Z or time based on datatype" requirement.

| Type | Comparator |
|---|---|
| `text` | case-insensitive lexicographic |
| `number` | numeric |
| `time` | chronological on the raw epoch millis, not the formatted string |
| `bool` | false before true |

**Timed-out rows sort last in both directions** on any numeric column sourced from `Seconds`. They do not hold a slow time; they hold no time, and burying them at whichever end happens to mean "worst" would be a lie in one of the two directions.

`#` is not sortable, since sorting by display index is the identity.

Computed columns are always numeric and sort numerically, with `-` cells sorted last in both directions for the same reason timed-out rows are. A column rendering `#ERR` is not sortable.

### 8.4 Collapse duplicates

`collapseDuplicatesOn` names one base column, or is `null`. When set, only the first row of each distinct value survives step 5. The others are simply not emitted to the renderer, which is visually identical to a zero-height row and avoids pushing rows the user will never see across the bridge.

Multi-column collapse keys are not supported.

### 8.5 Row highlight

Every `highlightEvery`-th **visible** row, counted after collapse, gets a subtle background tint. Default 5, so rows 5, 10, 15 and so on, matching the existing precedent in `QuestionsScreen` where every 10th tile was marked. Setting `highlightEvery` to 0 disables it.

### 8.6 Column management and persistence

Column show/hide and reorder live in a Compose `ModalBottomSheet`: a checkbox list with drag handles. Tabulator owns only width-dragging, reporting new widths back over the bridge. Keeping the Compose side authoritative avoids two sources of truth for column state.

Any change to width, order, or visibility **autosaves immediately** to `views.json`. There is no explicit save action.

The same bottom sheet carries the view lifecycle actions, since autosave means edits land on the current view and there would otherwise be no way to branch off a new one:

- **Save as new view** copies the current view under a new `id` and prompts for a `name`. The new view appears in the drawer immediately.
- **Rename view** edits `name` only, leaving `id` untouched so nothing breaks.
- **Delete view** removes it. Deleting a built-in is allowed; "Reset to defaults" brings it back.
- **Reset to defaults** restores the three built-ins and leaves custom views alone.

Adding a computed column is also done here: pick an aggregate, a source column, a partition mode with its size or key, and a limit. The bottom sheet writes the struct; the `formula` mirror is generated on save.

Columns render at their configured widths with horizontal scrolling. A column may set `"frozen": true` to pin it during horizontal scroll, which Tabulator supports directly. Defaults to false.

### 8.7 WebView transport

The row payload is served to the page as a virtual URL through `WebViewAssetLoader` and fetched by JavaScript. At 5000 rows a full payload is roughly 700 KB of JSON, which is not safe to push through `evaluateJavascript` as a script string. `evaluateJavascript` is used only for small control messages such as applying a sort indicator or a column state.

The page and Tabulator load from the same `WebViewAssetLoader` origin, avoiding the CORS restrictions that apply to `file:///android_asset/`.

Bridge surface, all `@JavascriptInterface`:

- `onSortRequested(columnId, direction)` - Kotlin re-sorts, recomputes, republishes.
- `onColumnResized(columnId, width)` - Kotlin persists to `views.json`.
- `onRenderComplete(rowCount)` - used by test mode to know when `dump.json` is current.

## 9. Computed columns

### 9.1 MemberSelector

Every partition mode answers one question: for row `i`, which rows form its member set `S(i)`?

| Mode | `S(i)` |
|---|---|
| `group` | all rows where `row[by] == row[i][by]`, then trimmed to the newest `limit` members by timestamp |
| `bucket` | all rows where `floor(pos/N) == floor(pos(i)/N)` |
| `rolling` | rows at positions `pos(i)-N+1` through `pos(i)`, clamped at the start of the table |

One interface, three implementations, one shared set of aggregates.

A partition argument is **required**. There is no implicit whole-table mode. For a grand total, use `bucket:999999`, which places every row in bucket zero and gives every row the same figure. Note that `rolling:999999` is not the same thing: it produces a running cumulative aggregate, since each row still only sees the rows above it.

For `rolling`, a partial window near the top of the table produces a value computed from however many rows exist. Cells are not left blank.

### 9.2 Aggregates

| Function | Source | Timed-out members |
|---|---|---|
| `MIN` `MAX` `AVG` `MEDIAN` `SUM` `STDDEV` | numeric column | excluded |
| `COUNT` | any column, or `*` | included |
| `ACCURACY` | any column | included |

`ACCURACY` returns `(members where timedOut is false) / (all members) * 100`. It reads only the `timedOut` flag; the source column is syntactically required for grammatical consistency but semantically ignored. This is what makes `=ACCURACY(Question, group:Question)` and `=ACCURACY(Seconds, group:Question)` equivalent, and it is why the requirement "the column can even be Question" works without special-casing.

`STDDEV` is population standard deviation, so a single member yields `0.0` rather than undefined.

When a partition contains no usable members - every member timed out, for a function that excludes them - the cell renders `-`. `COUNT` and `ACCURACY` always have at least one member, since a row is always in its own partition, so they are always defined.

### 9.3 limit

`limit` trims a `group` partition to its newest `N` members, ordered by `timestamp` descending. Default 10, which reproduces the current app's behaviour of keeping only the last 10 attempts per card. `0` means unlimited.

It is set per computed column, so `Best (all time)` at `limit: 0` and `Best (last 10)` at `limit: 10` can sit side by side in one view.

`limit` is ignored for `bucket` and `rolling`, whose `N` already bounds the member set.

The effect, on Q03 with attempts `2.4, 8.0, 1.0, 0.5` newest-first:

| Aggregate | `limit: 0` | `limit: 2` |
|---|---|---|
| `MIN` | 0.5 | 2.4 |
| `AVG` | 2.98 | 5.20 |
| `MEDIAN` | 1.70 | 5.20 |

### 9.4 Formula grammar

The struct is canonical. The `formula` string is a human-readable mirror, regenerated from the struct on every save.

```text
formula := "=" FUNC "(" source ( "," arg )* ")"
FUNC    := MIN | MAX | AVG | MEDIAN | SUM | COUNT | ACCURACY | STDDEV
source  := <column name> | "*"
arg     := "group:" <column name>
         | "bucket:" <int>
         | "rolling:" <int>
         | "last:" <int>
```

Exactly one partition argument is required. `last:` is optional and only meaningful with `group:`.

On load: if a column carries `aggregate`, the struct is used and `formula` is regenerated to match. If it carries only `formula`, the string is parsed and the struct filled in. The struct always wins, so the two representations cannot drift.

Worked examples covering the whole original requirement set:

| Formula | Meaning |
|---|---|
| `=MIN(Seconds, group:Question, last:10)` | best of a card's last 10 attempts |
| `=AVG(Seconds, group:Question)` | all-time average per card |
| `=STDDEV(Seconds, group:Question, last:10)` | consistency per card |
| `=ACCURACY(Seconds, rolling:100)` | trailing-100 accuracy, recomputed per row |
| `=ACCURACY(Seconds, bucket:100)` | accuracy per block of 100 sorted rows |
| `=ACCURACY(Seconds, group:Question)` | accuracy per question |
| `=ACCURACY(Seconds, group:Date)` | accuracy per calendar day |
| `=COUNT(*, group:Question)` | attempts per card |
| `=ACCURACY(Seconds, bucket:999999)` | overall accuracy across the table |

### 9.5 Formula errors

A formula that fails to parse, or that names a column which does not exist, yields a column whose every cell renders `#ERR`. The column-management bottom sheet shows one line of explanation for it, such as `unknown column "Secnods"`.

**Every other column in the view continues to render normally.** Containing a hand-edit typo to a single column, rather than a whole view, is the reason the struct rather than the string is the runtime representation.

### 9.6 Built-in views

Three built-ins, seeded on first run. This reconciles the requirement to keep the Stats, List Rows, and History entries in the menu with the decision to delete the tile-grid screen: List Rows survives as a table preset rather than as a grid.

| id | Columns | Sort | Collapse |
|---|---|---|---|
| `stats` | Question, Seconds as "Last", Best, Avg, Med, Accuracy, Attempts | Question asc | Question |
| `history` | #, When, Question, Answer, Seconds, TimedOut | When desc | none |
| `list_rows` | #, Question | When desc | none |

All three set `filterToCurrentDeck: true`.

## 10. Metronome

### 10.1 Behaviour

The timer starts when a card's question is displayed and runs for `metronome.intervalSeconds`, default 10.0. It is a per-card countdown rather than a free-running beat, so each card gets a fresh full interval.

On fire:

1. Play the click once.
2. If the user has not flipped, append a history record with `timedOut: true` and `timeTaken` set to the interval.
3. Advance to a new random card.

If the user flipped before the timer fired, the successful record was already written at flip time. The timer firing then only clicks and advances; it does not write a second record.

Reading the answer consumes the same interval. Flipping at 4.0 s leaves 6.0 s to read before the card advances. This is intended: the pressure is the point.

Manual advance cancels the pending timer, and the next card starts a fresh full interval.

### 10.2 Lifecycle gating

The timer runs only while all three hold:

- `metronome.enabled` is true,
- the app is foregrounded, and
- the current screen is Flip Cards.

Leaving the Flip Cards screen or backgrounding the app **stops and resets** the timer. No record is written for the abandoned attempt. Returning to Flip Cards restarts the current card from zero with a full interval. There is no partial-progress resume, and no clicking anywhere but the Flip Cards screen.

### 10.3 Click sound

`SoundPool` with `AudioAttributes` on the media stream, so it respects volume and silent mode.

Resolution order: `metronome.soundPath` if set and readable, otherwise the bundled `click.wav` asset, otherwise silence. An unreadable configured path shows a Toast once per app launch and falls through to the bundled asset.

The bundled default is a synthesized short click, roughly 20 ms, generated at build-prep time rather than downloaded, so there is no licensing question and the asset stays around 2 KB. Two CC0 alternatives are provided alongside the spec for the user to drop in via `soundPath`.

The same sound plays whether or not the card was answered. There is no distinct timeout sound and no visual countdown.

## 11. Component boundaries

```text
simple-anki/
-app/src/main/assets/
--tabulator/
---tabulator.min.js            # vendored Tabulator, MIT
---tabulator.min.css           # vendored Tabulator stylesheet
--table.html                   # WebView host page, fetches rows, applies column state
--click.wav                    # synthesized default metronome click

-app/src/main/java/net/jacoblo/simpleanki/
--MainActivity.kt              # activity, drawer scaffold, screen dispatch
--GameView.kt                  # flip-card screen, extracted from MainActivity

--data/
---AnkiPaths.kt                # resolves data dir; the only file that touches Environment
---JsonStore.kt                # read, write, and recreate-on-corrupt for one JSON file
---HistoryRepository.kt        # load, append, trim history.json; runs the migration
---DeckRepository.kt           # load simple-anki.json, create the sample deck
---SettingsRepository.kt       # settings.json
---ViewsRepository.kt          # views.json
---Models.kt                   # AnkiCard, HistoryEntry, Settings, TableView, ColumnSpec

--table/
---TableEngine.kt              # pure: rows plus view definition to a rendered table
---MemberSelector.kt           # pure: group, bucket, and rolling partitions
---Aggregates.kt               # pure: MIN MAX AVG MEDIAN SUM COUNT ACCURACY STDDEV
---FormulaParser.kt            # pure: formula string to ColumnSpec
---FormulaWriter.kt            # pure: ColumnSpec to formula string
---TableScreen.kt              # composable host plus column-management bottom sheet
---TableBridge.kt              # @JavascriptInterface, JS to Kotlin

--metronome/
---MetronomeController.kt      # per-card countdown and lifecycle gating
---ClickPlayer.kt              # SoundPool on the media stream

--testmode/
---TestMode.kt                 # activation, fixture seeding, dump.json

-app/src/test/java/net/jacoblo/simpleanki/
--AggregatesTest.kt            # aggregate math, timeout exclusion, empty partitions
--MemberSelectorTest.kt        # group, bucket, and rolling boundary conditions
--TableEngineTest.kt           # full pipeline: filter, sort, compute, collapse, number
--FormulaTest.kt               # parse, generate, round-trip, and error cases
--MigrationTest.kt             # history.json without timedOut to with
```

Every file under `table/` except `TableScreen.kt` and `TableBridge.kt` is pure Kotlin with no Android dependencies, and is unit-testable on the JVM.

## 12. Testability considerations

### 12.1 Controllability

The app touches three external dependencies.

**Filesystem.** Today `Environment.getExternalStorageDirectory()` is hardcoded in six top-level functions, which makes every one of them untestable off-device. `AnkiPaths` becomes the single place that resolves the data directory, and `JsonStore` takes it as a constructor argument. A JVM test constructs `AnkiPaths` over a JUnit `TemporaryFolder` and exercises the full repository layer with no emulator. This refactor is required by Milestone 1 and is the enabling change for everything else in this section.

**Clock.** The metronome needs elapsed time, but no clock abstraction is being introduced. `metronome.intervalSeconds` is already a user-settable float, so a test fixture writes `0.3` into the seeded `settings.json` and timeouts fire in 300 ms of real time. Building a fake clock to avoid a 300 ms wait would be a worse trade than the wait.

**Audio.** `ClickPlayer` is an interface with two implementations, a real `SoundPool` one and a no-op. JVM tests construct the no-op directly, and on-device test mode selects it so an automated run stays silent. Sound output is not machine-verifiable and no attempt is made to assert on it; what is asserted is the `timedOut` record the metronome writes alongside the click.

### 12.2 Observability

The interesting behaviour is the transformation from raw history records to a rendered table, and that is entirely pure. `TableEngine.render(rows, view)` returns a `RenderedTable` value: final column list with widths and visibility, the sort that was applied, and the fully computed and collapsed row matrix. Asserting on that return value covers filtering, sorting, aggregation, collapsing, and numbering without rendering anything.

For on-device verification, test mode writes that same `RenderedTable` to `dump.json` on every render, after `onRenderComplete` fires. An agent reads the file and diffs it against an expected fixture.

Migration is observable through its output: `history.json.bak` exists and `history.json` has a `timedOut` key on every record.

The metronome emits a `HistoryEntry` with `timedOut: true`, which is directly assertable by reading `history.json`.

### 12.3 Verifiability

| Behaviour | How an agent confirms it |
|---|---|
| Aggregate math | JVM unit test on `Aggregates`, including all-timed-out partitions |
| Partition boundaries | JVM unit test on `MemberSelector`, including partial rolling windows and the last incomplete bucket |
| Full pipeline | JVM unit test on `TableEngine`, asserting the returned `RenderedTable` |
| Formula parsing | JVM round-trip test: struct to string to struct, plus a table of malformed inputs mapping to `#ERR` |
| Migration | JVM test over a temp dir with a pre-migration fixture |
| Column persistence | On-device: resize, restart, read `views.json` |
| Metronome timeout | On-device with `intervalSeconds: 0.3`, wait, read `history.json` |
| Rendered table | On-device: read `dump.json`, diff against fixture |

### 12.4 Test mode

Activated by an intent extra, gated on the debug build variant so it cannot be reached in release:

```bash
adb shell am start -n net.jacoblo.simpleanki/.MainActivity --ez test_mode true
```

When active:

- `AnkiPaths` resolves to `/sdcard/SimpleAnki-test/` instead of `/sdcard/SimpleAnki/`.
- That directory is wiped on launch, then seeded from bundled fixtures: a deterministic deck, a known history file, and a `settings.json` with a short metronome interval.
- `dump.json` is written after every table render.
- `ClickPlayer` is the no-op implementation.

Nothing else changes. The production path is unaffected because the only branch is inside `AnkiPaths` and the `ClickPlayer` factory.

## 13. Edge cases and error scenarios

| Situation | Behaviour |
|---|---|
| `history.json` missing or empty | Table renders headers with zero rows |
| Every attempt in a group timed out | Excluding aggregates render `-`; `ACCURACY` renders `0.0%` |
| `rolling:100` at row 3 | Computed from the 3 available rows, not blank |
| Last bucket has fewer than N rows | Computed from however many it has |
| `collapseDuplicatesOn` names a hidden column | Collapse still applies; visibility is presentation only |
| `collapseDuplicatesOn` names a nonexistent column | Ignored, no collapse, one line of explanation in the bottom sheet |
| Computed column references another computed column | Rejected at parse time as an unknown column, renders `#ERR`. Only base columns are valid sources |
| `views.json` has zero views | Treated as corrupt; recovered to defaults |
| `activeViewId` names a deleted view | Falls back to the first view in the array |
| `defaultSort` names a hidden column | Sort still applies; visibility is presentation only |
| `defaultSort` names a nonexistent column | Falls back to the base order, `When` descending |
| All columns in a view hidden | Table renders empty; the bottom sheet remains reachable to unhide |
| Deck filter removes every row | Table renders headers with zero rows |
| Metronome fires while the answer is showing | Clicks and advances, writes no second record |
| Metronome enabled with no cards loaded | Timer does not start |
| `soundPath` points at a missing file | Toast once per launch, falls through to the bundled asset |
| History exceeds `maxEntries` | Oldest records dropped at write time |
