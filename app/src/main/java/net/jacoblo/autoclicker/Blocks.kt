package net.jacoblo.autoclicker

/**
 * Blocks, in the two shapes they take.
 *
 * Repeat, Random, While and If are tree nodes when they run: each holds its
 * body. The editor works on one flat reorderable list instead, where a body is
 * whatever sits between a start marker and its end. [flatten] goes one way and
 * [buildHierarchy] the other, and they are the only two things that know both
 * shapes -- which is why the markers live here beside them rather than beside
 * the steps that actually run.
 */

/**
 * Opens a block.
 *
 * Which of the three parts of a block a marker is decides everything the editor
 * does with it: how far its row indents, whether the markers balance, and where
 * a body ends when the tree is rebuilt. The type says so, rather than a
 * predicate listing the same four classes at each site.
 */
sealed class BlockStart : EditorMarker()

/** Closes the block a [BlockStart] opened. */
sealed class BlockEnd : EditorMarker()

/** Neither opens nor closes: sits at the parent's level and keeps it open. */
sealed class BlockMid : EditorMarker()

data class LoopStartStep(
	val repeatCount: Int,
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockStart()

data class LoopEndStep(
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockEnd()

data class RandomSelectStartStep(
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockStart()

data class RandomSelectEndStep(
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockEnd()

data class IfStartStep(
	val condition: String,
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockStart()

data class ElseIfStep(
	val condition: String,
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockMid()

data class ElseStep(
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockMid()

data class IfEndStep(
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockEnd()

data class WhileStartStep(
	val condition: String,
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockStart()

data class WhileEndStep(
	override val delayBefore: Long = 0,
	override val name: String = "",
	override val enabled: Boolean = true
) : BlockEnd()

// ---------------------------------------------------------------------------
// Reading the flat list
// ---------------------------------------------------------------------------

/** Indent level of each row, so nested blocks can be drawn as nested. */
fun blockDepths(items: List<Step>): List<Int> {
	var depth = 0
	return items.map { item ->
		when (item) {
			is BlockStart -> depth++
			is BlockEnd -> {
				depth = (depth - 1).coerceAtLeast(0)
				depth
			}
			is BlockMid -> (depth - 1).coerceAtLeast(0)
			else -> depth
		}
	}
}

fun isBalanced(items: List<Step>): Boolean {
	var depth = 0
	items.forEach { item ->
		when (item) {
			is BlockStart -> depth++
			is BlockEnd -> {
				depth--
				if (depth < 0) return false
			}
			// An ElseIf or Else outside any If has nothing to attach to.
			is BlockMid -> if (depth == 0) return false
			else -> {}
		}
	}
	return depth == 0
}

/**
 * The rows the comment at [start] heads: everything after it up to the next
 * comment at its own indent, or up to wherever the block holding it closes.
 *
 * Empty when the comment is the last row, or when another comment follows it
 * immediately. This is [sectionEnd]'s rule drawn over the flat list -- the tree
 * says "beside it" with a sibling list, the flat list says it with an indent.
 */
fun commentSection(items: List<Step>, start: Int): IntRange {
	val depths = blockDepths(items)
	val depth = depths[start]
	var end = start + 1
	while (end < items.size) {
		// A shallower row is the block closing, which ends the section with it.
		if (depths[end] < depth) break
		if (depths[end] == depth && items[end] is CommentStep) break
		end++
	}
	return (start + 1) until end
}

/**
 * Which rows will not run, per row.
 *
 * A row is off when it says so itself, when a block holding it is off, or when
 * the comment heading its section is off. The editor needs all three as one
 * answer per row, because a step inside two switched-off things is not more off
 * than a step inside one.
 */
fun disabledRows(items: List<Step>): List<Boolean> {
	val depths = blockDepths(items)
	// One entry per open block, and one per indent for the comment sections.
	val blockOff = mutableListOf<Boolean>()
	val sectionOff = mutableListOf<Boolean>()

	return items.mapIndexed { index, item ->
		val depth = depths[index]
		// Coming back out of a block ends every section that was open inside it.
		while (sectionOff.size > depth + 1) sectionOff.removeAt(sectionOff.lastIndex)
		while (sectionOff.size < depth + 1) sectionOff.add(false)

		// Before the read below, because a comment ends the section above it as
		// well as starting its own -- otherwise the comment that brings a
		// switched-off section to a close is counted as the last row of it.
		if (item is CommentStep) sectionOff[depth] = !item.enabled

		// Read before the pop below, so the End of a switched-off block is drawn
		// switched off rather than as the one live row of a dead block.
		val off = !item.enabled || blockOff.any { it } || sectionOff.any { it }

		if (item is BlockEnd && blockOff.isNotEmpty()) blockOff.removeAt(blockOff.lastIndex)
		if (item is BlockStart) blockOff.add(off)

		off
	}
}

// ---------------------------------------------------------------------------
// Flattening / Unflattening
// ---------------------------------------------------------------------------

fun flatten(steps: List<RuntimeStep>): List<Step> {
	val flatList = mutableListOf<Step>()
	steps.forEach { step ->
		when (step) {
			is ForLoopStep -> {
				flatList.add(LoopStartStep(step.repeatCount, step.delayBefore, step.name))
				flatList.addAll(flatten(step.steps))
				flatList.add(LoopEndStep(0))
			}
			is RandomSelectStep -> {
				flatList.add(RandomSelectStartStep(step.delayBefore, step.name))
				flatList.addAll(flatten(step.steps))
				flatList.add(RandomSelectEndStep(0))
			}
			is WhileStep -> {
				flatList.add(WhileStartStep(step.condition, step.delayBefore, step.name))
				flatList.addAll(flatten(step.steps))
				flatList.add(WhileEndStep(0))
			}
			is IfStep -> {
				step.branches.forEachIndexed { index, branch ->
					if (index == 0) {
						flatList.add(IfStartStep(branch.condition, step.delayBefore, step.name))
					} else {
						flatList.add(ElseIfStep(branch.condition))
					}
					flatList.addAll(flatten(branch.steps))
				}
				if (step.elseBranch.isNotEmpty()) {
					flatList.add(ElseStep())
					flatList.addAll(flatten(step.elseBranch))
				}
				flatList.add(IfEndStep(0))
			}
			else -> flatList.add(step)
		}
	}
	return flatList
}

fun buildHierarchy(flatSteps: List<Step>): List<RuntimeStep> =
	readSequence(flatSteps, 0) { false }.children

private class ParsedSequence(val children: List<RuntimeStep>, val terminator: Step?, val next: Int)

/**
 * Reads steps until [isTerminator] matches, recursing into any block it
 * meets. Returns which terminator stopped it, which is what lets an If chain
 * tell ElseIf from Else from End.
 */
private fun readSequence(
	flat: List<Step>,
	start: Int,
	isTerminator: (Step) -> Boolean
): ParsedSequence {
	val children = mutableListOf<RuntimeStep>()
	var i = start
	while (i < flat.size) {
		val item = flat[i]
		if (isTerminator(item)) return ParsedSequence(children, item, i + 1)
		when (item) {
			is BlockStart -> {
				val (node, next) = readBlock(flat, item, i + 1)
				children.add(node)
				i = next
			}
			// An End or Else with no matching Start cannot be represented; drop
			// it. The editor warns about this before saving.
			is BlockEnd, is BlockMid -> i++
			is RuntimeStep -> {
				children.add(item)
				i++
			}
		}
	}
	return ParsedSequence(children, null, i)
}

/**
 * A Repeat and a Random block accept each other's End on purpose: isBalanced
 * counts every End alike, so a parser that was stricter than the check the
 * editor shows would reject a list the editor called balanced.
 */
private fun readBlock(flat: List<Step>, opener: BlockStart, start: Int): Pair<RuntimeStep, Int> =
	when (opener) {
		is LoopStartStep -> {
			val body = readSequence(flat, start) { it is LoopEndStep || it is RandomSelectEndStep }
			ForLoopStep(opener.repeatCount, body.children, opener.delayBefore, opener.name) to body.next
		}
		is RandomSelectStartStep -> {
			val body = readSequence(flat, start) { it is LoopEndStep || it is RandomSelectEndStep }
			RandomSelectStep(body.children, opener.delayBefore, opener.name) to body.next
		}
		is WhileStartStep -> {
			val body = readSequence(flat, start) { it is WhileEndStep }
			WhileStep(opener.condition, body.children, opener.delayBefore, opener.name) to body.next
		}
		is IfStartStep -> readIf(flat, opener, start)
	}

private fun readIf(flat: List<Step>, opener: IfStartStep, start: Int): Pair<RuntimeStep, Int> {
	val branches = mutableListOf<ConditionBranch>()
	var condition = opener.condition
	var index = start

	while (true) {
		val body = readSequence(flat, index) {
			it is ElseIfStep || it is ElseStep || it is IfEndStep
		}
		index = body.next
		when (val terminator = body.terminator) {
			is ElseIfStep -> {
				branches.add(ConditionBranch(condition, body.children))
				condition = terminator.condition
			}
			is ElseStep -> {
				branches.add(ConditionBranch(condition, body.children))
				val elseBody = readSequence(flat, index) { it is IfEndStep }
				return IfStep(branches, elseBody.children, opener.delayBefore, opener.name) to elseBody.next
			}
			// IfEnd, or the list ran out
			else -> {
				branches.add(ConditionBranch(condition, body.children))
				return IfStep(branches, emptyList(), opener.delayBefore, opener.name) to index
			}
		}
	}
}
