package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TAG = "autoclicker.script.context"

/** Thrown by a Break action and caught by the innermost enclosing loop. */
class BreakSignal : Exception(null, null, false, false)

/**
 * Variables and built-in functions for one playback run.
 *
 * A fresh instance per run, so a script cannot be influenced by values left
 * over from the previous one.
 */
class ScriptContext : EvalContext {

	private val variables = mutableMapOf<String, Value>()

	override fun variable(name: String): Value? = variables[name]

	fun set(name: String, value: Value) {
		variables[name] = value
	}

	override suspend fun call(name: String, args: List<Value>): Value = when (name) {
		"contains" -> Value.Bool(
			args.getOrNull(0)?.asText().orEmpty().contains(args.getOrNull(1)?.asText().orEmpty())
		)
		"random" -> {
			val low = args.getOrNull(0)?.asNum() ?: 0L
			val high = args.getOrNull(1)?.asNum() ?: 0L
			Value.Num(if (high <= low) low else Random.nextLong(low, high + 1))
		}

		// image(name[, threshold]) -- is the saved area on screen right now
		"image" -> {
			val area = args.getOrNull(0)?.asText().orEmpty()
			val threshold = thresholdArg(args.getOrNull(1))
			Value.Bool(withContext(Dispatchers.IO) { ScreenConditions.matches(area, threshold) })
		}

		// waitImage(name, ms[, threshold]) -- poll until it appears or time runs out
		"waitImage" -> {
			val area = args.getOrNull(0)?.asText().orEmpty()
			val timeout = args.getOrNull(1)?.asNum() ?: 0L
			val threshold = thresholdArg(args.getOrNull(2))
			Value.Bool(ScreenConditions.waitFor(area, timeout, threshold))
		}

		else -> throw ExpressionException("unknown function '$name'")
	}

	// Accepts either a fraction (0.9) or a percentage (90), since both read
	// naturally in a condition.
	private fun thresholdArg(value: Value?): Float {
		val raw = value?.asNum() ?: return DEFAULT_MATCH_THRESHOLD
		if (raw <= 0L) return DEFAULT_MATCH_THRESHOLD
		return if (raw > 1L) (raw / 100f).coerceAtMost(1f) else raw.toFloat()
	}

	/**
	 * A condition that will not parse or evaluate is treated as false rather
	 * than aborting the run, and logged so the cause is findable.
	 */
	suspend fun condition(source: String): Boolean = try {
		evaluate(parseExpression(source), this).asBool()
	} catch (e: ExpressionException) {
		Log.w(TAG, "condition failed: '$source' -- ${e.message}")
		false
	}

	/**
	 * Substitutes {expression} in otherwise literal text.
	 *
	 * Text with no braces comes back untouched, so a plain message needs no
	 * quoting; an unclosed brace is left as written rather than swallowing the
	 * rest of the message.
	 */
	suspend fun interpolate(template: String): String {
		if (!template.contains('{')) return template

		val result = StringBuilder()
		var index = 0
		while (index < template.length) {
			val open = template.indexOf('{', index)
			if (open < 0) {
				result.append(template, index, template.length)
				break
			}
			result.append(template, index, open)

			val close = template.indexOf('}', open)
			if (close < 0) {
				result.append(template, open, template.length)
				break
			}
			result.append(evaluateOrZero(template.substring(open + 1, close)).asText())
			index = close + 1
		}
		return result.toString()
	}

	suspend fun evaluateOrZero(source: String): Value = try {
		evaluate(parseExpression(source), this)
	} catch (e: ExpressionException) {
		Log.w(TAG, "expression failed: '$source' -- ${e.message}")
		Value.Num(0)
	}
}
