package net.jacoblo.moodlauncher

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class DriveSync(private val context: Context) {

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val OAUTH_SCOPE = "oauth2:$DRIVE_SCOPE"
        private const val PREFS = "drive_sync"
        private const val KEY_FILE_ID = "file_id"
        private const val FILE_NAME = "moodlauncher_notes.json"
        private const val MIME = "application/json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSignedIn(): Boolean =
        GoogleSignIn.getLastSignedInAccount(context) != null

    /** Returns the Google account if signed in, or null. */
    fun signedInAccount(): Account? =
        GoogleSignIn.getLastSignedInAccount(context)?.account

    /**
     * Uploads [content] to the user's Drive as [FILE_NAME].
     * Creates the file on first call; updates it on subsequent calls.
     *
     * Must NOT be called on the main thread (uses [Dispatchers.IO] internally).
     *
     * @return [Result.success] on success, [Result.failure] with the cause otherwise.
     *         A [UserRecoverableAuthException] means the UI needs to show the
     *         auth-recovery intent from [UserRecoverableAuthException.intent].
     */
    suspend fun syncFile(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val account = signedInAccount()
                ?: return@withContext Result.failure(IllegalStateException("Not signed in"))

            val token = GoogleAuthUtil.getToken(context, account, OAUTH_SCOPE)

            val savedId = prefs.getString(KEY_FILE_ID, null)

            val fileId: String? = when {
                savedId != null && driveFileExists(token, savedId) -> savedId
                else -> findFile(token, FILE_NAME)
                    ?.also { prefs.edit().putString(KEY_FILE_ID, it).apply() }
            }

            if (fileId != null) {
                updateFile(token, fileId, content)
            } else {
                val newId = createFile(token, FILE_NAME, content)
                prefs.edit().putString(KEY_FILE_ID, newId).apply()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── REST helpers ──────────────────────────────────────────────────────────

    private fun driveFileExists(token: String, fileId: String): Boolean {
        val conn = openGet(
            "https://www.googleapis.com/drive/v3/files/$fileId?fields=id",
            token
        )
        return try { conn.responseCode == 200 } finally { conn.disconnect() }
    }

    private fun findFile(token: String, name: String): String? {
        val q = URLEncoder.encode("name='$name' and trashed=false", "UTF-8")
        val conn = openGet(
            "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&pageSize=1",
            token
        )
        return try {
            if (conn.responseCode != 200) return null
            val arr = JSONObject(conn.inputStream.bufferedReader().readText())
                .getJSONArray("files")
            if (arr.length() > 0) arr.getJSONObject(0).getString("id") else null
        } finally {
            conn.disconnect()
        }
    }

    private fun createFile(token: String, name: String, content: String): String {
        val boundary = UUID.randomUUID().toString()
        val body = "--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
            """{"name":"$name","mimeType":"$MIME"}""" + "\r\n" +
            "--$boundary\r\n" +
            "Content-Type: $MIME\r\n\r\n" +
            content + "\r\n" +
            "--$boundary--"

        val conn = URL(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        ).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        return try {
            JSONObject(conn.inputStream.bufferedReader().readText()).getString("id")
        } finally {
            conn.disconnect()
        }
    }

    private fun updateFile(token: String, fileId: String, content: String) {
        val conn = URL(
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
        ).openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", MIME)
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(content) }
        try { conn.responseCode } finally { conn.disconnect() }
    }

    private fun openGet(urlStr: String, token: String): HttpURLConnection {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }
}
