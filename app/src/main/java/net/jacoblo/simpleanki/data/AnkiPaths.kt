package net.jacoblo.simpleanki.data

import android.os.Environment
import java.io.File

/**
 * Resolves every file the app reads or writes from Environment.
 *
 * Only [production] and [testMode] reach for an Android API. The constructor, [at] and
 * every instance member stay Android-free, so a JVM test can point an instance at a
 * temporary folder and exercise real repository code.
 */
class AnkiPaths(val root: File) {
	val deck: File get() = File(root, "simple-anki.json")
	val history: File get() = File(root, "history.json")
	val historyBackup: File get() = File(root, "history.json.bak")
	val settings: File get() = File(root, "settings.json")
	val views: File get() = File(root, "views.json")
	val dump: File get() = File(root, "dump.json")

	/**
	 * Creates the root directory if absent. Safe to call repeatedly.
	 * A creation failure is not reported here; the subsequent write throws.
	 */
	fun ensureRoot() {
		if (!root.exists()) root.mkdirs()
	}

	companion object {
		private const val PRODUCTION_DIR_NAME = "SimpleAnki"
		private const val TEST_MODE_DIR_NAME = "SimpleAnki-test"

		/** /sdcard/SimpleAnki - touches Environment. */
		fun production(): AnkiPaths =
			AnkiPaths(File(Environment.getExternalStorageDirectory(), PRODUCTION_DIR_NAME))

		/** /sdcard/SimpleAnki-test - touches Environment. */
		fun testMode(): AnkiPaths =
			AnkiPaths(File(Environment.getExternalStorageDirectory(), TEST_MODE_DIR_NAME))

		/** Arbitrary root. Touches no Android API; this is the JVM test seam. */
		fun at(root: File): AnkiPaths = AnkiPaths(root)
	}
}
