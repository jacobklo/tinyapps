/*
 * Which rows form each row's member set, for the three partition modes.
 *
 * Pure index arithmetic over a row list that is already filtered and sorted: no aggregate
 * math, no formula parsing and no Android imports. Partitions are returned rather than one
 * member list per row, so rows that share a partition are aggregated once and the answer
 * broadcast to them; a per-row implementation of group or bucket would be quadratic.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.Partition

/**
 * Assignment of rows to partitions.
 *
 * Every partition holds at least one row, every partition has at least one row mapped to
 * it, and every id in [partitionOfRow] indexes [membersOfPartition]. Aggregates.compute is
 * undefined for an empty member set and the table engine leans on that case being
 * unreachable, so no builder in [MemberSelectors] may emit an empty partition. Members are
 * listed in ascending row order.
 *
 * equals and hashCode are overridden because the generated ones compare the arrays by
 * reference, which would leave two structurally identical results unequal.
 *
 * @param partitionOfRow     row index -> partition id, for every row including one trimmed
 *                           out of its own partition by a group limit
 * @param membersOfPartition partition id -> the row indices its aggregate reads
 */
data class PartitionResult(
	val partitionOfRow: IntArray,
	val membersOfPartition: List<IntArray>
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is PartitionResult) return false
		if (!partitionOfRow.contentEquals(other.partitionOfRow)) return false
		if (membersOfPartition.size != other.membersOfPartition.size) return false
		return membersOfPartition.indices.all {
			membersOfPartition[it].contentEquals(other.membersOfPartition[it])
		}
	}

	override fun hashCode(): Int {
		var result = partitionOfRow.contentHashCode()
		for (members in membersOfPartition) result = 31 * result + members.contentHashCode()
		return result
	}
}

fun interface MemberSelector {
	/** @param rows already filtered and sorted; position means sort position. */
	fun partition(rows: List<HistoryEntry>): PartitionResult
}

object MemberSelectors {

	/**
	 * One partition per distinct key.
	 *
	 * A limit bounds what the aggregate sees, not who sees it: a row trimmed out of its own
	 * partition still maps to that partition, and so still displays its group's figure.
	 *
	 * @param keyOf reads the partition key from a row, e.g. its Question
	 * @param limit keeps only the newest [limit] members by timestamp, ties broken by the
	 *              lower row index; 0 or less keeps every member
	 */
	fun group(keyOf: (HistoryEntry) -> String, limit: Int): MemberSelector =
		MemberSelector { rows ->
			val idOfKey = HashMap<String, Int>()
			val partitionOfRow = IntArray(rows.size)
			val members = ArrayList<MutableList<Int>>()
			for (i in rows.indices) {
				val id = idOfKey.getOrPut(keyOf(rows[i])) {
					members.add(ArrayList())
					members.size - 1
				}
				partitionOfRow[i] = id
				members[id].add(i)
			}
			PartitionResult(partitionOfRow, members.map { newest(it, rows, limit) })
		}

	/**
	 * Fixed blocks of [size] by sort position, so a partition id is position / size.
	 *
	 * The final block may be short. A size past the row count puts every row in block zero
	 * and so yields a true grand total, which is how an overall figure is spelled. [rolling]
	 * with the same size is deliberately not the same thing.
	 */
	fun bucket(size: Int): MemberSelector =
		MemberSelector { rows ->
			val width = atLeastOne(size)
			// (n - 1) / width + 1 rather than n / width, which would add an empty trailing
			// block whenever the row count divides exactly.
			val count = if (rows.isEmpty()) 0 else (rows.size - 1) / width + 1
			val members = List(count) { id ->
				val start = id * width
				val length = if (rows.size - start < width) rows.size - start else width
				IntArray(length) { start + it }
			}
			PartitionResult(IntArray(rows.size) { it / width }, members)
		}

	/**
	 * Trailing window of [size] by sort position, clamped at the start of the table.
	 *
	 * Every row has its own window, so there are as many partitions as rows. A window near
	 * the top is computed from however many rows exist rather than left blank, which is also
	 * why a size past the row count gives a running cumulative rather than a grand total.
	 */
	fun rolling(size: Int): MemberSelector =
		MemberSelector { rows ->
			val width = atLeastOne(size)
			val members = List(rows.size) { i ->
				val start = if (i - width + 1 > 0) i - width + 1 else 0
				IntArray(i - start + 1) { start + it }
			}
			PartitionResult(IntArray(rows.size) { it }, members)
		}

	/**
	 * The selector a computed column's [partition] calls for.
	 *
	 * [limit] applies to a group only; a bucket and a rolling window are already bounded by
	 * their own size.
	 *
	 * @param keyOf reads any column of a row, so one lambda serves whichever column the
	 *              group names
	 */
	fun forPartition(
		partition: Partition,
		limit: Int,
		keyOf: (HistoryEntry, String) -> String
	): MemberSelector = when (partition) {
		is Partition.Group -> group({ keyOf(it, partition.by) }, limit)
		is Partition.Bucket -> bucket(partition.size)
		is Partition.Rolling -> rolling(partition.size)
	}

	/**
	 * The [limit] newest members by timestamp, returned in ascending row order.
	 *
	 * A limit below 1 keeps every member: 0 is the documented unlimited spelling, and a
	 * negative one is nonsense that must not be allowed to trim a partition to nothing.
	 */
	private fun newest(members: List<Int>, rows: List<HistoryEntry>, limit: Int): IntArray {
		if (limit < 1 || members.size <= limit) return members.toIntArray()
		return members
			.sortedWith(compareByDescending<Int> { rows[it].timestamp }.thenBy { it })
			.subList(0, limit)
			.sorted()
			.toIntArray()
	}

	/** Guards the block division and the window arithmetic against a size of zero or less. */
	private fun atLeastOne(size: Int): Int = if (size < 1) 1 else size
}
