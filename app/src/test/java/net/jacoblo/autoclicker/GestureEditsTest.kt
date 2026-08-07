package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The editor's drag helpers, which have to leave a recorded path alone except
 * for the one thing being edited.
 */
class GestureEditsTest {

    private fun swipe(vararg points: DragPoint) = DragStep(points.toList(), delayBefore = 0)

    @Test
    fun translatingMovesEveryPointByTheSameAmount() {
        val moved = swipe(
            DragPoint(0.2f, 0.4f, 0),
            DragPoint(0.3f, 0.5f, 50),
            DragPoint(0.6f, 0.9f, 50)
        ).translatedTo(0.5f, 0.4f)

        assertEquals(0.5f, moved.points[0].x, 1e-5f)
        assertEquals(0.6f, moved.points[1].x, 1e-5f)
        assertEquals(0.9f, moved.points[2].x, 1e-5f)
        // Only x was asked to move, so y is untouched throughout.
        assertEquals(0.4f, moved.points[0].y, 1e-5f)
        assertEquals(0.9f, moved.points[2].y, 1e-5f)
    }

    @Test
    fun movingTheEndLeavesTheStartWhereItIs() {
        val edited = swipe(DragPoint(0.5f, 0.7f, 0), DragPoint(0.5f, 0.3f, 300))
            .withEnd(0.1f, 0.2f)

        assertEquals(0.5f, edited.points.first().x, 1e-5f)
        assertEquals(0.7f, edited.points.first().y, 1e-5f)
        assertEquals(0.1f, edited.points.last().x, 1e-5f)
        assertEquals(0.2f, edited.points.last().y, 1e-5f)
        // The travel time belongs to the point, not the coordinates.
        assertEquals(300L, edited.points.last().dt)
    }

    @Test
    fun swipeDurationLandsOnTheLastPointOnly() {
        val edited = swipe(DragPoint(0f, 0f, 0), DragPoint(1f, 1f, 300)).withSwipeDuration(900)

        assertEquals(0L, edited.points.first().dt)
        assertEquals(900L, edited.points.last().dt)
    }

    @Test
    fun editingAnEmptyPathIsHarmless() {
        val empty = DragStep(emptyList(), delayBefore = 0)

        assertEquals(empty, empty.withEnd(0.5f, 0.5f))
        assertEquals(empty, empty.withSwipeDuration(100))
        assertEquals(empty, empty.translatedTo(0.5f, 0.5f))
    }

    /** A double tap presses twice, so it takes twice as long as one tap. */
    @Test
    fun theEstimateCountsEveryTap() {
        val once = summarize(listOf(ClickStep(0.5f, 0.5f, duration = 50, delayBefore = 0)))
        val twice = summarize(
            listOf(ClickStep(0.5f, 0.5f, duration = 50, taps = 2, delayBefore = 0))
        )

        assertEquals(50L, once.durationMs)
        assertEquals(100L, twice.durationMs)
        // Still one step in the list either way.
        assertEquals(1, twice.actions)
    }
}
