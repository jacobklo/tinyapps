package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls a URL with GET until it answers 2xx, then returns the body (size-capped).
 *
 * A non-2xx reply -- e.g. a 404 meaning "nothing yet" -- is treated as not-ready
 * and the poll continues until the timeout, at which point it returns null. The
 * body cap is the first DoS guard on an untrusted response, before jq ever sees it.
 */
object Http {
	private const val TAG = "autoclicker.http"
	private const val CONNECT_TIMEOUT_MS = 4000
	private const val READ_TIMEOUT_MS = 4000
	private const val MAX_BODY_CHARS = 2_000_000
	private const val MIN_INTERVAL_MS = 250L

	suspend fun pollGet(url: String, timeoutMs: Long, intervalMs: Long): String? {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (true) {
			fetch(url)?.let { return it }
			if (System.currentTimeMillis() >= deadline) return null
			delay(intervalMs.coerceAtLeast(MIN_INTERVAL_MS))
		}
	}

	private fun fetch(url: String): String? {
		val connection = try {
			(URL(url).openConnection() as HttpURLConnection).apply {
				connectTimeout = CONNECT_TIMEOUT_MS
				readTimeout = READ_TIMEOUT_MS
				requestMethod = "GET"
			}
		} catch (e: Exception) {
			Log.w(TAG, "bad url $url: ${e.message}")
			return null
		}
		return try {
			if (connection.responseCode !in 200..299) {
				null
			} else {
				connection.inputStream.bufferedReader().use { reader ->
					val buffer = CharArray(8192)
					val body = StringBuilder()
					while (true) {
						val read = reader.read(buffer)
						if (read < 0) break
						body.append(buffer, 0, read)
						if (body.length > MAX_BODY_CHARS) {
							Log.w(TAG, "response over $MAX_BODY_CHARS chars, truncating")
							break
						}
					}
					body.toString()
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "cannot reach $url: ${e.message}")
			null
		} finally {
			connection.disconnect()
		}
	}
}
