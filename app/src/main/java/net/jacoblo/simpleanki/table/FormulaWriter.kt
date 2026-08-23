/*
 * A [ComputedSpec] spelled as the formula string a user reads and hand-edits.
 *
 * The struct is canonical and this is its mirror, regenerated on every save, so the two
 * cannot drift. Argument order is fixed - source, partition, then last: - so that the
 * round-trip property holds and a save rewrites the same bytes rather than churning the
 * file with a reordered argument list.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.Partition

object FormulaWriter {

	/** Round-trip property: parse(write(spec), known) == Ok(spec). */
	fun write(spec: ComputedSpec): String {
		val args = StringBuilder(spec.source)
		when (val partition = spec.partition) {
			is Partition.Group -> {
				args.append(", ").append(FormulaParser.ARG_GROUP).append(':').append(partition.by)
				// A limit bounds a group only, and 0 is the unlimited spelling; emitting it
				// for a bucket or a rolling window would produce a formula parse rejects.
				if (spec.limit > 0) {
					args.append(", ").append(FormulaParser.ARG_LAST).append(':').append(spec.limit)
				}
			}
			is Partition.Bucket ->
				args.append(", ").append(FormulaParser.ARG_BUCKET).append(':').append(partition.size)
			is Partition.Rolling ->
				args.append(", ").append(FormulaParser.ARG_ROLLING).append(':').append(partition.size)
		}
		// Aggregate.name is already the upper-case spelling the grammar emits.
		return "=" + spec.aggregate.name + "(" + args + ")"
	}
}
