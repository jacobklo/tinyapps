package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.table.Aggregates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the eight aggregate functions over a member set.
 *
 * Every fixture is chosen so that including the timed-out members would change the answer
 * of each excluding function, which is what makes the timeout rule testable rather than
 * merely asserted.
 */
class AggregatesTest {

	/** Four answered members: 2, 4, 6, 8. Even count, so MEDIAN must average 4 and 6. */
	private val allAnswered = Members(2.0 to false, 4.0 to false, 6.0 to false, 8.0 to false)

	/**
	 * The same four answered members plus three timeouts, one below the smallest, one above
	 * the largest, and one between them so that including them would move the median too.
	 */
	private val mixed = Members(
		2.0 to false, 0.5 to true, 4.0 to false, 100.0 to true,
		6.0 to false, 5.5 to true, 8.0 to false
	)

	private val allTimedOut = Members(3.0 to true, 5.0 to true, 7.0 to true)

	@Test
	fun allMembersAnswered() {
		assertValue(2.0, allAnswered.of(Aggregate.MIN))
		assertValue(8.0, allAnswered.of(Aggregate.MAX))
		assertValue(5.0, allAnswered.of(Aggregate.AVG))
		assertValue(5.0, allAnswered.of(Aggregate.MEDIAN))
		assertValue(20.0, allAnswered.of(Aggregate.SUM))
		assertValue(SQRT_5, allAnswered.of(Aggregate.STDDEV))
		assertValue(4.0, allAnswered.of(Aggregate.COUNT))
		assertValue(100.0, allAnswered.of(Aggregate.ACCURACY))
	}

	@Test
	fun mixedMembersExcludeTheTimeoutsFromEverythingButCountAndAccuracy() {
		// Including the timeouts would give 0.5, 100.0, 18.0, 5.5, 126.0 and a wider spread.
		assertValue(2.0, mixed.of(Aggregate.MIN))
		assertValue(8.0, mixed.of(Aggregate.MAX))
		assertValue(5.0, mixed.of(Aggregate.AVG))
		assertValue(5.0, mixed.of(Aggregate.MEDIAN))
		assertValue(20.0, mixed.of(Aggregate.SUM))
		assertValue(SQRT_5, mixed.of(Aggregate.STDDEV))
		// COUNT and ACCURACY are the two that keep them: seven members, four answered.
		assertValue(7.0, mixed.of(Aggregate.COUNT))
		assertValue(400.0 / 7.0, mixed.of(Aggregate.ACCURACY))
	}

	@Test
	fun everyMemberTimedOut() {
		assertNull(allTimedOut.of(Aggregate.MIN))
		assertNull(allTimedOut.of(Aggregate.MAX))
		assertNull(allTimedOut.of(Aggregate.AVG))
		assertNull(allTimedOut.of(Aggregate.MEDIAN))
		// Zero would read as "answered instantly", so SUM is undefined here like the rest.
		assertNull(allTimedOut.of(Aggregate.SUM))
		assertNull(allTimedOut.of(Aggregate.STDDEV))
		assertValue(3.0, allTimedOut.of(Aggregate.COUNT))
		assertValue(0.0, allTimedOut.of(Aggregate.ACCURACY))
	}

	@Test
	fun oneAnsweredMember() {
		val single = Members(4.0 to false)
		assertValue(4.0, single.of(Aggregate.MIN))
		assertValue(4.0, single.of(Aggregate.MAX))
		assertValue(4.0, single.of(Aggregate.AVG))
		assertValue(4.0, single.of(Aggregate.MEDIAN))
		assertValue(4.0, single.of(Aggregate.SUM))
		// Population spread of one member is zero, not undefined and not a division by zero.
		assertValue(0.0, single.of(Aggregate.STDDEV))
		assertValue(1.0, single.of(Aggregate.COUNT))
		assertValue(100.0, single.of(Aggregate.ACCURACY))
	}

	@Test
	fun oneTimedOutMember() {
		val single = Members(4.0 to true)
		assertNull(single.of(Aggregate.MIN))
		assertNull(single.of(Aggregate.MAX))
		assertNull(single.of(Aggregate.AVG))
		assertNull(single.of(Aggregate.MEDIAN))
		assertNull(single.of(Aggregate.SUM))
		assertNull(single.of(Aggregate.STDDEV))
		assertValue(1.0, single.of(Aggregate.COUNT))
		assertValue(0.0, single.of(Aggregate.ACCURACY))
	}

	@Test
	fun noMembersAtAll() {
		// Unreachable through the table engine, since a row is always in its own partition,
		// but it must not throw.
		val empty = Members()
		assertNull(empty.of(Aggregate.MIN))
		assertNull(empty.of(Aggregate.MAX))
		assertNull(empty.of(Aggregate.AVG))
		assertNull(empty.of(Aggregate.MEDIAN))
		assertNull(empty.of(Aggregate.SUM))
		assertNull(empty.of(Aggregate.STDDEV))
		assertValue(0.0, empty.of(Aggregate.COUNT))
		// The share of nothing is undefined rather than zero.
		assertNull(empty.of(Aggregate.ACCURACY))
	}

	@Test
	fun accuracyIsAPercentageAndNotAFraction() {
		// CellFormat.PERCENT appends the sign without rescaling, so seven of eight is 87.5,
		// not 0.875, which would render as "0.9%".
		val sevenOfEight = Members(
			1.0 to false, 1.0 to false, 1.0 to false, 1.0 to false,
			1.0 to false, 1.0 to false, 1.0 to false, 1.0 to true
		)
		assertValue(87.5, sevenOfEight.of(Aggregate.ACCURACY))
	}

	@Test
	fun accuracyNeverReadsTheValues() {
		// Every value is non-numeric, which would leave nothing usable for MIN and friends.
		// ACCURACY still answers, and answers from the flags alone.
		val nonNumeric = Members(
			Double.NaN to false, Double.NaN to true, Double.NaN to false, Double.NaN to false
		)
		assertValue(75.0, nonNumeric.of(Aggregate.ACCURACY))
		assertValue(4.0, nonNumeric.of(Aggregate.COUNT))
		assertNull(nonNumeric.of(Aggregate.MIN))
		assertNull(nonNumeric.of(Aggregate.AVG))

		// The same flags with usable values must give the same accuracy.
		val numeric = Members(9.0 to false, 9.0 to true, 9.0 to false, 9.0 to false)
		assertValue(75.0, numeric.of(Aggregate.ACCURACY))
	}

	@Test
	fun stdDevIsPopulationAndNotSample() {
		// Mean 5, squared deviations summing to 32 over eight members: 2.0 for a population,
		// 2.138... for a sample.
		val spread = Members(
			2.0 to false, 4.0 to false, 4.0 to false, 4.0 to false,
			5.0 to false, 5.0 to false, 7.0 to false, 9.0 to false
		)
		assertValue(2.0, spread.of(Aggregate.STDDEV))
	}

	@Test
	fun medianOfAnOddCountIsTheMiddleValue() {
		// The mean is 34.33, so a median of 2.0 proves the two are not confused.
		val odd = Members(1.0 to false, 2.0 to false, 100.0 to false)
		assertValue(2.0, odd.of(Aggregate.MEDIAN))
	}

	@Test
	fun medianOfAnEvenCountAveragesTheTwoMiddleValues() {
		// Lower middle is 2.0 and upper middle is 4.0, so only their mean gives 3.0.
		val even = Members(1.0 to false, 2.0 to false, 4.0 to false, 100.0 to false)
		assertValue(3.0, even.of(Aggregate.MEDIAN))
	}

	@Test
	fun medianIgnoresTheOrderOfTheMembers() {
		val shuffled = Members(8.0 to false, 2.0 to false, 6.0 to false, 4.0 to false)
		assertValue(5.0, shuffled.of(Aggregate.MEDIAN))
	}

	@Test
	fun nonNumericMembersAreExcludedRatherThanPoisoningTheResult() {
		// A NaN reaching MIN would make the whole result NaN if it were simply carried along.
		val withNaN = Members(Double.NaN to false, 4.0 to false, 6.0 to false)
		assertValue(4.0, withNaN.of(Aggregate.MIN))
		assertValue(6.0, withNaN.of(Aggregate.MAX))
		assertValue(5.0, withNaN.of(Aggregate.AVG))
		assertValue(5.0, withNaN.of(Aggregate.MEDIAN))
		assertValue(10.0, withNaN.of(Aggregate.SUM))
		assertValue(1.0, withNaN.of(Aggregate.STDDEV))
		// The member is still a member, so it still counts.
		assertValue(3.0, withNaN.of(Aggregate.COUNT))
		assertValue(100.0, withNaN.of(Aggregate.ACCURACY))
	}

	@Test
	fun everyMemberNonNumericIsUndefinedForTheNumericFunctions() {
		val allNaN = Members(Double.NaN to false, Double.NaN to false)
		assertNull(allNaN.of(Aggregate.MIN))
		assertNull(allNaN.of(Aggregate.MAX))
		assertNull(allNaN.of(Aggregate.AVG))
		assertNull(allNaN.of(Aggregate.MEDIAN))
		assertNull(allNaN.of(Aggregate.SUM))
		assertNull(allNaN.of(Aggregate.STDDEV))
	}

	@Test
	fun mismatchedArrayLengthsAreRejected() {
		// COUNT is the strongest probe: it never reads values, so nothing would run off the
		// end of an array. Only the explicit length check can make this throw.
		val error = assertThrows(IllegalArgumentException::class.java) {
			Aggregates.compute(Aggregate.COUNT, doubleArrayOf(1.0, 2.0), booleanArrayOf(false, false, true))
		}
		assertTrue(error.message!!.contains("2 and 3"))
	}

	@Test
	fun onlyCountAndAccuracyTolerateANonNumericSource() {
		assertTrue(Aggregates.requiresNumericSource(Aggregate.MIN))
		assertTrue(Aggregates.requiresNumericSource(Aggregate.MAX))
		assertTrue(Aggregates.requiresNumericSource(Aggregate.AVG))
		assertTrue(Aggregates.requiresNumericSource(Aggregate.MEDIAN))
		assertTrue(Aggregates.requiresNumericSource(Aggregate.SUM))
		assertTrue(Aggregates.requiresNumericSource(Aggregate.STDDEV))
		assertFalse(Aggregates.requiresNumericSource(Aggregate.COUNT))
		assertFalse(Aggregates.requiresNumericSource(Aggregate.ACCURACY))
	}

	private fun assertValue(expected: Double, actual: Double?) {
		assertNotNull("expected $expected but the aggregate was undefined", actual)
		assertEquals(expected, actual!!, DELTA)
	}

	/** One member set, written as value-to-timeout pairs so the fixtures stay readable. */
	private class Members(vararg members: Pair<Double, Boolean>) {
		private val values = members.map { it.first }.toDoubleArray()
		private val timedOut = members.map { it.second }.toBooleanArray()

		fun of(fn: Aggregate): Double? = Aggregates.compute(fn, values, timedOut)
	}

	companion object {
		const val DELTA = 1e-9
		const val SQRT_5 = 2.23606797749979
	}
}
