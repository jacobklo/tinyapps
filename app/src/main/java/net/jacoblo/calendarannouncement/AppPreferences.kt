package net.jacoblo.calendarannouncement

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppPreferences(context: Context) {

    companion object {
        const val KEY_SYNC_INTERVAL = "sync_interval"
        const val KEY_ANNOUNCE_BEFORE = "announce_before"
        const val KEY_USE_GOOGLE = "use_google_calendar"
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_TTS_LANGUAGE = "tts_language"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_TTS_SPEED = "tts_speed"
        const val KEY_TTS_AUDIO_STREAM = "tts_audio_stream"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_DISABLED_CALENDAR_IDS = "disabled_calendar_ids"
        const val KEY_GOOGLE_CLIENT_ID = "google_client_id"
        const val KEY_GOOGLE_CLIENT_SECRET = "google_client_secret"

        private const val SEC_ACCESS_TOKEN = "g_access_token"
        private const val SEC_REFRESH_TOKEN = "g_refresh_token"
        private const val SEC_TOKEN_EXPIRY = "g_token_expiry"
        private const val SEC_GOOGLE_EMAIL = "g_email"
        private const val SEC_OAUTH_VERIFIER = "g_oauth_verifier"
        private const val TAG = "AppPreferences"

        const val DEFAULT_SYNC_INTERVAL = 10
        const val DEFAULT_ANNOUNCE_BEFORE = 10
        const val DEFAULT_TTS_AUDIO_STREAM = AudioManager.STREAM_MUSIC
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val securePrefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init EncryptedSharedPreferences, falling back", e)
            null
        }
    }

    private fun secureGet(key: String, default: String): String =
        (securePrefs ?: prefs).getString(key, default) ?: default

    private fun securePutString(key: String, value: String) =
        (securePrefs ?: prefs).edit().putString(key, value).apply()

    private fun securePutLong(key: String, value: Long) =
        (securePrefs ?: prefs).edit().putLong(key, value).apply()

    private fun secureGetLong(key: String, default: Long): Long =
        (securePrefs ?: prefs).getLong(key, default)

    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, DEFAULT_SYNC_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    var announceBeforeMinutes: Int
        get() = prefs.getInt(KEY_ANNOUNCE_BEFORE, DEFAULT_ANNOUNCE_BEFORE)
        set(value) = prefs.edit().putInt(KEY_ANNOUNCE_BEFORE, value).apply()

    var useGoogleCalendar: Boolean
        get() = prefs.getBoolean(KEY_USE_GOOGLE, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_GOOGLE, value).apply()

    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()

    var ttsLanguage: String
        get() = prefs.getString(KEY_TTS_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_LANGUAGE, value).apply()

    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    var ttsAudioStream: Int
        get() = prefs.getInt(KEY_TTS_AUDIO_STREAM, DEFAULT_TTS_AUDIO_STREAM)
        set(value) = prefs.edit().putInt(KEY_TTS_AUDIO_STREAM, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var disabledCalendarIds: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_CALENDAR_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISABLED_CALENDAR_IDS, value).apply()

    var googleClientId: String
        get() = prefs.getString(KEY_GOOGLE_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_CLIENT_ID, value).apply()

    var googleClientSecret: String
        get() = prefs.getString(KEY_GOOGLE_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_CLIENT_SECRET, value).apply()

    var googleAccounts: Set<String>
        get() {
            val accountsStr = secureGet("google_accounts", "")
            if (accountsStr.isEmpty()) {
                val oldEmail = secureGet(SEC_GOOGLE_EMAIL, "")
                if (oldEmail.isNotEmpty()) {
                    setGoogleAccessToken(oldEmail, secureGet(SEC_ACCESS_TOKEN, ""))
                    setGoogleRefreshToken(oldEmail, secureGet(SEC_REFRESH_TOKEN, ""))
                    setGoogleTokenExpiry(oldEmail, secureGetLong(SEC_TOKEN_EXPIRY, 0L))
                    securePutString("google_accounts", oldEmail)
                    clearOldGoogleCredentials()
                    return setOf(oldEmail)
                }
            }
            return accountsStr.split(",").filter { it.isNotEmpty() }.toSet()
        }
        set(value) = securePutString("google_accounts", value.joinToString(","))

    fun addGoogleAccount(email: String) {
        val accounts = googleAccounts.toMutableSet()
        accounts.add(email)
        googleAccounts = accounts
    }

    fun removeGoogleAccount(email: String) {
        val accounts = googleAccounts.toMutableSet()
        accounts.remove(email)
        googleAccounts = accounts
        (securePrefs ?: prefs).edit()
            .remove("g_access_token_$email")
            .remove("g_refresh_token_$email")
            .remove("g_token_expiry_$email")
            .apply()
    }

    fun getGoogleAccessToken(email: String): String = secureGet("g_access_token_$email", "")
    fun setGoogleAccessToken(email: String, value: String) = securePutString("g_access_token_$email", value)

    fun getGoogleRefreshToken(email: String): String = secureGet("g_refresh_token_$email", "")
    fun setGoogleRefreshToken(email: String, value: String) = securePutString("g_refresh_token_$email", value)

    fun getGoogleTokenExpiry(email: String): Long = secureGetLong("g_token_expiry_$email", 0L)
    fun setGoogleTokenExpiry(email: String, value: Long) = securePutLong("g_token_expiry_$email", value)

    fun isGoogleLoggedIn(): Boolean = googleAccounts.isNotEmpty()

    private fun clearOldGoogleCredentials() {
        (securePrefs ?: prefs).edit()
            .remove(SEC_ACCESS_TOKEN)
            .remove(SEC_REFRESH_TOKEN)
            .remove(SEC_TOKEN_EXPIRY)
            .remove(SEC_GOOGLE_EMAIL)
            .remove(SEC_OAUTH_VERIFIER)
            .apply()
    }
}