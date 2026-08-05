package net.jacoblo.autoclicker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lists, which reach a script only through the Wait for code step.
 *
 * The awkward cases are all about a script walking off the end of one: reading
 * past the last code has to leave the run alive, because the alternative is a
 * script that stops halfway through a login.
 */
class ArrayValueTest {

    private class Context(private val vars: Map<String, Value>) : EvalContext {
        override fun variable(name: String): Value? = vars[name]
        override suspend fun call(name: String, args: List<Value>): Value = when (name) {
            "count" -> Value.Num((args.getOrNull(0) as? Value.Arr)?.items?.size?.toLong() ?: 0L)
            else -> throw ExpressionException("unknown function '$name'")
        }
    }

    private fun codes(vararg values: String) = Value.Arr(values.map { Value.Str(it) })

    private fun eval(source: String, vars: Map<String, Value> = emptyMap()): Value =
        runBlocking { evaluate(parseExpression(source), Context(vars)) }

    private val threeCodes = mapOf("codes" to codes("111111", "222222", "333333"))

    @Test
    fun indexingReadsInRankOrder() {
        assertEquals("111111", eval("codes[0]", threeCodes).asText())
        assertEquals("222222", eval("codes[1]", threeCodes).asText())
        assertEquals("333333", eval("codes[2]", threeCodes).asText())
    }

    @Test
    fun theIndexIsItselfAnExpression() {
        val vars = threeCodes + mapOf("i" to Value.Num(1))
        assertEquals("333333", eval("codes[i + 1]", vars).asText())
        assertEquals("222222", eval("codes[i]", vars).asText())
    }

    @Test
    fun readingPastTheEndIsEmptyRatherThanAnError() {
        assertEquals("", eval("codes[3]", threeCodes).asText())
        assertEquals("", eval("codes[99]", threeCodes).asText())
        assertEquals("", eval("codes[0 - 1]", threeCodes).asText())
    }

    @Test
    fun indexingSomethingThatIsNotAListIsEmpty() {
        assertEquals("", eval("n[0]", mapOf("n" to Value.Num(5))).asText())
        assertEquals("", eval("missing[0]").asText())
    }

    @Test
    fun countReportsTheLength() {
        assertEquals(3L, eval("count(codes)", threeCodes).asNum())
        assertEquals(0L, eval("count(codes)", mapOf("codes" to Value.Arr(emptyList()))).asNum())
        // Anything that is not a list has no length, so a guard on it ends at once.
        assertEquals(0L, eval("count(n)", mapOf("n" to Value.Num(7))).asNum())
    }

    @Test
    fun aListIsTrueWhenItHasAnything() {
        assertTrue(eval("codes", threeCodes).asBool())
        assertFalse(eval("codes", mapOf("codes" to Value.Arr(emptyList()))).asBool())
    }

    /** So a Toast can show everything that arrived without indexing each one. */
    @Test
    fun textOfAListJoinsTheEntries() {
        assertEquals("111111, 222222, 333333", eval("codes", threeCodes).asText())
        assertEquals("", eval("codes", mapOf("codes" to Value.Arr(emptyList()))).asText())
    }

    /**
     * A list must not quietly behave as its own length, or `codes > 2` would
     * look like it asked a sensible question.
     */
    @Test
    fun aListHasNoNumericValue() {
        assertEquals(0L, eval("codes", threeCodes).asNum())
    }

    @Test
    fun indexingBindsTighterThanOperators() {
        val vars = threeCodes + mapOf("n" to Value.Num(1))
        // Concatenation of the element, not indexing of a concatenation.
        assertEquals("111111x", eval("codes[0] + \"x\"", vars).asText())
        assertTrue(eval("codes[0] == \"111111\"", vars).asBool())
        // Negation applies to the element that was read.
        assertEquals(-111111L, eval("-codes[0]", vars).asNum())
    }

    @Test
    fun aMissingBracketIsReportedAtParseTime() {
        val error = runCatching { parseExpression("codes[0") }.exceptionOrNull()
        assertTrue("expected a parse error, got $error", error is ExpressionException)
    }

    /** The typical loop from the help text has to terminate on an empty list. */
    @Test
    fun walkingAnEmptyListRunsZeroTimes() {
        val vars = mapOf("codes" to Value.Arr(emptyList()), "i" to Value.Num(0))
        assertFalse(eval("i < count(codes)", vars).asBool())
    }
}
