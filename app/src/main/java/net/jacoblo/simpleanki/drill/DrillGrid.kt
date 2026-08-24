/*
 * The drill grid: one cell per item, at exactly the size settings asked for.
 *
 * Compose, deliberately, and not the Tabulator page the stats screens reuse. A cell here is a
 * tap target the user hits fifty times in a row while scoring, and routing each of those taps
 * out to the WebView and back would put a bridge round-trip between the finger and the mark.
 * The table stack also sizes its own columns, which this grid must not: the cell size in
 * settings exists precisely so that the choice is the user's.
 *
 * Holds no rule of its own. What a cell shows comes from DrillOps.isRevealed, what a tap means
 * comes from DrillOps.cycle by way of the caller, and which values are red arrives as a
 * predicate. There is nothing here for a JVM test to assert on, and that is the intent - every
 * rule the drills can get wrong lives in DrillOps, where the tests already are.
 */
package net.jacoblo.simpleanki.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.ItemStatus

/**
 * The cell grid.
 *
 * Scrolls in both directions rather than shrinking cells: the user chose the cell size in
 * settings, so a grid too wide for the viewport scrolls sideways instead of overriding it.
 * Both scroll offsets are remembered, so a scroll to the far corner of a large grid survives
 * the recomposition that every clock tick causes.
 *
 * Not lazy, and not a LazyVerticalGrid. A drill set is a screenful or a few - 52 cells at the
 * top of what the app ships with - and the lazy grids cannot be made to scroll on both axes
 * without surrendering the fixed cell size that is the whole point of this layout.
 *
 * @param geometry the shape from settings, passed through unvalidated by [DrillKind.geometry].
 *   The clamping that keeps a hand-edited value from crashing the layout happens HERE, since
 *   here it is a question about this frame rather than a change to what the file says.
 * @param scoring whether cells are scoring cells: an unmarked cell hides its value. Drives
 *   [DrillOps.isRevealed], which is the only thing that decides whether a value shows - and so
 *   is also the lever the screen covers a Fresh grid with, every cell of which is unmarked.
 * @param redValue whether a value is drawn red. A predicate rather than a suit test, so this
 *   grid draws a deck without ever learning what a card is.
 * @param onTap null when cells are not tappable, which is every state but EDITING and
 *   PAST_RUN. Null rather than an ignored lambda, so an untappable cell takes no ripple and
 *   no click semantics either.
 */
@Composable
fun DrillGrid(
	items: List<DrillItem>,
	geometry: DrillGeometry,
	scoring: Boolean,
	redValue: (String) -> Boolean,
	onTap: ((index: Int) -> Unit)?,
	modifier: Modifier = Modifier
) {
	// A hand-edited settings.json can say "columns": 0, and the settings screen's validators
	// guard its own input rather than the file. Zero would throw out of chunked() below and a
	// negative would throw out of it too, so the count is clamped to one column: a grid one
	// cell wide is visibly wrong in a way the user can go and fix, which a crash on the way to
	// the settings screen is not. The stored value is left exactly as it is.
	val columns = geometry.columns.coerceAtLeast(1)
	// Clamped for the same reason and no further: Constraints.fixed rejects a negative size
	// outright. A zero leaves an invisible grid, which is again the honest picture of what the
	// file says rather than a size nobody asked for.
	val cellWidth = geometry.cellWidthDp.coerceAtLeast(0).dp
	val cellHeight = geometry.cellHeightDp.coerceAtLeast(0).dp
	Column(
		modifier = modifier
			.verticalScroll(rememberScrollState())
			.horizontalScroll(rememberScrollState())
	) {
		items.chunked(columns).forEachIndexed { rowIndex, row ->
			Row {
				row.forEachIndexed { columnIndex, item ->
					// Recomputed from the row position rather than carried by a running
					// counter: this index is what a tap cycles, and an index off by a row
					// would mark a cell the user never touched.
					val index = rowIndex * columns + columnIndex
					DrillCell(
						item = item,
						scoring = scoring,
						red = redValue(item.value),
						width = cellWidth,
						height = cellHeight,
						onTap = if (onTap == null) null else ({ onTap(index) })
					)
				}
			}
		}
	}
}

/**
 * One cell: its value when [DrillOps.isRevealed] says so, and its mark while [scoring].
 *
 * The mark is drawn only while scoring, which is what makes Edit a toggle rather than a
 * one-way door: turning it off returns the grid to the plain revealed set the Finished
 * state shows, without discarding a single mark the user has made.
 */
@Composable
private fun DrillCell(
	item: DrillItem,
	scoring: Boolean,
	red: Boolean,
	width: Dp,
	height: Dp,
	onTap: (() -> Unit)?
) {
	val mark = if (scoring) markColor(item.status) else null
	Box(
		modifier = Modifier
			.size(width, height)
			// Tinted rather than filled, and the border carries the mark as well. A solid red
			// cell would swallow the red of a heart drawn on it, and suit has to stay legible
			// while the run is being scored.
			.background(mark?.copy(alpha = MARK_ALPHA) ?: Color.Transparent)
			.border(
				width = if (mark == null) 1.dp else 2.dp,
				color = mark ?: MaterialTheme.colorScheme.outlineVariant
			)
			.then(if (onTap == null) Modifier else Modifier.clickable(onClick = onTap)),
		contentAlignment = Alignment.Center
	) {
		if (DrillOps.isRevealed(item.status, scoring)) {
			Text(
				text = item.value,
				color = if (red) RED_VALUE else MaterialTheme.colorScheme.onSurface,
				style = MaterialTheme.typography.titleMedium,
				// A cell too narrow for its value is a settings problem, and clipping shows
				// it. Wrapping would silently change the row height the user configured.
				maxLines = 1,
				overflow = TextOverflow.Clip
			)
		}
	}
}

/** The tint a marked cell carries, or null for one the user has not marked. */
private fun markColor(status: ItemStatus): Color? = when (status) {
	ItemStatus.UNSCORED -> null
	ItemStatus.WRONG -> WRONG_MARK
	ItemStatus.RIGHT -> RIGHT_MARK
}

/**
 * Fixed colours, not colorScheme roles.
 *
 * Material3 has no "success" role to pair with error, and this app runs on dynamic colour, so
 * a right and a wrong drawn from the scheme would come out as two shades of the user's
 * wallpaper on one device and as a usable red and green on the next. Red means missed and
 * green means got it in every theme, which is the one thing these two must not lose.
 *
 * [RED_VALUE] is the brighter red of the two so that a heart stays a heart against a dark
 * background as well as a light one.
 */
private val WRONG_MARK = Color(0xFFD32F2F)
private val RIGHT_MARK = Color(0xFF2E7D32)
private val RED_VALUE = Color(0xFFE53935)

/** Light enough that a value drawn on a marked cell is still read as its own colour. */
private const val MARK_ALPHA = 0.18f
