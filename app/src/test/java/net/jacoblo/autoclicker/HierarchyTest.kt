package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun click(name: String) =
    ClickStep(x = 0.5f, y = 0.5f, duration = 10, delayBefore = 0, name = name)

private fun names(events: List<Step>) = events.map { it.name }

/**
 * The editor edits a flat list and rebuilds the tree on save, so flatten and
 * buildHierarchy must round-trip. If/ElseIf/Else is the subtle case: the flat
 * form has no nesting of its own and the branches are only implied by which
 * marker terminated each run of rows.
 */
class HierarchyTest {

    @Test
    fun plainSequenceRoundTrips() {
        val tree = listOf(click("a"), click("b"))
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun repeatBlockRoundTrips() {
        val tree = listOf(
            ForLoopStep(3, listOf(click("inner")), delayBefore = 0, name = "loop")
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun whileBlockRoundTrips() {
        val tree = listOf(
            WhileStep("count < 3", listOf(click("inner")), delayBefore = 0, name = "w")
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun ifElseIfElseRoundTrips() {
        val tree = listOf(
            IfStep(
                branches = listOf(
                    ConditionBranch("a == 1", listOf(click("first"))),
                    ConditionBranch("a == 2", listOf(click("second")))
                ),
                elseBranch = listOf(click("otherwise")),
                delayBefore = 0,
                name = "branchy"
            )
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun ifWithoutElseRoundTrips() {
        val tree = listOf(
            IfStep(
                branches = listOf(ConditionBranch("x > 0", listOf(click("only")))),
                elseBranch = emptyList(),
                delayBefore = 0,
                name = ""
            )
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun nestedConditionalsInsideLoopsRoundTrip() {
        val tree = listOf(
            WhileStep(
                condition = "running == 1",
                steps = listOf(
                    ForLoopStep(
                        repeatCount = 2,
                        steps = listOf(
                            IfStep(
                                branches = listOf(
                                    ConditionBranch("hp < 10", listOf(click("heal")))
                                ),
                                elseBranch = listOf(
                                    IfStep(
                                        branches = listOf(
                                            ConditionBranch("mp > 5", listOf(click("cast")))
                                        ),
                                        elseBranch = listOf(click("attack")),
                                        delayBefore = 0,
                                        name = ""
                                    )
                                ),
                                delayBefore = 0,
                                name = ""
                            )
                        ),
                        delayBefore = 0,
                        name = ""
                    )
                ),
                delayBefore = 0,
                name = ""
            )
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun flatteningAnIfProducesMarkersInSourceOrder() {
        val flat = flatten(
            listOf(
                IfStep(
                    branches = listOf(
                        ConditionBranch("c1", listOf(click("a"))),
                        ConditionBranch("c2", listOf(click("b")))
                    ),
                    elseBranch = listOf(click("c")),
                    delayBefore = 0,
                    name = ""
                )
            )
        )
        assertTrue(flat[0] is IfStartStep)
        assertEquals("a", flat[1].name)
        assertTrue(flat[2] is ElseIfStep)
        assertEquals("b", flat[3].name)
        assertTrue(flat[4] is ElseStep)
        assertEquals("c", flat[5].name)
        assertTrue(flat[6] is IfEndStep)
    }

    @Test
    fun strayEndMarkerIsDroppedRatherThanCorruptingTheTree() {
        val rebuilt = buildHierarchy(listOf(click("a"), IfEndStep(), click("b")))
        assertEquals(listOf("a", "b"), names(rebuilt))
    }

    @Test
    fun balanceDetectsMissingAndOrphanedMarkers() {
        assertTrue(isBalanced(listOf(IfStartStep("c"), IfEndStep())))
        assertTrue(
            isBalanced(
                listOf(IfStartStep("c"), ElseIfStep("d"), ElseStep(), IfEndStep())
            )
        )
        assertFalse(isBalanced(listOf(IfStartStep("c"))))
        assertFalse(isBalanced(listOf(IfEndStep())))
        // An Else with no enclosing If has nothing to attach to.
        assertFalse(isBalanced(listOf(ElseStep())))
    }

    @Test
    fun depthsIndentBodiesButNotTheBranchMarkers() {
        val flat = listOf(
            IfStartStep("c"),
            click("a"),
            ElseStep(),
            click("b"),
            IfEndStep()
        )
        assertEquals(listOf(0, 1, 0, 1, 0), blockDepths(flat))
    }

    /** A comment heads the rows below it as far as the next comment beside it. */
    @Test
    fun aCommentSectionStopsAtTheNextComment() {
        val flat = listOf(
            CommentStep("first"),
            click("a"),
            click("b"),
            CommentStep("second"),
            click("c")
        )

        assertEquals(listOf(1, 2), commentSection(flat, 0).toList())
        assertEquals(listOf(4), commentSection(flat, 3).toList())
    }

    /** The block a comment sits in ends its section, comment or no comment. */
    @Test
    fun aCommentSectionEndsWhereItsBlockDoes() {
        val flat = listOf(
            WhileStartStep("1 == 1"),
            CommentStep("inside"),
            click("a"),
            WhileEndStep(),
            click("after the loop")
        )

        assertEquals(listOf(2), commentSection(flat, 1).toList())
    }

    /**
     * The two ways a row goes dead without being switched off itself, and the
     * row that ends a dead section rather than belonging to it.
     */
    @Test
    fun switchingSomethingOffSwitchesOffWhatIsInsideIt() {
        val flat = listOf(
            WhileStartStep("1 == 1", enabled = false),
            click("in the loop"),
            WhileEndStep(),
            CommentStep("heading", enabled = false),
            click("under the heading"),
            CommentStep("next heading"),
            click("live again")
        )

        assertEquals(
            listOf(true, true, true, true, true, false, false),
            disabledRows(flat)
        )
    }

    /** A live comment inside a switched-off block is still not going to run. */
    @Test
    fun aSectionInsideADeadBlockStaysDead() {
        val flat = listOf(
            WhileStartStep("1 == 1", enabled = false),
            CommentStep("inside"),
            click("a"),
            WhileEndStep()
        )

        assertEquals(listOf(true, true, true, true), disabledRows(flat))
    }
}
