package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.Partition
import net.jacoblo.simpleanki.table.MemberSelector
import net.jacoblo.simpleanki.table.MemberSelectors
import net.jacoblo.simpleanki.table.PartitionResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the three partition modes and the invariant they exist to uphold.
 *
 * Assertions compare PartitionResult field by field rather than whole, so a failure names
 * the array that differs instead of printing an identity hash. The one test that compares
 * whole results is the one pinning the equality contract itself.
 *
 * Fixtures deliberately put timestamp order at odds with sort position wherever a trim is
 * under test, so an implementation that trimmed by position would be caught.
 */
class MemberSelectorTest {

	private val byQuestion: (HistoryEntry) -> String = { it.question }

	private fun row(question: String, timestamp: Long) =
		HistoryEntry(question, "a", 1.0f, timestamp, false)

	private fun rows(vararg spec: Pair<String, Long>) = spec.map { row(it.first, it.second) }

	/** [n] rows with a distinct key each, so only position can partition them. */
	private fun positions(n: Int) = (0 until n).map { row("Q$it", it.toLong()) }

	private fun assertPartitions(
		expectedPartitionOfRow: IntArray,
		expectedMembers: List<IntArray>,
		actual: PartitionResult
	) {
		assertArrayEquals("partitionOfRow", expectedPartitionOfRow, actual.partitionOfRow)
		assertEquals("partition count", expectedMembers.size, actual.membersOfPartition.size)
		for (id in expectedMembers.indices) {
			assertArrayEquals("members of $id", expectedMembers[id], actual.membersOfPartition[id])
		}
	}

	// ---------------------------------------------------------------------------
	// Degenerate inputs
	// ---------------------------------------------------------------------------

	@Test
	fun emptyInputProducesNoPartitionsAtAll() {
		val none = emptyList<HistoryEntry>()
		for (selector in everyMode()) {
			assertPartitions(IntArray(0), emptyList(), selector.partition(none))
		}
	}

	@Test
	fun singleRowIsAMemberOfItsOwnPartitionInEveryMode() {
		val one = rows("Q1" to 100L)
		for (selector in everyMode()) {
			assertPartitions(intArrayOf(0), listOf(intArrayOf(0)), selector.partition(one))
		}
	}

	// ---------------------------------------------------------------------------
	// bucket
	// ---------------------------------------------------------------------------

	@Test
	fun bucketWhereTheRowCountIsAnExactMultipleOfSize() {
		// Six rows over three: no short block, and no empty trailing block either.
		assertPartitions(
			intArrayOf(0, 0, 0, 1, 1, 1),
			listOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5)),
			MemberSelectors.bucket(3).partition(positions(6))
		)
	}

	@Test
	fun bucketWhereTheRowCountIsNotAMultipleOfSize() {
		// The seventh row is a block of one rather than being dropped or padded.
		assertPartitions(
			intArrayOf(0, 0, 0, 1, 1, 1, 2),
			listOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6)),
			MemberSelectors.bucket(3).partition(positions(7))
		)
	}

	@Test
	fun bucketOfOnePutsEveryRowInItsOwnBlock() {
		assertPartitions(
			intArrayOf(0, 1, 2),
			listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2)),
			MemberSelectors.bucket(1).partition(positions(3))
		)
	}

	@Test
	fun bucketSizeOfZeroOrLessIsClampedToOne() {
		val three = positions(3)
		val expectedRows = intArrayOf(0, 1, 2)
		val expectedMembers = listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2))
		for (size in listOf(0, -1, -7, Int.MIN_VALUE)) {
			assertPartitions(expectedRows, expectedMembers, MemberSelectors.bucket(size).partition(three))
		}
	}

	@Test
	fun bucketPastTheRowCountIsAGrandTotalWhileRollingIsARunningCumulative() {
		// The documented asymmetry: every row sees the whole table under bucket, but only
		// the rows above it under rolling.
		val four = positions(4)
		assertPartitions(
			intArrayOf(0, 0, 0, 0),
			listOf(intArrayOf(0, 1, 2, 3)),
			MemberSelectors.bucket(999999).partition(four)
		)
		assertPartitions(
			intArrayOf(0, 1, 2, 3),
			listOf(intArrayOf(0), intArrayOf(0, 1), intArrayOf(0, 1, 2), intArrayOf(0, 1, 2, 3)),
			MemberSelectors.rolling(999999).partition(four)
		)
	}

	// ---------------------------------------------------------------------------
	// rolling
	// ---------------------------------------------------------------------------

	@Test
	fun rollingClampsAtTheStartAndComputesPartialWindows() {
		// Positions 0 and 1 are the partial windows, position 2 is the first full one at
		// size - 1 into the table, and 3 and 4 are the steady state.
		assertPartitions(
			intArrayOf(0, 1, 2, 3, 4),
			listOf(
				intArrayOf(0),
				intArrayOf(0, 1),
				intArrayOf(0, 1, 2),
				intArrayOf(1, 2, 3),
				intArrayOf(2, 3, 4)
			),
			MemberSelectors.rolling(3).partition(positions(5))
		)
	}

	@Test
	fun rollingSizeOfZeroOrLessIsClampedToOne() {
		val three = positions(3)
		val expectedRows = intArrayOf(0, 1, 2)
		val expectedMembers = listOf(intArrayOf(0), intArrayOf(1), intArrayOf(2))
		for (size in listOf(0, -1, -7, Int.MIN_VALUE)) {
			assertPartitions(expectedRows, expectedMembers, MemberSelectors.rolling(size).partition(three))
		}
	}

	// ---------------------------------------------------------------------------
	// group
	// ---------------------------------------------------------------------------

	@Test
	fun groupProducesOnePartitionPerDistinctKey() {
		val mixed = rows("Q1" to 10L, "Q2" to 20L, "Q1" to 30L, "Q3" to 40L, "Q2" to 50L)
		// Partition ids follow first appearance, so Q1 is 0, Q2 is 1 and Q3 is 2.
		assertPartitions(
			intArrayOf(0, 1, 0, 2, 1),
			listOf(intArrayOf(0, 2), intArrayOf(1, 4), intArrayOf(3)),
			MemberSelectors.group(byQuestion, 0).partition(mixed)
		)
	}

	@Test
	fun groupTrimsToTheNewestMembersByTimestampAndNotBySortPosition() {
		// One key, timestamps 10, 30, 50, 20 down the table. The newest two are positions 2
		// and 1; the first two would be 0 and 1, the last two 2 and 3.
		val jumbled = rows("Q1" to 10L, "Q1" to 30L, "Q1" to 50L, "Q1" to 20L)
		assertPartitions(
			intArrayOf(0, 0, 0, 0),
			listOf(intArrayOf(1, 2)),
			MemberSelectors.group(byQuestion, 2).partition(jumbled)
		)
	}

	@Test
	fun aRowTrimmedOutOfItsGroupStillMapsToThatGroup() {
		// Two keys, so that the trimmed rows belong to partition 1 rather than 0. A single
		// key could not tell "kept its own id" apart from "fell back to the first id".
		// Q1 holds positions 0 and 3 and is not trimmed; Q2 holds 1, 2, 4 and 5, of which
		// only the newest two, 2 and 4, survive the limit.
		val two = rows(
			"Q1" to 10L, "Q2" to 15L, "Q2" to 50L, "Q1" to 30L, "Q2" to 40L, "Q2" to 20L
		)
		val result = MemberSelectors.group(byQuestion, 2).partition(two)
		assertPartitions(
			intArrayOf(0, 1, 1, 0, 1, 1),
			listOf(intArrayOf(0, 3), intArrayOf(2, 4)),
			result
		)
		// The two rows the limit dropped still display Q2's figure rather than a blank or
		// some other group's.
		assertEquals(1, result.partitionOfRow[1])
		assertEquals(1, result.partitionOfRow[5])
	}

	@Test
	fun groupTrimBreaksATimestampTieOnTheLowerRowIndex() {
		// Two attempts land in the same millisecond straddling the cut. Only the earlier
		// position may survive, so members are 0 and 1 rather than 0 and 2.
		val tied = rows("Q1" to 30L, "Q1" to 20L, "Q1" to 20L, "Q1" to 10L)
		assertPartitions(
			intArrayOf(0, 0, 0, 0),
			listOf(intArrayOf(0, 1)),
			MemberSelectors.group(byQuestion, 2).partition(tied)
		)
	}

	@Test
	fun groupWithALimitOfZeroKeepsEveryMember() {
		val four = rows("Q1" to 10L, "Q1" to 30L, "Q1" to 50L, "Q1" to 20L)
		assertPartitions(
			intArrayOf(0, 0, 0, 0),
			listOf(intArrayOf(0, 1, 2, 3)),
			MemberSelectors.group(byQuestion, 0).partition(four)
		)
	}

	@Test
	fun groupWithANegativeLimitKeepsEveryMemberRatherThanTrimmingToNothing() {
		val four = rows("Q1" to 10L, "Q1" to 30L, "Q1" to 50L, "Q1" to 20L)
		for (limit in listOf(-1, -10, Int.MIN_VALUE)) {
			assertPartitions(
				intArrayOf(0, 0, 0, 0),
				listOf(intArrayOf(0, 1, 2, 3)),
				MemberSelectors.group(byQuestion, limit).partition(four)
			)
		}
	}

	@Test
	fun groupWithALimitOfOneKeepsExactlyTheNewestMember() {
		val four = rows("Q1" to 10L, "Q1" to 30L, "Q1" to 50L, "Q1" to 20L)
		assertPartitions(
			intArrayOf(0, 0, 0, 0),
			listOf(intArrayOf(2)),
			MemberSelectors.group(byQuestion, 1).partition(four)
		)
	}

	@Test
	fun groupWhereTheLimitExceedsTheMemberCountKeepsEveryMember() {
		val mixed = rows("Q1" to 10L, "Q2" to 20L, "Q1" to 30L)
		assertPartitions(
			intArrayOf(0, 1, 0),
			listOf(intArrayOf(0, 2), intArrayOf(1)),
			MemberSelectors.group(byQuestion, 100).partition(mixed)
		)
	}

	// ---------------------------------------------------------------------------
	// The non-empty-partition invariant
	// ---------------------------------------------------------------------------

	@Test
	fun noPartitionIsEverEmptyForAnyModeSizeOrLimit() {
		// Seven rows, three keys of uneven size, with repeated timestamps so that the trim
		// tiebreak is exercised too.
		val fixture = rows(
			"Q1" to 50L, "Q2" to 40L, "Q1" to 40L, "Q3" to 30L,
			"Q2" to 30L, "Q1" to 20L, "Q1" to 20L
		)
		val selectors = ArrayList<MemberSelector>()
		for (limit in listOf(Int.MIN_VALUE, -3, -1, 0, 1, 2, 6, 7, 8, 999999)) {
			selectors.add(MemberSelectors.group(byQuestion, limit))
		}
		for (size in listOf(Int.MIN_VALUE, -3, -1, 0, 1, 2, 3, 6, 7, 8, 999999, Int.MAX_VALUE)) {
			selectors.add(MemberSelectors.bucket(size))
			selectors.add(MemberSelectors.rolling(size))
		}
		for (selector in selectors) {
			val result = selector.partition(fixture)
			assertEquals("every row is mapped", fixture.size, result.partitionOfRow.size)
			assertTrue("a non-empty table has partitions", result.membersOfPartition.isNotEmpty())
			for (members in result.membersOfPartition) {
				assertTrue("empty partition", members.isNotEmpty())
			}
			val occupied = BooleanArray(result.membersOfPartition.size)
			for (id in result.partitionOfRow) {
				assertTrue("partition id $id out of range", id >= 0 && id < occupied.size)
				occupied[id] = true
			}
			for (id in occupied.indices) {
				assertTrue("partition $id has no rows", occupied[id])
			}
		}
	}

	// ---------------------------------------------------------------------------
	// forPartition
	// ---------------------------------------------------------------------------

	@Test
	fun forPartitionBindsTheGroupColumnIntoTheKeyLambda() {
		val fixture = rows("Q1" to 10L, "Q2" to 20L, "Q1" to 30L)
		val seen = ArrayList<String>()
		val keyOf: (HistoryEntry, String) -> String = { entry, column ->
			seen.add(column)
			if (column == "Question") entry.question else entry.answer
		}
		assertPartitions(
			intArrayOf(0, 1, 0),
			listOf(intArrayOf(0, 2), intArrayOf(1)),
			MemberSelectors.forPartition(Partition.Group("Question"), 0, keyOf).partition(fixture)
		)
		assertEquals(listOf("Question", "Question", "Question"), seen)

		// Every row shares one answer, so naming a different column must collapse the table
		// into a single partition. That is what proves the column is really bound.
		assertPartitions(
			intArrayOf(0, 0, 0),
			listOf(intArrayOf(0, 1, 2)),
			MemberSelectors.forPartition(Partition.Group("Answer"), 0, keyOf).partition(fixture)
		)
	}

	@Test
	fun forPartitionIgnoresTheLimitForBucketAndRolling() {
		// A limit of 1 would cut every partition to a single member if it were applied.
		val four = positions(4)
		val keyOf: (HistoryEntry, String) -> String = { entry, _ -> entry.question }
		assertPartitions(
			intArrayOf(0, 0, 1, 1),
			listOf(intArrayOf(0, 1), intArrayOf(2, 3)),
			MemberSelectors.forPartition(Partition.Bucket(2), 1, keyOf).partition(four)
		)
		assertPartitions(
			intArrayOf(0, 1, 2, 3),
			listOf(intArrayOf(0), intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3)),
			MemberSelectors.forPartition(Partition.Rolling(2), 1, keyOf).partition(four)
		)
	}

	// ---------------------------------------------------------------------------
	// PartitionResult
	// ---------------------------------------------------------------------------

	@Test
	fun partitionResultComparesByContentRatherThanByArrayIdentity() {
		val result = PartitionResult(intArrayOf(0, 0, 1), listOf(intArrayOf(0, 1), intArrayOf(2)))
		val same = PartitionResult(intArrayOf(0, 0, 1), listOf(intArrayOf(0, 1), intArrayOf(2)))
		assertEquals(result, same)
		assertEquals(result.hashCode(), same.hashCode())
		assertNotEquals(result, PartitionResult(intArrayOf(0, 1, 1), listOf(intArrayOf(0), intArrayOf(1, 2))))
		assertNotEquals(result, PartitionResult(intArrayOf(0, 0, 1), listOf(intArrayOf(0, 1))))
		assertNotEquals(result, PartitionResult(intArrayOf(0, 0, 1), listOf(intArrayOf(0), intArrayOf(2))))
	}

	private fun everyMode(): List<MemberSelector> = listOf(
		MemberSelectors.group(byQuestion, 10),
		MemberSelectors.group(byQuestion, 0),
		MemberSelectors.bucket(3),
		MemberSelectors.rolling(3)
	)
}
