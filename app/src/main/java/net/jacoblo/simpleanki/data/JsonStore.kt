package net.jacoblo.simpleanki.data

import java.io.File
import java.io.IOException

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
		} catch (_: Exception) {
			null
		}
	}

	/**
	 * Replaces the file atomically by writing a temp file and renaming it over the target,
	 * so a process kill mid-write leaves the previous contents intact.
	 *
	 * @throws IOException when the file cannot be written.
	 */
	fun write(text: String) {
		val tmp = File(file.path + ".tmp")
		tmp.writeText(text)
		if (!tmp.renameTo(file)) {
			tmp.delete()
			throw IOException("could not replace ${file.path}")
		}
	}

	/**
	 * Renames the file to "<name>.corrupt", overwriting any previous quarantine.
	 * Returns false when there was no file to move, or when the rename failed.
	 */
	fun quarantine(): Boolean {
		if (!file.exists()) return false
		val target = File(file.path + ".corrupt")
		target.delete()
		return file.renameTo(target)
	}

	fun exists(): Boolean = file.exists()
}
