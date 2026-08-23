package net.jacoblo.simpleanki.data

import java.io.File
import java.io.IOException

/**
 * What a read of a JSON file found.
 *
 * [Absent] and [Unreadable] are separate cases on purpose. Folding them into one null
 * makes a transient read failure on a perfectly good file look like a first run, and a
 * caller that recreates the file from defaults on a first run would then destroy it.
 * Absence is the only state with nothing to preserve.
 */
sealed interface ReadResult {
	/** No such file. Recreating it loses nothing. */
	data object Absent : ReadResult

	/** The file is there but could not be read. Retry; do not replace. */
	data object Unreadable : ReadResult

	data class Present(val text: String) : ReadResult

	/** The contents, or null for [Absent] and [Unreadable]. */
	val textOrNull: String? get() = (this as? Present)?.text
}

/**
 * Reads and writes one JSON file, quarantining it when it cannot be parsed.
 *
 * [write] does not create the parent directory; call [AnkiPaths.ensureRoot] first.
 */
class JsonStore(private val file: File) {
	/** Whether the file is absent, unreadable, or readable - and if so, its contents. */
	fun read(): ReadResult {
		if (!file.exists()) return ReadResult.Absent
		return try {
			ReadResult.Present(file.readText())
		} catch (_: Exception) {
			ReadResult.Unreadable
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
	 * Renames the file to "<name>.corrupt", KEEPING any quarantine already there.
	 *
	 * Overwriting would be a data-loss path rather than a tidiness question. A caller
	 * quarantines and then writes defaults, so the second incident's file is the defaulted
	 * one this app wrote while the first one holds whatever the user actually had. Letting
	 * the second overwrite the first therefore trades the only valuable copy for a
	 * worthless one - and for settings.json the valuable copy carries a lifetime review
	 * count that exists nowhere else.
	 *
	 * Returns false when there was no file to move, when a quarantine already exists, or
	 * when the rename failed. In every one of those cases the file is left exactly where
	 * it was, for the caller's own write to replace.
	 */
	fun quarantine(): Boolean {
		if (!file.exists()) return false
		val target = File(file.path + ".corrupt")
		if (target.exists()) return false
		return file.renameTo(target)
	}

	fun exists(): Boolean = file.exists()
}
