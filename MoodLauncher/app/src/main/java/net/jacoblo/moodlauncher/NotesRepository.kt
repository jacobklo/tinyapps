package net.jacoblo.moodlauncher

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class NotesRepository(private val context: Context) {

    private val driveSync = DriveSync(context)

    private val notesDir: File
        get() = File(Environment.getExternalStorageDirectory(), "moodlauncher")

    private val notesFile: File
        get() = File(notesDir, "notes.json")

    suspend fun loadNotes(): Map<String, DayNote> = withContext(Dispatchers.IO) {
        val file = notesFile
        if (!file.exists()) return@withContext emptyMap()
        try {
            parseJson(file.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun saveNote(dateKey: String, note: DayNote) = withContext(Dispatchers.IO) {
        val current = loadNotes().toMutableMap()
        if (note.emoji.isBlank() && note.notes.isBlank()) {
            current.remove(dateKey)
        } else {
            current[dateKey] = note
        }
        val json = toJson(current)
        writeRaw(json)

        // Auto-sync to Drive whenever signed in
        if (driveSync.isSignedIn()) {
            driveSync.syncFile(json)
        }
    }

    /** Raw JSON string — used by DriveSync for uploads. */
    fun readRaw(): String = try { notesFile.readText() } catch (_: Exception) { "{}" }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun parseJson(raw: String): Map<String, DayNote> {
        val obj = JSONObject(raw)
        val result = mutableMapOf<String, DayNote>()
        for (key in obj.keys()) {
            val entry = obj.getJSONObject(key)
            result[key] = DayNote(
                emoji = entry.optString("emoji", ""),
                notes = entry.optString("notes", "")
            )
        }
        return result
    }

    private fun toJson(notes: Map<String, DayNote>): String {
        val obj = JSONObject()
        for ((key, note) in notes) {
            obj.put(key, JSONObject().apply {
                put("emoji", note.emoji)
                put("notes", note.notes)
            })
        }
        return obj.toString(2)
    }

    private fun writeRaw(json: String) {
        notesDir.mkdirs()
        notesFile.writeText(json)
    }
}
