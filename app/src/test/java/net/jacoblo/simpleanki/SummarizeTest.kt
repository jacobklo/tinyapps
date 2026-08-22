package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers the windowing and timeout rules of the CardStats replacement. */
class SummarizeTest {

	@Test
	fun returnsNullsWhenTheQuestionHasNoAttempt() {
		val summary = summarize(listOf(attempt("other", 3.0f, 1L)), "missing")
		assertNull(summary.best)
		assertNull(summary.average)
	}

	@Test
	fun returnsNullsWhenEveryAttemptTimedOut() {
		val history = (1..3).map { attempt("q", 10.0f, it.toLong(), timedOut = true) }
		val summary = summarize(history, "q")
		assertNull(summary.best)
		assertNull(summary.average)
	}

	@Test
	fun countsOnlyTheNewestTenAttempts() {
		// The eleventh-newest attempt is the fastest, so a best of 5 proves it was dropped.
		val history = listOf(attempt("q", 1.0f, 1L)) + (2..11).map { attempt("q", 5.0f, it.toLong()) }
		val summary = summarize(history, "q")
		assertEquals(5.0f, summary.best!!, TOLERANCE)
		assertEquals(5.0f, summary.average!!, TOLERANCE)
	}

	@Test
	fun aTimedOutAttemptIsExcludedButStillConsumesAWindowSlot() {
		// Newest ten are one timeout plus nine successes; the older fast attempt stays out.
		val history = listOf(attempt("q", 1.0f, 1L), attempt("q", 99.0f, 2L, timedOut = true)) +
			(3..11).map { attempt("q", 4.0f, it.toLong()) }
		val summary = summarize(history, "q")
		// 1.0f would mean the timeout freed its slot; 13.5f would mean it was averaged in.
		assertEquals(4.0f, summary.best!!, TOLERANCE)
		assertEquals(4.0f, summary.average!!, TOLERANCE)
	}

	@Test
	fun ignoresAttemptsAtOtherQuestions() {
		val history = listOf(attempt("q", 2.0f, 1L), attempt("other", 0.5f, 2L))
		val summary = summarize(history, "q")
		assertEquals(2.0f, summary.best!!, TOLERANCE)
		assertEquals(2.0f, summary.average!!, TOLERANCE)
	}

	@Test
	fun averagesEveryAttemptInsideTheWindow() {
		val history = listOf(attempt("q", 2.0f, 1L), attempt("q", 4.0f, 2L))
		val summary = summarize(history, "q")
		assertEquals(2.0f, summary.best!!, TOLERANCE)
		assertEquals(3.0f, summary.average!!, TOLERANCE)
	}

	private fun attempt(
		question: String,
		timeTaken: Float,
		timestamp: Long,
		timedOut: Boolean = false
	) = HistoryEntry(question, "answer", timeTaken, timestamp, timedOut)

	private companion object {
		const val TOLERANCE = 0.0001f
	}
}
