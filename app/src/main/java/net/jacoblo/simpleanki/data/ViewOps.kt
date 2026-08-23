/*
 * What the column sheet does to the stored views, as pure functions.
 *
 * Split out of the Compose layer for the reason TableGestures.kt is split out of
 * TableScreen.kt: a composable is unavoidably Android, and the rules worth testing -
 * which id a new view gets, when a delete is refused, what a checkbox does to a base
 * column the view does not carry yet - are all pure. ColumnSheet only wires callbacks
 * to these.
 *
 * Every function returns a NEW value and mutates nothing, so a caller that ignores the
 * result has changed nothing. Free of Android imports on purpose.
 */
package net.jacoblo.simpleanki.data

import net.jacoblo.simpleanki.table.FormulaParser
import net.jacoblo.simpleanki.table.FormulaWriter
import net.jacoblo.simpleanki.table.TableEngine
import net.jacoblo.simpleanki.table.reordered
import java.util.Locale

/**
 * Width a column created from the sheet starts at; the width field takes it from there.
 *
 * Matches the width [ViewsRepository] gives a hand-written column that names none, so a
 * column has the same starting width however it got into views.json.
 */
const val NEW_COLUMN_WIDTH = 120

/** Longest id [uniqueId] derives from a name, before any uniquifying suffix. */
private const val MAX_ID_LENGTH = 40

/** Everything an id may not contain, collapsed to a single underscore. */
private val NON_ID_CHARS = Regex("[^a-z0-9]+")

/**
 * An id derived from [desired] that is not in [taken].
 *
 * Ids are lowercase ASCII because views.json exists to be hand-edited and an id is what
 * a hand-edit types: a view called "Slowest 10" becomes "slowest_10". A [desired] with no
 * ASCII letters or digits at all - a name written in Chinese, say - has nothing to derive
 * from and becomes [fallback], which the suffix below then makes unique. The visible name
 * is unaffected; only the internal id is transliterated away.
 *
 * Deriving rather than generating a UUID keeps a hand-edited file readable, and the
 * "_2", "_3" suffix is what keeps two views of the same name from colliding.
 *
 * [taken] is matched case-insensitively. What is derived here is always lowercase but
 * what is stored need not be - the base columns are capitalised, and views.json is
 * hand-editable - and two ids differing only in case are a trap for the exact-match
 * lookups that resolve them as much as for the human doing the editing.
 */
fun uniqueId(desired: String, taken: Set<String>, fallback: String): String {
	val reserved = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
	val slug = desired.lowercase(Locale.ROOT)
		.replace(NON_ID_CHARS, "_")
		.trim('_')
		.take(MAX_ID_LENGTH)
		// Again, because the truncation above can leave a trailing underscore behind.
		.trim('_')
		.ifEmpty { fallback }
	if (slug !in reserved) return slug
	var suffix = 2
	while ("${slug}_$suffix" in reserved) suffix++
	return "${slug}_$suffix"
}

/**
 * A copy of the view [sourceId] names, appended under a new id and made active.
 *
 * This is the only way to branch off a view. Every other edit autosaves onto whatever is
 * showing, built-ins included, so without this there would be no way to keep the History
 * view AND a variant of it.
 *
 * The id is derived from [name] and made unique, so two views may share a name without
 * sharing an identity. Unchanged when [sourceId] names no stored view.
 */
fun ViewsFile.saveAsNew(sourceId: String, name: String): ViewsFile {
	val source = views.firstOrNull { it.id == sourceId } ?: return this
	val id = uniqueId(name, views.map { it.id }.toSet(), fallback = "view")
	// The id doubles as the name when the caller passed a blank one. The sheet disables
	// its button in that case, so this only fires against a caller that did not.
	val copy = source.copy(id = id, name = name.trim().ifEmpty { id })
	return ViewsFile(id, views + copy)
}

/**
 * A copy with [viewId] renamed.
 *
 * The id is deliberately untouched. It is what the drawer selects by, what an autosave
 * finds the changed view by, and what [ViewsRepository.save] merges unknown keys onto, so
 * re-deriving it from the new name would break all three to make a file marginally
 * tidier. A blank name is refused rather than applied, which would leave an unclickable
 * drawer entry.
 */
fun ViewsFile.rename(viewId: String, name: String): ViewsFile {
	val trimmed = name.trim()
	if (trimmed.isEmpty()) return this
	return copy(views = views.map { if (it.id == viewId) it.copy(name = trimmed) else it })
}

/**
 * A copy without [viewId], or null when the deletion is refused.
 *
 * Built-ins delete like anything else - [ViewsRepository.resetBuiltIns] is the way back,
 * so nothing is lost for good. The last remaining view is the one exception: deleting it
 * would leave the drawer with no table entries and so no way to reach the sheet that
 * creates one. Refusing is the null; the caller says so.
 *
 * [ViewsFile.activeViewId] falls back to the first survivor when the deleted view was the
 * active one, and is otherwise left alone.
 */
fun ViewsFile.delete(viewId: String): ViewsFile? {
	if (views.size <= 1) return null
	val remaining = views.filterNot { it.id == viewId }
	if (remaining.size == views.size) return this
	return ViewsFile(if (activeViewId == viewId) remaining.first().id else activeViewId, remaining)
}

/**
 * A copy with [columnId] shown if it was hidden and hidden if it was shown.
 *
 * All eight base columns are always offered by the sheet, but a view need not carry a
 * spec for each - the Stats view carries two. Ticking one the view has no spec for
 * therefore ADDS the spec rather than doing nothing, which is what makes "add a column"
 * and "show a column" the same gesture for a base column.
 *
 * An id that is neither in the view nor a base column is nothing this can add, so it is
 * left alone.
 */
fun TableView.toggleColumn(columnId: String): TableView {
	if (columns.any { it.id == columnId }) {
		return copy(
			columns = columns.map { if (it.id == columnId) it.copy(visible = !it.visible) else it }
		)
	}
	val base = TableEngine.baseColumn(columnId) ?: return this
	return copy(columns = columns + ColumnSpec(base.id, base.id, NEW_COLUMN_WIDTH))
}

/**
 * A copy with [spec] appended, re-idded so it collides with nothing.
 *
 * The sheet hands over the user's title as the id and this derives the real one, so the
 * uniqueness rule lives in one place rather than in the caller.
 *
 * All eight base ids are reserved, not just the ones this view carries a spec for. A
 * column the user titled "Seconds" must not end up answering to the base column's id, and
 * a base column absent from the view today is one checkbox away from being in it.
 */
fun TableView.addComputed(spec: ColumnSpec): TableView {
	val taken = columns.map { it.id }.toSet() + TableEngine.BASE_COLUMNS.map { it.id }
	return copy(columns = columns + spec.copy(id = uniqueId(spec.id, taken, fallback = "column")))
}

/**
 * A copy with [columnId]'s spec replaced by [spec], in place.
 *
 * In place is the whole of it. The edited column keeps its id, because the sort, the
 * collapse key, and views.json all name a column by it, and it keeps its POSITION,
 * because the alternative - drop it and append the rebuilt one - moves a column to the
 * end of the table for the crime of being edited, which is the delete-and-rebuild this
 * replaced.
 *
 * It keeps its width, visibility and frozen flag too. Those are the user's, and
 * [buildComputedSpec] has no opinion on any of them: it opens every column at
 * [NEW_COLUMN_WIDTH] because that is where a NEW column starts, not because an edited one
 * should snap back to it.
 *
 * What the builder does own is taken from [spec] - the title, the struct, the formula
 * mirror, and the format the struct implies. The format in particular must follow the
 * aggregate rather than be preserved: an AVG changed to a COUNT would otherwise keep
 * TWO_DP and render five attempts as "5.00". See [defaultFormat].
 *
 * A base column is refused. It has no struct to edit, and writing a computed spec onto
 * its id would leave two meanings for one name. An unknown id is left alone.
 */
fun TableView.replaceComputed(columnId: String, spec: ColumnSpec): TableView {
	if (TableEngine.baseColumn(columnId) != null) return this
	if (columns.none { it.id == columnId }) return this
	return copy(
		columns = columns.map { existing ->
			if (existing.id != columnId) {
				existing
			} else {
				spec.copy(
					id = existing.id,
					width = existing.width,
					visible = existing.visible,
					frozen = existing.frozen
				)
			}
		}
	)
}

/** What the column sheet's builder produced: the column, or why it was refused. */
sealed interface BuildResult {
	data class Ok(val spec: ColumnSpec) : BuildResult
	data class Err(val message: String) : BuildResult
}

/**
 * The title a computed column takes when the user names none.
 *
 * The builder shows it as the title field's placeholder, so what the user sees greyed out
 * is literally what they get by leaving the field alone.
 */
fun generatedTitle(aggregate: Aggregate, source: String): String = "${aggregate.name} $source"

/**
 * The column the builder's pickers describe, or why that pairing is refused.
 *
 * Pure, and here rather than in the composable, because everything worth getting right
 * about the builder is: that ACCURACY renders as a percentage rather than as a bare
 * number, that a window with no size falls back to the user's own default rather than to
 * zero, and that AVG over Question is refused instead of rendering "-" in every cell with
 * no explanation anywhere.
 *
 * That last rule is [FormulaParser.sourceError] itself, not a second copy of it. The
 * pickers can express any aggregate against any source, so without it the easy path would
 * accept what the typed formula rejects - and the user would get the same broken column
 * with none of the message.
 *
 * [title] is taken verbatim as both id and title; [TableView.addComputed] derives the id
 * actually stored, since only it knows what is already taken. A blank one falls back to
 * [generatedTitle].
 */
fun buildComputedSpec(
	aggregate: Aggregate,
	source: String,
	partition: Partition,
	limit: Int,
	title: String,
	tableSettings: TableSettings
): BuildResult {
	FormulaParser.sourceError(aggregate, source, TableEngine.BASE_COLUMN_IDS)?.let {
		return BuildResult.Err(it)
	}
	val spec = ComputedSpec(aggregate, source, sized(partition, tableSettings), limit)
	val name = title.trim().ifEmpty { generatedTitle(aggregate, source) }
	return BuildResult.Ok(
		ColumnSpec(
			id = name,
			title = name,
			width = NEW_COLUMN_WIDTH,
			format = defaultFormat(aggregate),
			computed = spec,
			// Written here for the reason DefaultViews writes it: a struct with no mirror
			// beside it is not a shape that survives a save, so generating it now makes the
			// column in memory byte for byte what the autosave is about to store.
			formula = FormulaWriter.write(spec)
		)
	)
}

/**
 * What the computed-column builder's pickers open on.
 *
 * [groupKey] and [size] are both carried whatever [partition] is, because the three
 * partition buttons share one builder: switching from a rolling window to a group has to
 * put SOMETHING in the By picker, and switching back has to put something in Size.
 */
data class BuilderSeed(
	val aggregate: Aggregate,
	val source: String,
	val partition: Partition,
	val groupKey: String,
	val size: Int,
	val limit: Int
)

/**
 * The values the builder opens on for [spec], or the ones a new column starts from when
 * it is null.
 *
 * This is what "edit" means on the UI side: reopening the builder on the column's own
 * pickers rather than on the defaults, so that saving an edit that changed nothing gives
 * back the column it started from. Pure and here rather than in the composable because
 * the fallbacks are the whole content of it, and two of them are not obvious.
 *
 * The first is [size] for a grouped column, which has no window at all. It opens at the
 * user's default rather than at zero, since zero is the one value [sized] refuses.
 *
 * The second is [limit], which is read from [spec] only for a GROUP. A bucket or rolling
 * column stores a limit of 0 - [buildComputedSpec] writes it, because a window is already
 * a count of rows - so seeding the group field from it would silently turn "the last 10
 * attempts" into "all of them" the moment the user switched a rolling column to a group.
 */
fun builderSeed(spec: ComputedSpec?, tableSettings: TableSettings): BuilderSeed {
	val partition = spec?.partition ?: Partition.Group(TableEngine.ID_QUESTION)
	return BuilderSeed(
		aggregate = spec?.aggregate ?: Aggregate.AVG,
		source = spec?.source ?: TableEngine.ID_SECONDS,
		partition = partition,
		groupKey = (partition as? Partition.Group)?.by ?: TableEngine.ID_QUESTION,
		size = when (partition) {
			is Partition.Group -> tableSettings.defaultWindowSize
			is Partition.Bucket -> partition.size
			is Partition.Rolling -> partition.size
		},
		limit = if (partition is Partition.Group && spec != null) {
			spec.limit
		} else {
			tableSettings.defaultLimit
		}
	)
}

/**
 * How a computed column renders when the user picked no format, derived from what the
 * aggregate answers in.
 *
 * The builder offers no format picker on purpose: every aggregate has exactly one sensible
 * rendering, and TableEngine's blanket TWO_DP default is right for only six of the eight.
 * COUNT answers in whole attempts and ACCURACY in percent - "5.00" and "83.33" are both
 * simply wrong. An exhaustive when rather than an else, so a ninth aggregate is a compile
 * error here rather than a column that quietly renders in the wrong units.
 */
private fun defaultFormat(aggregate: Aggregate): CellFormat = when (aggregate) {
	Aggregate.COUNT -> CellFormat.INT
	Aggregate.ACCURACY -> CellFormat.PERCENT
	Aggregate.MIN, Aggregate.MAX, Aggregate.AVG,
	Aggregate.MEDIAN, Aggregate.SUM, Aggregate.STDDEV -> CellFormat.TWO_DP
}

/**
 * [partition] with a block or window size of at least 1, defaulted to
 * [TableSettings.defaultWindowSize].
 *
 * The size field can be emptied while it is being retyped, which reaches here as a 0. That
 * is the same case [ViewsRepository] defaults on the way in from a hand-edited file, and
 * defaulting it identically here is what keeps the column in memory agreeing with the one
 * on disk: left at 0 it would be clamped to a partition of one row, so the column would
 * render each row's own value while looking like an aggregate.
 */
private fun sized(partition: Partition, tableSettings: TableSettings): Partition {
	val size = tableSettings.defaultWindowSize
	return when (partition) {
		is Partition.Group -> partition
		is Partition.Bucket -> if (partition.size >= 1) partition else Partition.Bucket(size)
		is Partition.Rolling -> if (partition.size >= 1) partition else Partition.Rolling(size)
	}
}

/**
 * A copy without [columnId], which must not be a base column.
 *
 * A base column is never truly removed - all eight always exist and the checkbox toggles
 * visibility - so dropping its spec here would delete the width and title the user had
 * given it and silently reset both. Anything else is the user's own: a computed column
 * they built, or a column left behind by a hand-edit that TableEngine already warns is
 * unknown. Both are genuinely deletable, and the second has no other way out of the file.
 */
fun TableView.removeColumn(columnId: String): TableView {
	if (TableEngine.baseColumn(columnId) != null) return this
	return copy(columns = columns.filterNot { it.id == columnId })
}

/**
 * A copy that collapses duplicate rows on [columnId], or shows every row when it is null.
 *
 * Only a base column is accepted, and that is why this exists rather than the sheet
 * writing the field itself. TableEngine warns and collapses nothing when the key names a
 * computed column - a computed value belongs to a partition rather than to a row, and
 * does not exist until after the sort the collapse follows - or an unknown one. The
 * dropdown offers the eight base columns; this is what stops any other id reaching the
 * field however the caller was wired.
 *
 * A bad key already in a hand-edited views.json is untouched by this and still warns. It
 * is the UI that must not be able to MAKE one.
 */
fun TableView.collapseOn(columnId: String?): TableView {
	if (columnId != null && TableEngine.baseColumn(columnId) == null) return this
	return copy(collapseDuplicatesOn = columnId)
}

/**
 * A copy with [columnId] moved [delta] places along the view's own column list.
 *
 * Positions are counted over ALL of [TableView.columns], hidden columns included, rather
 * than over the visible subset the sheet ticks. The stored order is what the page is
 * handed and what a later unhide reveals, so a move that stepped over a hidden column
 * without moving past it would reshuffle the hidden ones behind the user's back.
 *
 * A move off either end is refused rather than clamped. The sheet disables the button
 * there, and clamping would leave a live-looking button that quietly did nothing.
 *
 * Expressed as a reorder because [reordered] is already the one place a new column order
 * is applied to a view - it was written for the header drag this replaces. All this adds
 * is which order a tap asks for.
 */
fun TableView.moveColumn(columnId: String, delta: Int): TableView {
	val from = columns.indexOfFirst { it.id == columnId }
	if (from < 0) return this
	val to = from + delta
	if (to < 0 || to >= columns.size) return this
	val ids = columns.mapTo(ArrayList()) { it.id }
	ids.add(to, ids.removeAt(from))
	return reordered(ids)
}

/** Narrowest column the width field accepts. Zero is not a column and a negative is not a width. */
const val MIN_COLUMN_WIDTH = 1

/**
 * The column width [text] describes, refusing anything that is not a positive whole
 * number of pixels.
 *
 * [FieldResult] rather than [BuildResult] for the reason SettingsOps.kt gives beside it:
 * the same Ok/Err shape, made generic because a field's value need not be a [ColumnSpec].
 *
 * The empty string is refused rather than special-cased, and has to be: the field writes
 * on the keystroke that parses, so clearing "120" to type "80" passes through "" and
 * through "8". Refusing "" is what keeps the column from being written to nothing halfway
 * through. "8" is a width the user asked for, briefly, and is written.
 */
fun parseColumnWidth(text: String): FieldResult<Int> {
	val value = text.trim().toIntOrNull()
	if (value == null || value < MIN_COLUMN_WIDTH) {
		return FieldResult.Err("Must be a whole number of pixels, $MIN_COLUMN_WIDTH or more")
	}
	return FieldResult.Ok(value)
}
