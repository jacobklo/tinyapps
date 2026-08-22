package net.jacoblo.simpleanki.data

import java.io.File

/**
 * Reads and writes one JSON file, quarantining it when it cannot be parsed.
 *
 * [write] does not create the parent directory; call [AnkiPaths.ensureRoot] first.
 */
class JsonStore(private val file: File) {
	/** File contents, or null when the file is missing or unreadable. */
	fun readOrNull(): String? {
		if (!file.exists()) return null
		return try {
			file.readText()
		} catch (e: Exception) {
			null
		}
	}

	fun write(text: String) {
		file.writeText(text)
	}

	/**
	 * Renames the file to "<name>.corrupt", overwriting any previous quarantine.
	 * Returns true when a file was actually moved.
	 */
	fun quarantine(): Boolean {
		if (!file.exists()) return false
		val target = File(file.path + ".corrupt")
		target.delete()
		return file.renameTo(target)
	}

	fun exists(): Boolean = file.exists()
}
