package net.jacoblo.autoclicker

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

private const val TAG = "autoclicker.root.shell"

/**
 * A single long-lived `su` shell that every root gesture is written into.
 *
 * Spawning `su` per gesture costs hundreds of milliseconds, so the process is
 * kept open for the lifetime of the app. Each command is followed by an echoed
 * marker so [exec] can block until the command actually finished -- playback
 * paces the next interaction off that return.
 */
object RootShell {

	private const val MARKER = "__autoclicker_done__"

	private var process: Process? = null
	private var stdin: OutputStream? = null
	private var stdout: BufferedReader? = null

	val isOpen: Boolean
		@Synchronized get() = process != null

	/**
	 * Starts the root shell. Blocks until the superuser prompt is answered, so
	 * this must never run on the main thread.
	 */
	@Synchronized
	fun open(): Boolean {
		if (process != null) return true
		try {
			val started = ProcessBuilder("su").redirectErrorStream(true).start()
			process = started
			stdin = started.outputStream
			stdout = BufferedReader(InputStreamReader(started.inputStream))
		} catch (e: Exception) {
			Log.e(TAG, "cannot start su", e)
			close()
			return false
		}

		// A denied prompt still yields a live process, so prove the shell works.
		if (!exec("id")) {
			Log.w(TAG, "su started but is not usable")
			close()
			return false
		}
		Log.i(TAG, "root shell opened")
		return true
	}

	@Synchronized
	fun close() {
		try {
			stdin?.write("exit\n".toByteArray())
			stdin?.flush()
		} catch (e: Exception) {
			Log.d(TAG, "shell was already broken while closing", e)
		}
		process?.destroy()
		process = null
		stdin = null
		stdout = null
	}

	/** Runs one command and blocks until it completes. */
	@Synchronized
	fun exec(command: String): Boolean {
		val out = stdin ?: return false
		val reader = stdout ?: return false
		try {
			out.write("$command\necho $MARKER\n".toByteArray())
			out.flush()
			while (true) {
				val line = reader.readLine()
				if (line == null) {
					Log.w(TAG, "root shell died running: $command")
					close()
					return false
				}
				if (line.trim() == MARKER) return true
				Log.d(TAG, "su> $line")
			}
		} catch (e: Exception) {
			Log.e(TAG, "root command failed: $command", e)
			close()
			return false
		}
	}

	/** Revives the shell after a su daemon restart killed it mid-session. */
	@Synchronized
	private fun ensureOpen(): Boolean = isOpen || open()

	fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
		if (!ensureOpen()) return false
		return exec("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(1)}")
	}

	/** `input text` reads %s as a space, so literal spaces have to be encoded. */
	fun text(value: String): Boolean {
		if (!ensureOpen()) return false
		val escaped = value.replace("'", "'\\''").replace(" ", "%s")
		return exec("input text '$escaped'")
	}
}
