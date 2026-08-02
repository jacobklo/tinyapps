package net.jacoblo.autoclicker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Toast messages are literal text with {expression} substituted, so plain text
 * needs no quoting. Malformed braces must degrade to showing the text rather
 * than swallowing the rest of the message.
 */
class InterpolationTest {

    private fun interpolate(template: String, vars: Map<String, Value> = emptyMap()): String =
        runBlocking {
            val context = ScriptContext()
            vars.forEach { (name, value) -> context.set(name, value) }
            context.interpolate(template)
        }

    @Test
    fun plainTextPassesThroughUntouched() {
        assertEquals("Script finished", interpolate("Script finished"))
        assertEquals("", interpolate(""))
    }

    @Test
    fun substitutesAVariable() {
        assertEquals(
            "attempt 3 of 5",
            interpolate("attempt {count} of 5", mapOf("count" to Value.Num(3)))
        )
    }

    @Test
    fun substitutesSeveralAndEvaluatesArithmetic() {
        assertEquals(
            "2 done, 8 left",
            interpolate("{done} done, {total - done} left", mapOf("done" to Value.Num(2), "total" to Value.Num(10)))
        )
    }

    @Test
    fun unknownVariableRendersAsZeroRatherThanFailing() {
        assertEquals("value 0", interpolate("value {missing}"))
    }

    @Test
    fun brokenExpressionDoesNotAbortTheMessage() {
        // The bad expression evaluates to 0 and the surrounding text survives.
        assertEquals("a 0 b", interpolate("a {1 +} b"))
    }

    @Test
    fun unclosedBraceIsLeftAsWritten() {
        assertEquals("oops {count", interpolate("oops {count", mapOf("count" to Value.Num(1))))
    }

    @Test
    fun bracesAtTheEdgesWork() {
        assertEquals("7", interpolate("{3 + 4}"))
        assertEquals("x7y", interpolate("x{3 + 4}y"))
    }
}
