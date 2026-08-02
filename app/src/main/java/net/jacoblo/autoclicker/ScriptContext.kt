package net.jacoblo.autoclicker

import android.util.Log
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
		else -> throw ExpressionException("unknown function '$name'")
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

	suspend fun evaluateOrZero(source: String): Value = try {
		evaluate(parseExpression(source), this)
	} catch (e: ExpressionException) {
		Log.w(TAG, "expression failed: '$source' -- ${e.message}")
		Value.Num(0)
	}
}
