package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlapping slices of the frame the recogniser is offered one at a time. */
class BandsTest {

	@Test
	fun `covers a phone screen in overlapping bands`() {
		assertEquals(
			listOf(Band(0, 700), Band(600, 1300), Band(1200, 1900), Band(1800, 2400)),
			bands(2400)
		)
	}

	/** Every row of the frame falls inside a band, so nothing is unreadable. */
	@Test
	fun `leaves no row uncovered`() {
		val bands = bands(2400)

		(0 until 2400).forEach { y ->
			assertTrue("row $y is in no band", bands.any { y >= it.top && y < it.bottom })
		}
	}

	/** Consecutive bands overlap, so a phrase on a seam is whole in one of them. */
	@Test
	fun `overlaps its neighbour`() {
		bands(2400).zipWithNext().forEach { (above, below) ->
			assertTrue("no overlap at ${above.bottom}", below.top < above.bottom)
		}
	}

	/** A last sliver would hold no whole line and only cost a recognition. */
	@Test
	fun `drops a trailing sliver`() {
		assertEquals(listOf(Band(0, 700), Band(600, 1000)), bands(1000))
		assertTrue(bands(2400).all { it.bottom - it.top > 350 })
	}

	/** Banding a frame no taller than one band would only repeat the whole look. */
	@Test
	fun `does not band a frame that is already short enough`() {
		assertEquals(emptyList<Band>(), bands(700))
		assertEquals(emptyList<Band>(), bands(400))
	}
}
