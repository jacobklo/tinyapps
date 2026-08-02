package net.jacoblo.autoclicker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.io.File

private const val TAG = "autoclicker.trigger.runner"

// Foreground app and notifications are polled rather than observed. Watching
// app launches properly needs an accessibility service and notifications need
// a notification listener, and both are user-granted grants that other apps can
// enumerate -- exactly what the root work exists to avoid.
private const val POLL_INTERVAL_MS = 2000L

/**
 * Watches for the conditions defined in [TriggerStore] and plays the matching
 * recording. Hosted by the bubble's foreground service so it lives as long as
 * the app does.
 */
class TriggerRunner(private val context: Context) {

	private var thread: Thread? = null
	@Volatile private var running = false

	private var lastForegroundPackage: String? = null
	private var knownNotifications = emptySet<String>()
	// Nothing is fired on the first poll: every app and notification already on
	// screen would look like it had just appeared.
	private var primed = false

	private val screenReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent?) {
			when (intent?.action) {
				Intent.ACTION_SCREEN_ON -> fire(TriggerType.SCREEN_ON) { true }
				Intent.ACTION_SCREEN_OFF -> fire(TriggerType.SCREEN_OFF) { true }
				Intent.ACTION_USER_PRESENT -> fire(TriggerType.UNLOCKED) { true }
			}
		}
	}

	fun start() {
		if (running) return
		running = true

		// Screen state must be registered at runtime; these actions are not
		// deliverable to a manifest receiver.
		context.registerReceiver(
			screenReceiver,
			IntentFilter().apply {
				addAction(Intent.ACTION_SCREEN_ON)
				addAction(Intent.ACTION_SCREEN_OFF)
				addAction(Intent.ACTION_USER_PRESENT)
			}
		)

		thread = Thread { pollLoop() }.apply {
			isDaemon = true
			start()
		}
		Log.i(TAG, "trigger runner started")
	}

	fun stop() {
		running = false
		thread = null
		try {
			context.unregisterReceiver(screenReceiver)
		} catch (e: IllegalArgumentException) {
			// Never registered.
		}
		Log.i(TAG, "trigger runner stopped")
	}

	private fun pollLoop() {
		while (running) {
			try {
				if (AppSettings.useRoot && RootShell.isOpen && TriggerStore.list().any { it.enabled }) {
					poll()
				}
			} catch (e: Exception) {
				Log.w(TAG, "poll failed", e)
			}
			Thread.sleep(POLL_INTERVAL_MS)
		}
	}

	private fun poll() {
		// One shell round trip for both sources, filtered on the device so the
		// notification dump does not come back in full.
		val output = RootShell.execOutput(
			"dumpsys window | grep -m1 mCurrentFocus; echo '---SPLIT---'; " +
				"dumpsys notification --noredact | grep -E 'android.title=|android.text=' | head -60"
		) ?: return

		val parts = output.split("---SPLIT---")
		checkForegroundApp(parts.getOrNull(0).orEmpty())
		checkNotifications(parts.getOrNull(1).orEmpty())
		primed = true
	}

	private fun checkForegroundApp(dump: String) {
		val current = FOCUS_PACKAGE.find(dump)?.groupValues?.get(1)
		if (current == lastForegroundPackage) return

		val previous = lastForegroundPackage
		lastForegroundPackage = current

		if (!primed) return
		if (previous != null) fire(TriggerType.APP_CLOSED) { it.parameter.trim() == previous }
		if (current != null) fire(TriggerType.APP_OPENED) { it.parameter.trim() == current }
	}

	private fun checkNotifications(dump: String) {
		val current = dump.lineSequence()
			.map { it.trim() }
			.filter { it.startsWith("android.title=") || it.startsWith("android.text=") }
			.map { unwrapDumpValue(it.substringAfter('=').trim()) }
			.filter { it.isNotEmpty() && it != "null" }
			.toSet()

		val fresh = current - knownNotifications
		knownNotifications = current

		if (!primed || fresh.isEmpty()) return
		fire(TriggerType.NOTIFICATION) { trigger ->
			val needle = trigger.parameter.trim()
			needle.isNotEmpty() && fresh.any { it.contains(needle, ignoreCase = true) }
		}
	}

	private fun fire(type: TriggerType, matches: (Trigger) -> Boolean) {
		if (!AppSettings.useRoot || !RootShell.isOpen) return

		TriggerStore.list()
			.filter { it.enabled && it.type == type && matches(it) }
			.forEach { trigger ->
				// A trigger firing mid-playback would fight the running script
				// for the touchscreen.
				if (GestureExecutor.isPlaying) {
					Log.d(TAG, "already playing, ignoring ${trigger.type} -> ${trigger.recording}")
					return@forEach
				}
				val file = File(Storage.recordingsDir, trigger.recording)
				if (!file.exists()) {
					Log.w(TAG, "trigger ${trigger.id} points at a missing recording ${trigger.recording}")
					return@forEach
				}
				Log.i(TAG, "${trigger.type} fired, playing ${trigger.recording}")
				val data = RecordingManager.loadRecording(file)
				GestureExecutor.playRecording(data.events, data.globalRandom)
			}
	}

	/**
	 * dumpsys prints notification fields as `String (the actual text)`. Keeping
	 * the wrapper would let a keyword match the type name rather than the
	 * message.
	 */
	private fun unwrapDumpValue(raw: String): String {
		val open = raw.indexOf('(')
		return if (open >= 0 && raw.endsWith(")")) raw.substring(open + 1, raw.length - 1).trim() else raw
	}

	private companion object {
		val FOCUS_PACKAGE = Regex("""mCurrentFocus=Window\{[^}]*\s+([A-Za-z0-9_.]+)/""")
	}
}
