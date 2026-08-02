package net.jacoblo.autoclicker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Variables plus a couple of stub functions, standing in for the runtime. */
private class TestContext(
	private val vars: Map<String, Value> = emptyMap()
) : EvalContext {

	var calls = mutableListOf<String>()

	override fun variable(name: String): Value? = vars[name]

	override suspend fun call(name: String, args: List<Value>): Value {
		calls.add(name)
		return when (name) {
			"yes" -> Value.Bool(true)
			"no" -> Value.Bool(false)
			"twice" -> Value.Num(args[0].asNum() * 2)
			"contains" -> Value.Bool(args[0].asText().contains(args[1].asText()))
			else -> throw ExpressionException("unknown function '$name'")
		}
	}
}

private fun eval(source: String, context: EvalContext = TestContext()): Value =
	runBlocking { evaluate(parseExpression(source), context) }

class ExpressionTest {

	@Test
	fun arithmeticRespectsPrecedence() {
		assertEquals(7L, eval("1 + 2 * 3").asNum())
		assertEquals(9L, eval("(1 + 2) * 3").asNum())
		assertEquals(1L, eval("7 % 3").asNum())
		assertEquals(3L, eval("7 / 2").asNum())
	}

	@Test
	fun subtractionIsLeftAssociative() {
		assertEquals(1L, eval("10 - 2 - 7").asNum())
	}

	@Test
	fun divisionByZeroYieldsZeroRatherThanThrowing() {
		assertEquals(0L, eval("5 / 0").asNum())
		assertEquals(0L, eval("5 % 0").asNum())
	}

	@Test
	fun comparisonsAndBooleanLogic() {
		assertTrue(eval("3 > 2").asBool())
		assertTrue(eval("2 <= 2").asBool())
		assertTrue(eval("1 == 1 && 2 != 3").asBool())
		assertTrue(eval("false || true").asBool())
		assertTrue(eval("!false").asBool())
	}

	@Test
	fun plusConcatenatesWhenEitherSideIsText() {
		assertEquals("ab", eval("\"a\" + \"b\"").asText())
		assertEquals("count3", eval("\"count\" + 3").asText())
		assertEquals(3L, eval("1 + 2").asNum())
	}

	@Test
	fun variablesResolveAndDefaultToZero() {
		val context = TestContext(mapOf("count" to Value.Num(5)))
		assertEquals(5L, eval("count", context).asNum())
		assertEquals(10L, eval("count * 2", context).asNum())
		assertEquals(0L, eval("missing", context).asNum())
	}

	@Test
	fun functionsAreCalledWithEvaluatedArguments() {
		assertEquals(8L, eval("twice(4)").asNum())
		assertTrue(eval("contains(\"hello world\", \"world\")").asBool())
	}

	@Test
	fun andShortCircuitsSoTheRightSideIsNeverCalled() {
		val context = TestContext()
		assertEquals(false, eval("no() && yes()", context).asBool())
		assertEquals(listOf("no"), context.calls)
	}

	@Test
	fun orShortCircuitsSoTheRightSideIsNeverCalled() {
		val context = TestContext()
		assertEquals(true, eval("yes() || no()", context).asBool())
		assertEquals(listOf("yes"), context.calls)
	}

	@Test
	fun unaryMinusAndNestedCalls() {
		assertEquals(-6L, eval("-twice(3)").asNum())
		assertEquals(8L, eval("twice(twice(2))").asNum())
	}

	@Test(expected = ExpressionException::class)
	fun unbalancedParenthesisIsRejected() {
		eval("(1 + 2")
	}

	@Test(expected = ExpressionException::class)
	fun trailingGarbageIsRejected() {
		eval("1 + 2 3")
	}

	@Test(expected = ExpressionException::class)
	fun unterminatedStringIsRejected() {
		eval("\"abc")
	}
}
