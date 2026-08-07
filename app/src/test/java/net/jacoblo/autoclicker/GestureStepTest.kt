package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the line between a tap and a drag falls.
 *
 * Both recorders decide this, and until they shared a function they decided it
 * with two copies of the same number. The threshold is in pixels and the stored
 * coordinates are fractions, so the conversion is part of the same judgement.
 */
class GestureStepTest {

	private val screen = ScreenGeometry(1000, 2000, 0)

	private fun classify(vararg points: Pair<Float, Float>, duration: Long = 50): RuntimeStep =
		gestureStep(
			points.map { DragPoint(it.first, it.second, 0) },
			durationMs = duration,
			delayBefore = 0,
			screen = screen
		)

	@Test
	fun aFingerThatBarelyMovedIsATap() {
		val step = classify(100f to 200f, 119f to 200f)

		assertTrue("19px of travel is a tap, was $step", step is ClickStep)
	}

	@Test
	fun aFingerThatMovedIsADrag() {
		val step = classify(100f to 200f, 121f to 200f)

		assertTrue("21px of travel is a drag, was $step", step is DragStep)
	}

	/** Exactly at the threshold counts as a drag, so the boundary is not a guess. */
	@Test
	fun theThresholdItselfIsADrag() {
		assertTrue(classify(0f to 0f, CLICK_DISTANCE_PX to 0f) is DragStep)
	}

	/** Distance is measured start to end, not along the path. */
	@Test
	fun aFingerThatWanderedAndCameBackIsATap() {
		val step = classify(100f to 200f, 100f to 260f, 100f to 205f)

		assertTrue("ended 5px from where it began, was $step", step is ClickStep)
	}

	@Test
	fun aTapIsStoredAsAFractionOfTheScreen() {
		val step = classify(250f to 500f) as ClickStep

		assertEquals(0.25f, step.x, 1e-6f)
		assertEquals(0.25f, step.y, 1e-6f)
	}

	@Test
	fun aDragKeepsEveryPointAsAFraction() {
		val step = classify(500f to 1400f, 500f to 600f) as DragStep

		assertEquals(0.5f, step.points.first().x, 1e-6f)
		assertEquals(0.7f, step.points.first().y, 1e-6f)
		assertEquals(0.3f, step.points.last().y, 1e-6f)
	}

	/** The injector has nothing to hold for a press of no length. */
	@Test
	fun aTapAlwaysLastsAtLeastAMillisecond() {
		assertEquals(1L, (classify(10f to 10f, duration = 0) as ClickStep).duration)
	}

	/**
	 * Only the evdev recorder captures these; the overlay one leaves them at
	 * zero and the injector substitutes a device-typical value.
	 */
	@Test
	fun aTapCarriesTheContactItWasRecordedWith() {
		val step = gestureStep(
			listOf(DragPoint(10f, 10f, 0, pressure = 45, touchMajor = 130, touchMinor = 120)),
			durationMs = 90,
			delayBefore = 0,
			screen = screen
		) as ClickStep

		assertEquals(45, step.pressure)
		assertEquals(130, step.touchMajor)
		assertEquals(120, step.touchMinor)
	}

	@Test
	fun theWaitBeforeTheGestureIsKept() {
		val step = gestureStep(
			listOf(DragPoint(10f, 10f, 0)),
			durationMs = 50,
			delayBefore = 750,
			screen = screen
		)

		assertEquals(750L, step.delayBefore)
	}
}
