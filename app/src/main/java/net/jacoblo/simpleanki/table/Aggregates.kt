/*
 * The eight aggregate functions, as pure math over arrays.
 *
 * Values in, one number or null out: no partitioning, no formula parsing, no table and no
 * Android imports. Deciding which rows are members of a set belongs to the caller.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.Aggregate
import kotlin.math.sqrt

object Aggregates {

	/**
	 * Computes one aggregate over a member set.
	 *
	 * MIN, MAX, AVG, MEDIAN, SUM and STDDEV read only the members that did not time out,
	 * because a timed-out attempt has no answer time worth averaging - its timeTaken is
	 * merely the interval that elapsed. COUNT and ACCURACY read every member, since a
	 * timeout is exactly what ACCURACY is counting.
	 *
	 * A member whose value is NaN marks a non-numeric source cell. Such a member is dropped
	 * alongside the timeouts rather than poisoning the result, so nothing here ever returns
	 * NaN; a set left with no usable member is undefined and returns null. Callers should
	 * still not reach here with NaN when [requiresNumericSource] is true - Task 12 rejects
	 * those formulas at parse time - but exclusion is the safe landing if one slips through.
	 *
	 * @param values   source value per member; NaN where the source is non-numeric
	 * @param timedOut whether each member's row timed out; same length as [values]
	 * @return the result, or null when undefined, which renders as "-"
	 */
	fun compute(fn: Aggregate, values: DoubleArray, timedOut: BooleanArray): Double? {
		require(values.size == timedOut.size) {
			"values and timedOut must be the same length, got ${values.size} and ${timedOut.size}"
		}
		// The two that keep the timed-out members answer straight from the flags. ACCURACY
		// deliberately never touches values at all, which is what makes its source column
		// syntactically required but semantically free to be any column.
		if (fn == Aggregate.COUNT) return timedOut.size.toDouble()
		if (fn == Aggregate.ACCURACY) return accuracy(timedOut)

		val answered = answered(values, timedOut)
		if (answered.isEmpty()) return null
		return when (fn) {
			Aggregate.MIN -> answered.min()
			Aggregate.MAX -> answered.max()
			Aggregate.AVG -> answered.average()
			Aggregate.MEDIAN -> median(answered)
			Aggregate.SUM -> answered.sum()
			Aggregate.STDDEV -> populationStdDev(answered)
			Aggregate.COUNT, Aggregate.ACCURACY -> error("$fn is answered above")
		}
	}

	/** True when [fn] requires a numeric source column. */
	fun requiresNumericSource(fn: Aggregate): Boolean =
		fn != Aggregate.COUNT && fn != Aggregate.ACCURACY

	/**
	 * Share of members that were answered, as a percentage from 0 to 100.
	 *
	 * Not a 0-to-1 fraction, however much normalising it looks like the tidy choice:
	 * CellFormat.PERCENT appends a percent sign without rescaling, so seven answers out of
	 * eight must arrive here as 87.5 to render as "87.5%".
	 *
	 * Null for an empty set, since the share of nothing is undefined rather than zero. The
	 * table engine cannot produce that case - a row is always a member of its own partition.
	 */
	private fun accuracy(timedOut: BooleanArray): Double? {
		if (timedOut.isEmpty()) return null
		return timedOut.count { !it }.toDouble() / timedOut.size * 100.0
	}

	/** Values of the members that neither timed out nor carry a non-numeric NaN. */
	private fun answered(values: DoubleArray, timedOut: BooleanArray): DoubleArray =
		values.filterIndexed { i, v -> !timedOut[i] && !v.isNaN() }.toDoubleArray()

	/** Middle value, or the mean of the two middle values when the count is even. */
	private fun median(values: DoubleArray): Double {
		val sorted = values.sortedArray()
		val mid = sorted.size / 2
		return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
	}

	/**
	 * Population standard deviation, dividing by n rather than n - 1.
	 *
	 * A member set is the whole population of attempts it describes, not a sample drawn
	 * from a larger one, so a single member has a spread of zero rather than no spread.
	 */
	private fun populationStdDev(values: DoubleArray): Double {
		val mean = values.average()
		var squares = 0.0
		for (v in values) squares += (v - mean) * (v - mean)
		return sqrt(squares / values.size)
	}
}
