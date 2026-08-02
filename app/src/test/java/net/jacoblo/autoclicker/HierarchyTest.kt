package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun click(name: String) =
    ClickInteraction(x = 0.5f, y = 0.5f, duration = 10, delayBefore = 0, name = name)

private fun names(events: List<Interaction>) = events.map { it.name }

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
            ForLoopInteraction(3, listOf(click("inner")), delayBefore = 0, name = "loop")
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun whileBlockRoundTrips() {
        val tree = listOf(
            WhileInteraction("count < 3", listOf(click("inner")), delayBefore = 0, name = "w")
        )
        assertEquals(tree, buildHierarchy(flatten(tree)))
    }

    @Test
    fun ifElseIfElseRoundTrips() {
        val tree = listOf(
            IfInteraction(
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
            IfInteraction(
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
            WhileInteraction(
                condition = "running == 1",
                interactions = listOf(
                    ForLoopInteraction(
                        repeatCount = 2,
                        interactions = listOf(
                            IfInteraction(
                                branches = listOf(
                                    ConditionBranch("hp < 10", listOf(click("heal")))
                                ),
                                elseBranch = listOf(
                                    IfInteraction(
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
                IfInteraction(
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
        assertTrue(flat[0] is IfStartInteraction)
        assertEquals("a", flat[1].name)
        assertTrue(flat[2] is ElseIfInteraction)
        assertEquals("b", flat[3].name)
        assertTrue(flat[4] is ElseInteraction)
        assertEquals("c", flat[5].name)
        assertTrue(flat[6] is IfEndInteraction)
    }

    @Test
    fun strayEndMarkerIsDroppedRatherThanCorruptingTheTree() {
        val rebuilt = buildHierarchy(listOf(click("a"), IfEndInteraction(), click("b")))
        assertEquals(listOf("a", "b"), names(rebuilt))
    }

    @Test
    fun balanceDetectsMissingAndOrphanedMarkers() {
        assertTrue(isBalanced(listOf(IfStartInteraction("c"), IfEndInteraction())))
        assertTrue(
            isBalanced(
                listOf(IfStartInteraction("c"), ElseIfInteraction("d"), ElseInteraction(), IfEndInteraction())
            )
        )
        assertFalse(isBalanced(listOf(IfStartInteraction("c"))))
        assertFalse(isBalanced(listOf(IfEndInteraction())))
        // An Else with no enclosing If has nothing to attach to.
        assertFalse(isBalanced(listOf(ElseInteraction())))
    }

    @Test
    fun depthsIndentBodiesButNotTheBranchMarkers() {
        val flat = listOf(
            IfStartInteraction("c"),
            click("a"),
            ElseInteraction(),
            click("b"),
            IfEndInteraction()
        )
        assertEquals(listOf(0, 1, 0, 1, 0), blockDepths(flat))
    }
}
