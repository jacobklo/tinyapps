package net.jacoblo.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JqTest {

	@Test
	fun extractsAFieldIntoAList() {
		val v = Jq.run("""[{"code":"111"},{"code":"222"}]""", "[.[].code]")
		assertTrue(v is Value.Arr)
		v as Value.Arr
		assertEquals(2, v.items.size)
		assertEquals("111", v.items[0].asText())
		assertEquals("222", v.items[1].asText())
	}

	@Test
	fun extractsASingleValue() {
		assertEquals("abc", Jq.run("""{"token":"abc"}""", ".token").asText())
	}

	@Test
	fun filtersByAField() {
		val v = Jq.run(
			"""[{"code":"a","age_seconds":10},{"code":"b","age_seconds":999}]""",
			"[.[] | select(.age_seconds <= 120) | .code]"
		)
		assertTrue(v is Value.Arr)
		assertEquals(1, (v as Value.Arr).items.size)
		assertEquals("a", v.items[0].asText())
	}

	@Test
	fun integerAndBoolMapToTypedValues() {
		assertEquals(7L, Jq.run("""{"n":7}""", ".n").asNum())
		assertTrue((Jq.run("""{"ok":true}""", ".ok") as Value.Bool).value)
	}

	@Test
	fun malformedFilterFailsSoftToEmpty() {
		assertEquals("", Jq.run("""{"a":1}""", "|||not jq").asText())
	}

	@Test
	fun malformedJsonFailsSoftToEmpty() {
		assertEquals("", Jq.run("not json at all", ".a").asText())
	}

	@Test
	fun runawayFilterIsBoundedNotHung() {
		// Would emit 100M values; the streamed output cap must abort and fail soft.
		assertEquals("", Jq.run("null", "range(100000000)").asText())
	}
}
