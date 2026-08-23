# Simple Anki: Table Controls and Settings - Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Follows:** `plans/2026-08-22-simple-anki-table-metronome-accuracy.md` (16 tasks, merged, 270 tests)

**Goal:** Move column manipulation off grid gestures and into the edit sheet, expose every hand-editable setting in the UI, make the row tint visible and configurable, and add a persisting header context menu.

**Architecture:** No new subsystems. Three tasks over the existing seams: `ColumnSheet` gains the controls that Tabulator drags used to provide, a new settings screen writes through `SettingsRepository`, and `table.html` loses its drag handlers and gains a header context menu that routes to the bridge calls that already exist.

## User decisions taken before planning

| Question | Decision |
|---|---|
| Context menu scope | **Persisting operations only** - hide, freeze, move, copy. No cell edit, no row delete, because a JS-side edit changes a rendered string and reverts on the next render |
| Drag vs menu | **Remove drag entirely.** `movableColumns` and `resizableColumns` both off; the sheet is the only path |
| Settings scope | **Everything except `history.maxEntries`**, which stays JSON-only because lowering it permanently truncates practice history on the next flip |
| Row tint | **Both** - better fixed defaults for light and dark, AND configurable in JSON and the settings screen |

---

## Task A: Settings Screen

**Files:**
- Create: `app/src/main/java/net/jacoblo/simpleanki/SettingsScreen.kt`
- Create: `app/src/test/java/net/jacoblo/simpleanki/SettingsScreenTest.kt`
- Modify: `data/Models.kt`, `data/SettingsRepository.kt`, `AnkiDrawer.kt`, `MainActivity.kt`, `assets/table.html`

**Context:**
Only `metronome.enabled` has a UI today. Six settings are hand-edit-only, which makes the JSON files the real interface and the app a partial view of them.

**New fields on `TableSettings`:**

```kotlin
data class TableSettings(
	val defaultLimit: Int = 10,
	val highlightEvery: Int = 5,
	val defaultWindowSize: Int = 100,
	val highlightColorLight: String = "#DCE4EC",
	val highlightColorDark: String = "#2C333C"
)
```

Those two defaults are a starting point, not a mandate. **The current light tint is reported as hard to see on white - verify on the device and adjust until a banded row is obvious at arm's length.** Say what you chose and why.

**Settings screen contents,** grouped:

- **Metronome** - `enabled` (switch, moved from the drawer), `intervalSeconds` (decimal, must be > 0), `soundPath` (text, empty means the bundled click)
- **Table** - `highlightEvery` (int, 0 disables), `highlightColorLight` and `highlightColorDark` (hex text with a live swatch), `defaultWindowSize` (int, >= 1), `defaultLimit` (int, >= 0 where 0 means unlimited)
- **History** - `maxEntries` shown **read-only**, with one line saying it is edited in `settings.json` because lowering it deletes records permanently

`Screen` gains a `Settings` case. The drawer's metronome switch is replaced by a "Settings" entry; the switch itself moves into the screen.

**Requirements:**
- [ ] Every field persists to `settings.json` immediately on change
- [ ] `intervalSeconds` rejects zero and negatives - the metronome already guards this, but the UI should not let it be set
- [ ] `maxEntries` is displayed but not editable
- [ ] The two colours round-trip as hex strings and reject malformed input rather than writing garbage
- [ ] `highlightEvery` of 0 disables banding
- [ ] The new tint is clearly visible on the device in both themes
- [ ] The pure validation half lives in `ViewOps.kt` or a sibling and is JVM-tested

**Difficulty:** Medium

---

## Task B: Column Sheet Controls

**Files:**
- Modify: `table/ColumnSheet.kt`, `data/ViewOps.kt`, `assets/table.html`, `table/TableScreen.kt`, `table/TableGestures.kt`
- Modify: `app/src/test/java/net/jacoblo/simpleanki/ViewOpsTest.kt`

**Context:**
Column reorder and resize are Tabulator header drags today. On a phone, dragging a column border precisely is fiddly, and a horizontal drag competes with scrolling a six-column table sideways. Both move into the sheet, and the drags are disabled.

**Sheet gains:**

- **A duplicate-rows control.** `collapseDuplicatesOn` is honoured by the engine but has no UI at all. A dropdown: "Show all rows" plus one entry per base column. This is the user's request 1
- **Move up / move down** per column, replacing drag reorder
- **A width field** per column, replacing drag resize
- **Edit** on a computed column, reopening the builder pre-filled, replacing delete-and-rebuild

**`table.html` changes:** `movableColumns: false`, and `resizable: false` in `columnDefaults`. The `columnMoved` and `columnResized` handlers and their bridge calls stay - Task C's context menu uses the same path.

**Requirements:**
- [ ] A dropdown sets `collapseDuplicatesOn`, including back to none, and autosaves
- [ ] Move up/down reorders and autosaves; the first column cannot move up, the last cannot move down
- [ ] A width field sets `ColumnSpec.width` and autosaves, rejecting non-positive values
- [ ] Edit reopens the builder pre-filled with the existing aggregate, source, partition, limit, title and format
- [ ] Editing replaces the column in place, keeping its id and position
- [ ] Dragging a column header or border in the grid does nothing
- [ ] Each new operation is a pure function in `ViewOps.kt` with a JVM test

**Difficulty:** Medium

---

## Task C: Header Context Menu

**Files:**
- Modify: `assets/table.html`, `table/TableBridge.kt` if a new call is genuinely needed

**Context:**
Tabulator's `MenuModule` is already in the vendored bundle - `headerContextMenu` is available and a long-press fires `contextmenu` on Android, so no mouse is required.

**Scope is persisting operations only.** Kotlin re-pushes the payload on every sort, filter and resume, so a JS-side data edit reverts on the next render. Every menu item must therefore route to a bridge call that Kotlin persists.

**Menu items, all on the column header:**

| Item | Route |
|---|---|
| Hide this column | new bridge call, or reuse the sheet's toggle path |
| Freeze / unfreeze | new bridge call setting `ColumnSpec.frozen` |
| Move left / move right | the existing `reorder` bridge call |
| Copy column values | clipboard, JS-only, nothing to persist |

**Explicitly NOT included:** cell editing and row deletion. Both would change a rendered string rather than a history record, and would visibly revert on the next render. Offering them would be worse than not.

**Requirements:**
- [ ] A long-press on a column header opens the menu on the device
- [ ] Hide, freeze and move all persist - verified by backgrounding and resuming
- [ ] Copy puts the column's values on the clipboard
- [ ] No menu item edits a cell or deletes a row
- [ ] The menu does not interfere with header-tap sorting or horizontal scrolling
- [ ] Column titles in the menu are escaped the same way the header is - Tabulator uses `innerHTML` for menu labels, and the page holds the `Android` bridge

**Difficulty:** Medium

---

## Order

A, then B, then C. B and C both touch `table.html`; B disables the drags that C's menu replaces.
