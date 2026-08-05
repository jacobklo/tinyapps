package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "autoclicker.code.server"

// The server rebuilds its list from Gmail every 50 seconds, so a tighter poll
// than this cannot make a code arrive sooner; it only wastes the LAN round trip.
private const val POLL_INTERVAL_MS = 2000L

private const val CONNECT_TIMEOUT_MS = 4000
private const val READ_TIMEOUT_MS = 4000

/**
 * Reads six-digit verification codes from the gmail-six-digit service.
 *
 * The service answers `GET /codes` with a JSON array ranked best-first, and
 * with 404 and an empty body when it has none. It keeps everything from the
 * last ten minutes, so a caller that simply takes the first entry can easily
 * type a code from a previous login: [waitForCodes] therefore filters on the
 * age the server reports and only reports codes that are actually recent.
 */
object CodeServer {

	sealed class Result {
		data class Found(val codes: List<String>) : Result()

		/** Phrased for showing to the user, so it names the thing to fix. */
		data class Failed(val reason: String) : Result()
	}

	/**
	 * Polls until at least one code is newer than [maxAgeSeconds], or
	 * [timeoutMs] elapses. Suspends between attempts, so the stop button still
	 * interrupts it.
	 */
	suspend fun waitForCodes(maxAgeSeconds: Long, timeoutMs: Long): Result {
		val base = AppSettings.codeServer.trim()
		if (base.isBlank()) {
			return Result.Failed("no code server set, add one in Settings")
		}
		val url = endpoint(base)
		val deadline = System.currentTimeMillis() + timeoutMs

		var lastFailure: String? = null
		while (true) {
			when (val attempt = fetch(url, maxAgeSeconds)) {
				is Result.Found -> return attempt
				is Result.Failed -> lastFailure = attempt.reason
			}
			if (System.currentTimeMillis() >= deadline) {
				return Result.Failed(lastFailure ?: "no code arrived in time")
			}
			delay(POLL_INTERVAL_MS)
		}
	}

	// Accepts a bare host:port as well as a full URL, since the path is fixed
	// and typing it into settings adds nothing but a chance to get it wrong.
	private fun endpoint(base: String): String {
		val withScheme = if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base"
		val trimmed = withScheme.trimEnd('/')
		return if (trimmed.endsWith("/codes")) trimmed else "$trimmed/codes"
	}

	private fun fetch(url: String, maxAgeSeconds: Long): Result {
		val connection = try {
			(URL(url).openConnection() as HttpURLConnection).apply {
				connectTimeout = CONNECT_TIMEOUT_MS
				readTimeout = READ_TIMEOUT_MS
				requestMethod = "GET"
			}
		} catch (e: Exception) {
			Log.w(TAG, "cannot open $url", e)
			return Result.Failed("code server address is not a valid URL")
		}

		return try {
			val status = connection.responseCode
			// 404 is the documented "nothing to report yet", not a fault.
			if (status == HttpURLConnection.HTTP_NOT_FOUND) {
				return Result.Failed("the code server has no codes yet")
			}
			if (status != HttpURLConnection.HTTP_OK) {
				return Result.Failed("code server answered $status")
			}
			val body = connection.inputStream.bufferedReader().use { it.readText() }
			parse(body, maxAgeSeconds)
		} catch (e: Exception) {
			Log.w(TAG, "cannot reach $url", e)
			Result.Failed("cannot reach the code server")
		} finally {
			connection.disconnect()
		}
	}

	private fun parse(body: String, maxAgeSeconds: Long): Result {
		val codes = try {
			val array = JSONArray(body)
			// Array order is the server's ranking and is preserved deliberately.
			(0 until array.length()).mapNotNull { i ->
				val entry = array.optJSONObject(i) ?: return@mapNotNull null
				val code = entry.optString("code")
				val age = entry.optLong("age_seconds", Long.MAX_VALUE)
				if (code.isBlank() || age > maxAgeSeconds) null else code
			}
		} catch (e: Exception) {
			Log.w(TAG, "cannot parse code server response", e)
			return Result.Failed("code server sent something unreadable")
		}

		if (codes.isEmpty()) {
			return Result.Failed("no code newer than ${maxAgeSeconds}s")
		}
		// Codes themselves stay out of the log at INFO, matching the server.
		Log.d(TAG, "got ${codes.size} code(s) within ${maxAgeSeconds}s")
		return Result.Found(codes)
	}
}
