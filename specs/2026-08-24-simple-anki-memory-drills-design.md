# Memory Drills Design Spec

**Date:** 2026-08-24

**Status:** approved, ready for `jja-writing-plans`

## Purpose

Simple Anki's Flip Cards screen warms up the "Images" discipline for memory competition. This spec adds the two other warm-up disciplines the user trains: a grid of random two-digit numbers, and a shuffled deck of playing cards. Each is a timed memorisation drill followed by self-scoring, and every run is kept so accuracy and speed can be tracked over time.

The two drills are behaviourally identical. They differ only in what an item is and how one is generated. Everything else - the grid, the clock, the buttons, the scoring cycle, the storage, the stats table, the run picker - is one design serving both.

## Scope

In scope: two drill screens, two stats screens, a run picker, a run storage format, and the settings that size the grids.

Out of scope, deliberately: any recall input (the user recalls on paper or mentally; the app only scores), typed-answer checking, export, computed/pivot columns on the drill stats tables, and a combined cross-drill table.

## The two drills

### Numbers

A set of `count` two-digit numbers, `count` defaulting to 50 and settable. Values are drawn uniformly from `00`-`99` **with replacement**, so duplicates occur naturally, matching a true random digit stream. Each is rendered zero-padded to two characters: `07`, `42`, `91`.

### Poker

One full 52-card deck, shuffled. The count is **fixed at 52** and is not settable - this supersedes the earlier "both counts settable" decision.

A card renders as rank plus suit glyph: `A♠`, `7♥`, `10♦`, `K♣`. Ranks are `A 2 3 4 5 6 7 8 9 10 J Q K`; suits are `♠ ♥ ♦ ♣`. Hearts and diamonds render in red, spades and clubs in the ordinary foreground colour, so suit is carried by colour as well as by shape.

> **How the glyphs reach the screen.** The project's editing rules forbid non-ASCII characters in source, and the glyphs are wanted on screen. Kotlin's `\uXXXX` escapes satisfy both at once: the source file stays pure ASCII, and the glyph appears at runtime. The four suits are `\u2660` (♠), `\u2665` (♥), `\u2666` (♦), and `\u2663` (♣) - the SOLID variants, not the hollow ones at `\u2661` and `\u2662`. Escapes rather than a string resource, because a resource would drag an Android import into the generator and cost it its JVM tests.

## Screen anatomy

Top to bottom: the timer, the grid, the button row.

**Timer.** Counts up from Start, displayed `mm:ss` in whole seconds. Stored as float seconds so the derived rate column keeps its precision.

**Grid.** A plain grid of equally sized cells, no row numbers and no grouping rules. Column count, cell width, and cell height all come from settings, per drill. Rows follow from the item count. When the grid is wider or taller than the viewport it scrolls in both directions rather than shrinking cells - a cell whose size the user chose must keep that size.

**Button row.** Five buttons: `Start`, `Done`, `New`, `Edit`, `Runs`.

## Run lifecycle

A drill screen is always in exactly one of five states.

| State | Grid shows | Start | Done | New | Edit | Runs |
|---|---|---|---|---|---|---|
| **Fresh** | full grid, all cells empty | on | off | on | off | on |
| **Running** | values revealed, clock ticking | off | on | on | off | on |
| **Finished** | values still revealed, clock frozen | off | off | on | on | on |
| **Editing** | scoring cells (see below) | off | off | on | on | on |
| **Past run** | scoring cells of a stored run | off | off | on | on | on |

**Entering the screen** generates a set and lands in Fresh. The grid is drawn at full size with empty cells, so the shape and scale of what is coming is visible before the clock starts, but no value is.

**Start** reveals every value and starts the clock. This is the only thing that reveals a set, which is what makes the recorded time meaningful.

**Done** freezes the clock and **writes the run to storage immediately**, unscored. The grid stays revealed; scoring begins when the user presses Edit.

**Edit** toggles the scoring overlay on and off. Toggling it off returns to the Finished view - values revealed, no scoring cells.

**New** discards whatever is on screen, without confirmation and without saving, generates a fresh set, and returns to Fresh. It is enabled in every state, including while a past run is open, where it is the escape hatch back to a live drill.

**Runs** opens the run picker.

### Backgrounding

If the app is backgrounded while **Running**, the run is abandoned: the clock resets to zero and the same set is re-covered, so the screen returns to Fresh holding the set it already had. The user can press Start and attempt that same set again. Backgrounding in any other state does nothing, because the run is already stored.

Rotation destroys and recreates the activity and therefore counts as backgrounding. A run in progress does not survive it.

## Scoring

In Editing and Past run states every cell becomes a scoring cell with three states, cycled by tapping:

1. **Unscored** - the cell is blank. The value is hidden. This is the starting state of every cell.
2. **Wrong** - the value is revealed, and the cell is red.
3. **Right** - the value stays revealed, and the cell is green.

A fourth tap returns to Unscored, which **hides the value again** as well as clearing the mark.

The order is deliberate: the first tap is the one that reveals the answer, and revealing it defaults to "I got this wrong" unless the user says otherwise. Marking a long run of correct answers costs two taps each; that is accepted.

**Accuracy is `right / count`.** Unscored and wrong count against the user identically. They remain distinct in storage - wrong means "checked and missed", unscored means "not checked" - but they are worth the same in the percentage.

**A live tally** is shown while scoring, reading `40/50 = 80%` and updating on every tap.

**Every tap autosaves**, rewriting that run's record immediately, consistent with how views, widths, and settings already behave in this app. There is no save button and nothing is ever pending.

### Consequence: unscored runs read as 0%

Because Done writes the run before any scoring happens, a finished-but-unscored run appears in the stats table with 0 right, 0 wrong, and 0% accuracy. This is intended, and it is distinguishable from a genuinely failed run: a failed run has `Wrong = Count`, an unscored one has `Right + Wrong = 0`.

## Past runs

Any stored run can be reopened and re-scored. Two routes reach the same place:

- **The Runs button** on a drill screen opens a picker listing that drill's recent runs, newest first, each line showing when it was, how long it took, and its accuracy. The picker shows the 50 most recent; the stats screen holds everything.
- **A row tap on the stats table** opens that run directly.

An opened past run shows its stored items and their stored statuses, with its stored duration frozen in the timer. Scoring behaves exactly as it does for a fresh run, autosaving into that run's record. `Start` and `Done` are dead - the run's time is history and must not be overwritten. `New` abandons it and starts a fresh live drill.

## Storage

Two files, alongside the existing ones under the app's data directory:

- `numbers-runs.json`
- `poker-runs.json`

Each is a bare JSON array of run objects, matching the convention `history.json` already uses. Both are hand-editable, like every other file this app writes.

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

`status` is one of `""`, `"right"`, `"wrong"` - the user's own vocabulary, and readable in a text editor.

The item count is **derived from `items.length`** and is not stored separately, so the two can never disagree. `id` is the start timestamp rendered as a decimal string; a collision would require two runs to start in the same millisecond, which is not reachable given a run spans seconds.

**Retention is 5000 runs per file**, newest kept, matching `history.json`. A Poker run with its full 52-item list is roughly 2 KB, so a full file is a few megabytes.

Reads and writes go through the existing atomic-write-and-quarantine machinery: a write replaces the file by rename so a kill mid-write cannot corrupt it, and a file that fails to parse is moved aside rather than overwritten, then recreated empty. A failed write reports itself and the run carries on.

## Stats screens

One per drill, each reusing the existing Tabulator page unchanged. The runs are turned into a finished table and handed to the same web view the flip-card tables use, so row banding, frozen columns, both-axis scrolling, and the styling all come for free.

**Columns:** `#`, `When`, `Time`, `Count`, `Right`, `Wrong`, `Accuracy`, `Sec/Item`.

- `When` - `MM-dd HH:mm:ss`, matching the existing history table
- `Time` - the run duration as `mm:ss`
- `Accuracy` - `right / count` as a percentage
- `Sec/Item` - `seconds / count` to two decimals, the rate figure

The columns are fixed. There is no column sheet, no add/remove, no width editing, and no computed columns on these tables - the drill stats screens are a fixed report, not a configurable view, and they do not appear in `views.json`.

**Sorting works.** Tapping a header sorts by that column; tapping the sorted one reverses it. The default is `When` descending, newest first. Sorting happens over the run list before the table is built.

**Tapping a row** opens that run for scoring.

## Settings

Two new sections in the settings screen, each also present in `settings.json`:

**Numbers** - item count (default 50), column count (default 5), cell width, cell height.

**Poker** - column count (default 6), cell width, cell height. No item count; Poker is fixed at 52.

Cell width and height are in density-independent pixels. Defaults are chosen so the default grids fit a 360dp phone without horizontal scrolling, and so that raising the column count or the cell size is what makes the grid scroll.

Unknown or malformed values fall back to defaults rather than failing, as the existing settings already do.

## Navigation

Four new drawer entries, each drill sitting next to its own stats:

```text
Flip Cards
---
Numbers
Numbers Stats
Poker
Poker Stats
---
Stats / History / List Rows      (the existing flip-card views)
---
Settings
```

The top bar is unchanged. The lifetime review counter stays a flip-card figure and is not advanced by drills. The column-sheet action is absent on all four new screens, since none of them has configurable columns.

The metronome does not apply to drills and requires no change: it is already gated to the Flip Cards screen.

## Error handling

Every failure mode follows what the app already does. An unreadable or unparseable run file is quarantined and recreated empty rather than destroyed. A failed write raises a toast and leaves the screen ahead of disk, to be reconciled on the next load. Missing storage permission leaves the drill screens usable but unable to save; the existing permission prompt covers granting it.

An empty run file is not an error - it is a user who has not drilled yet, and the stats table shows its empty placeholder.

## Testability Considerations

**Controllability.** The only two external dependencies are randomness and the clock. Set generation takes an injected random source, so a test pins a seed and asserts on an exact set; production passes the default. The clock is never read inside testable code - the pure layer takes elapsed milliseconds as a parameter, and only the screen reads the wall clock. The filesystem is already abstracted: the existing path resolver has a test seam that points every file at a temporary folder, so a JVM test exercises the real repository against real files with no emulator.

**Observability.** Everything worth verifying is pure and returns a value: set generation, the three-state tap cycle, accuracy computation, run summarisation, sorting, and the finished stats table. Each is a function from inputs to an asserted output, with no Android import anywhere in the chain, so the whole feature is covered by JVM tests. The stats table additionally rides the existing render-dump path, which writes the finished table to a file under test mode, so an agent can verify what was rendered without reading the screen.

**Verifiability.** The run files are the observable state and they are plain JSON, so a test asserts on the file after an operation. Structural assertions only, never exact serialized text - the JSON implementation on the test classpath does not preserve key order the way the device's does, and asserting on rendered text would pass locally and fail on hardware.

**Test mode.** No new mechanism. The existing debug-only test mode redirects every path to a separate directory and seeds fixtures there; it gains the two run files so a drill can be driven against known data. Nothing in this feature reads the directory name to decide how to behave.

**The one risk that needs a deliberate test.** The tap cycle and the accuracy figure are the correctness core, and both are easy to write a test that passes for the wrong reason - a cycle test that never checks the reveal/hide half, or an accuracy test whose numbers happen to agree under both the `right/count` and `right/(right+wrong)` definitions. Test data must be chosen so the two definitions disagree, and the cycle must be asserted on both the mark and the visibility at every step.
