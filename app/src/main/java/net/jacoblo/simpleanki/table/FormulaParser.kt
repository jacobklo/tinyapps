/*
 * The formula string of a computed column, read back into a [ComputedSpec].
 *
 * Deliberately not an expression language. One function call, no nesting, no arithmetic,
 * no cell references, no IF - so a formula is only ever a spelling of the struct that
 * [FormulaWriter] can spell back. If a ratio is wanted, the answer is a new named
 * aggregate, not an operator.
 *
 * Every failure is a returned [ParseResult.Err] carrying a message a user can act on. The
 * point of the hybrid design is that a hand-edit typo costs one "#ERR" column rather than
 * the whole view, and that only holds if nothing here throws.
 */
package net.jacoblo.simpleanki.table

import net.jacoblo.simpleanki.data.Aggregate
import net.jacoblo.simpleanki.data.ColumnType
import net.jacoblo.simpleanki.data.ComputedSpec
import net.jacoblo.simpleanki.data.Partition

/** What one formula turned out to be: the spec it names, or why it could not be read. */
sealed interface ParseResult {
	data class Ok(val spec: ComputedSpec) : ParseResult
	data class Err(val message: String) : ParseResult
}

object FormulaParser {

	const val MSG_NO_EQUALS = "formula must start with \"=\""
	const val MSG_MALFORMED = "malformed formula"
	const val MSG_STAR_NEEDS_COUNT = "only COUNT accepts \"*\" as a source"
	const val MSG_NO_PARTITION = "a partition argument is required: group:, bucket:, or rolling:"
	const val MSG_TWO_PARTITIONS = "only one partition argument is allowed"
	const val MSG_LAST_WITHOUT_GROUP = "last: is only valid with group:"
	const val MSG_REPEATED_LAST = "last: may only appear once"

	/** The source that means "every member", which only COUNT has any use for. */
	const val WILDCARD = "*"

	const val ARG_GROUP = "group"
	const val ARG_BUCKET = "bucket"
	const val ARG_ROLLING = "rolling"
	const val ARG_LAST = "last"

	/**
	 * Characters that can only be operators here, since no column id contains one.
	 *
	 * "-" is absent on purpose: it has to reach [positive] so that "bucket:-1" is reported
	 * as a bad size rather than as arithmetic. "*" is absent too - it is the wildcard
	 * source - and is checked separately once the source is known.
	 */
	private const val OPERATORS = "+/=<>%^!&|"

	/**
	 * @param knownColumns base column ids; a source or group key outside this set is an
	 *                     error, which is what prevents a computed column from referencing
	 *                     another computed column
	 */
	fun parse(formula: String, knownColumns: Set<String>): ParseResult {
		val text = formula.trim()
		if (!text.startsWith("=")) return ParseResult.Err(MSG_NO_EQUALS)
		val body = text.substring(1)
		if (body.any { it in OPERATORS }) return ParseResult.Err(MSG_MALFORMED)
		val open = body.indexOf('(')
		// Exactly one pair, closing at the very end. This is what rejects nesting, a second
		// call bolted on with an operator, and any trailing text after the call.
		if (open < 0 || !body.endsWith(")")) return ParseResult.Err(MSG_MALFORMED)
		if (body.count { it == '(' } != 1 || body.count { it == ')' } != 1) {
			return ParseResult.Err(MSG_MALFORMED)
		}
		val name = body.substring(0, open).trim()
		// Letters only, so "2*MIN" reads as malformed rather than as a misspelt function.
		if (name.isEmpty() || !name.all { it.isLetter() }) return ParseResult.Err(MSG_MALFORMED)
		val fn = Aggregate.entries.firstOrNull { it.name == name.uppercase() }
			?: return ParseResult.Err("unknown function \"$name\"")
		val parts = body.substring(open + 1, body.length - 1).split(",").map { it.trim() }
		// An empty part is an empty argument list, a doubled comma, or a trailing one.
		if (parts.any { it.isEmpty() }) return ParseResult.Err(MSG_MALFORMED)
		val source = parts[0]
		// A star is the wildcard source and nothing else; anywhere else it is multiplication.
		if (body.count { it == '*' } != (if (source == WILDCARD) 1 else 0)) {
			return ParseResult.Err(MSG_MALFORMED)
		}
		sourceError(fn, source, knownColumns)?.let { return ParseResult.Err(it) }
		return arguments(fn, source, parts.drop(1), knownColumns)
	}

	/** Why [source] cannot feed [fn], or null when it can. */
	private fun sourceError(fn: Aggregate, source: String, knownColumns: Set<String>): String? {
		if (source == WILDCARD) return if (fn == Aggregate.COUNT) null else MSG_STAR_NEEDS_COUNT
		if (source !in knownColumns) return unknownColumn(source)
		if (!Aggregates.requiresNumericSource(fn)) return null
		val type = TableEngine.baseColumn(source)?.type
		if (type == ColumnType.NUMBER) return null
		return "$fn requires a numeric column, but \"$source\" is ${typeWord(type)}"
	}

	/**
	 * The partition and limit the arguments after the source describe.
	 *
	 * Exactly one partition argument is required; "last:" is optional and only means
	 * anything alongside "group:", since a bucket and a rolling window are already bounded
	 * by their own size.
	 */
	private fun arguments(
		fn: Aggregate,
		source: String,
		args: List<String>,
		knownColumns: Set<String>
	): ParseResult {
		var partition: Partition? = null
		var limit = 0
		var sawLast = false
		for (arg in args) {
			val colon = arg.indexOf(':')
			if (colon < 0) return ParseResult.Err(MSG_MALFORMED)
			val key = arg.substring(0, colon).trim().lowercase()
			val value = arg.substring(colon + 1).trim()
			when (key) {
				ARG_GROUP -> {
					if (partition != null) return ParseResult.Err(MSG_TWO_PARTITIONS)
					// The same knownColumns gate as the source, and the reason a group can
					// never key off a computed column - MemberSelectors could not read one.
					if (value !in knownColumns) return ParseResult.Err(unknownColumn(value))
					partition = Partition.Group(value)
				}
				ARG_BUCKET -> {
					if (partition != null) return ParseResult.Err(MSG_TWO_PARTITIONS)
					val size = positive(value) ?: return ParseResult.Err(badSize(ARG_BUCKET))
					partition = Partition.Bucket(size)
				}
				ARG_ROLLING -> {
					if (partition != null) return ParseResult.Err(MSG_TWO_PARTITIONS)
					val size = positive(value) ?: return ParseResult.Err(badSize(ARG_ROLLING))
					partition = Partition.Rolling(size)
				}
				ARG_LAST -> {
					if (sawLast) return ParseResult.Err(MSG_REPEATED_LAST)
					sawLast = true
					// 0 is the struct's spelling of unlimited and FormulaWriter omits the
					// argument entirely for it, so "last:0" has no round-trippable meaning.
					limit = positive(value)
						?: return ParseResult.Err("last must be a positive integer")
				}
				else -> return ParseResult.Err(MSG_MALFORMED)
			}
		}
		if (partition == null) return ParseResult.Err(MSG_NO_PARTITION)
		if (sawLast && partition !is Partition.Group) {
			return ParseResult.Err(MSG_LAST_WITHOUT_GROUP)
		}
		return ParseResult.Ok(ComputedSpec(fn, source, partition, limit))
	}

	/**
	 * [value] as a size, or null when it is not a whole number of at least 1.
	 *
	 * A missing size is exactly this case - the empty string parses as nothing - which is
	 * what keeps "bucket:" from becoming the bucket(1) that renders each row's own value
	 * while looking like an aggregate.
	 */
	private fun positive(value: String): Int? = value.toIntOrNull()?.takeIf { it >= 1 }

	private fun badSize(key: String): String = "$key size must be a positive integer"

	private fun unknownColumn(name: String): String = "unknown column \"$name\""

	/** How a column's type is named in the non-numeric-source message. */
	private fun typeWord(type: ColumnType?): String = when (type) {
		ColumnType.NUMBER -> "numeric"
		ColumnType.TIME -> "a time"
		ColumnType.BOOL -> "a boolean"
		else -> "text"
	}
}
