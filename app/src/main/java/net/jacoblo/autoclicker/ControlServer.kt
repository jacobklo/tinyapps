package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.File

private const val TAG = "autoclicker.control.server"

/**
 * Line-delimited JSON control surface on loopback, so another app on the device
 * can play a recording rather than reimplementing playback.
 *
 * A request is `{"reqId":..,"cmd":..,"args":{..}}` and the reply is
 * `{"reqId":..,"status":"ok","result":{..}}` or `{"reqId":..,"status":"err",
 * "error":".."}` -- the same shape Droidvate's own control server uses, since it
 * is the caller on the other end.
 *
 * Run state is tracked here rather than read back off [GestureExecutor], because
 * `isPlaying` goes false the moment the job is cancelled while the result is
 * only known once the finally block runs. A caller polling in that window would
 * see "not playing" next to the previous run's result and take it for this one.
 */
class ControlServer(private val port: Int, private val scope: CoroutineScope) {

	private var serverSocket: ServerSocket? = null
	private var acceptJob: Job? = null

	private var runId = 0L
	private var playing = false
	private var lastResult: PlaybackResult? = null

	fun start() {
		if (acceptJob != null) return
		acceptJob = scope.launch(Dispatchers.IO) {
			try {
				// Loopback only: this drives the touchscreen, and there is no
				// reason for it to be reachable from the network.
				val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
				serverSocket = socket
				Log.i(TAG, "listening on 127.0.0.1:$port")
				while (true) {
					val client = socket.accept()
					scope.launch(Dispatchers.IO) { handleConnection(client) }
				}
			} catch (e: Exception) {
				Log.w(TAG, "accept loop ended: ${e.message}")
			}
		}
	}

	fun stop() {
		acceptJob?.cancel()
		acceptJob = null
		try {
			serverSocket?.close()
		} catch (e: Exception) {
			Log.d(TAG, "socket already closed")
		}
		serverSocket = null
	}

	private suspend fun handleConnection(client: Socket) {
		try {
			val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
			val writer = client.getOutputStream()
			var line: String?
			while (reader.readLine().also { line = it } != null) {
				val response = process(line!!)
				writer.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
				writer.flush()
			}
		} catch (e: Exception) {
			Log.w(TAG, "connection error: ${e.message}")
		} finally {
			try {
				client.close()
			} catch (e: Exception) {
				Log.d(TAG, "client already closed")
			}
		}
	}

	private suspend fun process(line: String): JSONObject {
		var reqId = ""
		return try {
			val request = JSONObject(line)
			reqId = request.optString("reqId", "")
			val args = request.optJSONObject("args") ?: JSONObject()
			val result = handle(request.getString("cmd"), args)
			JSONObject().put("reqId", reqId).put("status", "ok").put("result", result)
		} catch (e: Exception) {
			Log.w(TAG, "command failed: ${e.message}")
			JSONObject().put("reqId", reqId).put("status", "err")
				.put("error", "${e.javaClass.simpleName}: ${e.message}")
		}
	}

	private suspend fun handle(cmd: String, args: JSONObject): JSONObject = when (cmd) {
		"status" -> status()
		"play" -> play(args.getString("recording"))
		"stop" -> stopPlayback()
		"vars_list" -> varsList()
		"vars_set" -> varsSet(args.getJSONObject("vars"))
		"vars_delete" -> varsDelete(args.getString("name"))
		"vars_clear" -> {
			GlobalStore.clear()
			varsList()
		}
		else -> throw IllegalArgumentException("unknown command: $cmd")
	}

	// -----------------------------------------------------------------------
	// Playback
	// -----------------------------------------------------------------------

	private fun status(): JSONObject {
		val json = JSONObject()
			.put("ready", notReady() == null)
			.put("playing", playing)
			.put("runId", runId)
		notReady()?.let { json.put("reason", it) }
		when (val result = lastResult) {
			null -> Unit
			is PlaybackResult.Completed -> json.put("lastResult", "completed")
				.put("degraded", result.degraded)
			is PlaybackResult.Stopped -> json.put("lastResult", "stopped")
			is PlaybackResult.Failed -> json.put("lastResult", "failed")
				.put("reason", result.reason)
		}
		return json
	}

	/** Null when playback would work; otherwise names the thing to fix. */
	private fun notReady(): String? = when {
		!Storage.hasPermission() -> "all-files access not granted"
		!AppSettings.useRoot -> "root replay is off in settings"
		!RootShell.isOpen -> "no root shell"
		!GestureExecutor.isReady() -> "gesture backend not ready"
		else -> null
	}

	private suspend fun play(name: String): JSONObject {
		notReady()?.let { throw IllegalStateException(it) }
		// Two scripts sharing the touchscreen would fight over it, which is the
		// same reason a trigger firing mid-playback is ignored.
		if (playing) throw IllegalStateException("already playing")

		// A name rather than a path: recordings live in one folder, and a caller
		// that could name any file could read one this app has no business
		// opening.
		val file = File(Storage.recordingsDir, name)
		if (file.parentFile != Storage.recordingsDir || !file.exists()) {
			throw IllegalArgumentException("no such recording: $name")
		}

		val data = RecordingManager.loadRecording(file)
		if (data.events.isEmpty()) throw IllegalArgumentException("recording has no events: $name")

		runId++
		playing = true
		lastResult = null
		val id = runId
		Log.i(TAG, "run $id playing $name (${data.events.size} events)")
		// playRecording touches the bubble's views, so it has to be started from
		// the main thread rather than the socket's IO one.
		withContext(Dispatchers.Main) {
			GestureExecutor.playRecording(data.events, data.globalRandom) { result ->
				// A stopped run's callback can land after the next one has
				// started; without this it would report that run as finished.
				if (id != runId) return@playRecording
				Log.i(TAG, "run $id finished: $result")
				lastResult = result
				playing = false
			}
		}
		return JSONObject().put("runId", id).put("events", data.events.size)
	}

	private suspend fun stopPlayback(): JSONObject {
		withContext(Dispatchers.Main) { GestureExecutor.stop() }
		// The cancelled run does report Stopped through its own callback, but not
		// until its finally block runs. Recording it here as well closes the
		// window where a caller would poll and still see the run in flight.
		if (playing) {
			playing = false
			lastResult = PlaybackResult.Stopped
		}
		return status()
	}

	// -----------------------------------------------------------------------
	// Global variables
	// -----------------------------------------------------------------------

	private fun varsList(): JSONObject {
		val vars = JSONObject()
		GlobalStore.all().forEach { (name, value) -> vars.put(name, GlobalStore.toJson(value)) }
		return JSONObject().put("vars", vars)
	}

	private fun varsSet(vars: JSONObject): JSONObject {
		GlobalStore.set(vars.keys().asSequence().associateWith { GlobalStore.toValue(vars.get(it)) })
		return varsList()
	}

	private fun varsDelete(name: String): JSONObject {
		GlobalStore.delete(name)
		return varsList()
	}
}
