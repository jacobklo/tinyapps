package net.jacoblo.autoclicker

import android.util.Log
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Output
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.Versions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs a jq filter over JSON and maps the result into the script's [Value] types.
 *
 * The FILTER is trusted (it is authored in the recording, and it is code); the
 * DATA is untrusted (it came from the network via http_get). jq has no eval and
 * cannot name a class, open a file, or shell out, so untrusted data can only be
 * transformed, never executed -- as long as the filter is a constant literal and
 * is never built from data. The real risk is a runaway filter, so four bounds
 * apply: input-size cap, parser nesting cap, streamed output-count cap, and a
 * wall-clock deadline on a daemon worker thread (jq eval is a synchronous Java
 * loop that a coroutine timeout would not interrupt). The scope carries only jq's
 * pure builtins -- no $ENV, no module loader, no side-effecting functions.
 */
object Jq {
  private const val TAG = "autoclicker.jq"
  private const val MAX_INPUT_CHARS = 2_000_000
  private const val MAX_OUTPUTS = 10_000
  private const val MAX_NESTING = 200
  private const val DEADLINE_MS = 2_000L

  private val version = Versions.JQ_1_6

  private val mapper = ObjectMapper(
    JsonFactory.builder()
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING).build())
      .build()
  )

  private val rootScope: Scope = Scope.newEmptyScope().also {
    BuiltinFunctionLoader.getInstance().loadFunctions(version, it)
  }

  private val compiled = ConcurrentHashMap<String, JsonQuery>()

  // Daemon so a filter abandoned at the deadline never keeps the process alive.
  private val evalPool = Executors.newCachedThreadPool { r ->
    Thread(r, "jq-eval").apply { isDaemon = true }
  }

  private class OutputCapExceeded : RuntimeException()

  /** Applies [filter] to [rawJson]; returns the mapped result, or an empty string on any failure or bound hit. */
  fun run(rawJson: String, filter: String): Value {
    if (rawJson.length > MAX_INPUT_CHARS) {
      Log.w(TAG, "input over $MAX_INPUT_CHARS chars, refusing")
      return Value.Str("")
    }
    return try {
      val query = compiled.getOrPut(filter) { JsonQuery.compile(filter, version) }
      val input = mapper.readTree(rawJson)
      val outputs = ArrayList<JsonNode>()
      val task = evalPool.submit {
        query.apply(rootScope, input, Output { out ->
          if (outputs.size >= MAX_OUTPUTS) throw OutputCapExceeded()
          outputs.add(out)
        })
      }
      try {
        task.get(DEADLINE_MS, TimeUnit.MILLISECONDS)
      } catch (e: Exception) {
        // Deadline, output cap, or an evaluation error: abandon the worker (a
        // trusted filter that runs away is the author's bug) and fail soft.
        task.cancel(true)
        Log.w(TAG, "jq bounded/failed: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
        return Value.Str("")
      }
      collapse(outputs)
    } catch (e: Throwable) {
      Log.w(TAG, "jq failed: ${e.message}")
      Value.Str("")
    }
  }

  // 0 results reads like an unset variable; 1 unwraps; many become a list.
  private fun collapse(outputs: List<JsonNode>): Value = when (outputs.size) {
    0 -> Value.Str("")
    1 -> toValue(outputs[0])
    else -> Value.Arr(outputs.map { toValue(it) })
  }

  private fun toValue(node: JsonNode): Value = when {
    node.isBoolean -> Value.Bool(node.booleanValue())
    node.isIntegralNumber -> Value.Num(node.longValue())
    // Decimals kept as text rather than truncated to a Long -- matches GlobalStore.
    node.isNumber -> Value.Str(node.asText())
    node.isTextual -> Value.Str(node.textValue())
    node.isNull || node.isMissingNode -> Value.Str("")
    node.isArray -> Value.Arr((0 until node.size()).map { toValue(node.get(it)) })
    // No Value.Obj: an object has nowhere to land, so render it for a toast.
    else -> Value.Str(node.toString())
  }
}
