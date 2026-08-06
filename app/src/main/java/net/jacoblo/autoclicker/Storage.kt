package net.jacoblo.autoclicker

import android.os.Environment
import java.io.File

/**
 * Every file the app owns lives under one folder on shared storage, so a
 * script, the screenshots it matches against and the settings that drive it can
 * be backed up, inspected or moved as a unit.
 *
 * Recordings made before this layout existed are left where they were, under
 * Environment/Recordings, and are no longer read.
 */
object Storage {

	private val root: File
		get() = File(Environment.getExternalStorageDirectory(), "autoclicker")

	val recordingsDir: File
		get() = ensure(File(root, "recordings"))

	val screenshotsDir: File
		get() = ensure(File(root, "screenshots"))

	val settingsFile: File
		get() = File(ensure(root), "settings.json")

	val triggersFile: File
		get() = File(ensure(root), "triggers.json")

	val globalsFile: File
		get() = File(ensure(root), "globals.json")

	val screenshotIndexFile: File
		get() = File(screenshotsDir, "index.json")

	/** All-files access; without it nothing below root is readable or writable. */
	fun hasPermission(): Boolean = Environment.isExternalStorageManager()

	private fun ensure(dir: File): File {
		if (!dir.exists()) dir.mkdirs()
		return dir
	}
}
