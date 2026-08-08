package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides whether recognised words are the phrase.
 *
 * The misreads below are the real ones: a 34sp heading read at three sizes came
 * back as "chhoose a password", "chooseă password", and split across blocks.
 */
class SpellingRunTest {

	@Test
	fun `matches words read exactly`() {
		assertEquals(0..2, spellingRun(listOf("Choose", "a", "password"), "Choose a password"))
	}

	@Test
	fun `matches a run inside a longer screen`() {
		val words = listOf("Passwords", "should", "Choose", "a", "password", "Continue")
		assertEquals(2..4, spellingRun(words, "Choose a password"))
	}

	@Test
	fun `ignores case the recogniser got wrong`() {
		assertEquals(0..1, spellingRun(listOf("a", "passWord"), "a password"))
	}

	@Test
	fun `ignores an accent the recogniser invented`() {
		assertEquals(0..1, spellingRun(listOf("chooseă", "password"), "Choose a password"))
	}

	@Test
	fun `ignores where the recogniser put the spaces`() {
		assertEquals(0..1, spellingRun(listOf("Choosea", "password"), "Choose a password"))
		assertEquals(0..2, spellingRun(listOf("Choo", "sea", "password"), "Choose a password"))
	}

	@Test
	fun `allows one wrong character in a long phrase`() {
		assertEquals(0..2, spellingRun(listOf("chhoose", "a", "password"), "Choose a password"))
	}

	@Test
	fun `refuses two wrong characters in a long phrase`() {
		assertNull(spellingRun(listOf("chhoosse", "a", "password"), "Choose a password"))
	}

	@Test
	fun `allows nothing wrong in a short phrase`() {
		assertEquals(0..0, spellingRun(listOf("Skip"), "Skip"))
		assertNull(spellingRun(listOf("Slip"), "Skip"))
		assertNull(spellingRun(listOf("Ski"), "Skip"))
	}

	@Test
	fun `finds nothing when the phrase is not there`() {
		assertNull(spellingRun(listOf("Continue", "Not", "now"), "Set a profile picture"))
	}

	@Test
	fun `finds nothing in an empty phrase or an empty screen`() {
		assertNull(spellingRun(listOf("Continue"), "   "))
		assertNull(spellingRun(emptyList(), "Continue"))
	}
}
