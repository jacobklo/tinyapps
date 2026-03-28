package net.jacoblo.calendarannouncement

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar

class GoogleCalendarClient(
    private val context: Context,
    private val prefs: AppPreferences
) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/calendar.readonly " +
            "https://www.googleapis.com/auth/userinfo.email"
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val CALENDAR_LIST_URL = "https://www.googleapis.com/calendar/v3/users/me/calendarList"
        const val EVENTS_BASE_URL = "https://www.googleapis.com/calendar/v3/calendars"
        const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
        private const val TAG = "GoogleCalendarClient"

        // Loopback port used for OAuth redirect (Desktop app credentials allow this automatically)
        private const val LOOPBACK_PORT = 0 // 0 = OS picks an available port
    }

    /**
     * Starts the OAuth2 PKCE flow using a local loopback HTTP server.
     * Requires "Desktop app" credentials from Google Cloud Console.
     * onComplete is called on a background thread - post to UI if needed.
     */
    fun startAuthFlow(onComplete: (success: Boolean) -> Unit) {
        val clientId = prefs.googleClientId
        if (clientId.isBlank()) {
            Log.e(TAG, "Client ID not set")
            onComplete(false)
            return
        }

        Thread {
            try {
                val verifier = generateCodeVerifier()
                val challenge = generateCodeChallenge(verifier)

                // OS picks an available port; loopback is auto-allowed for Desktop app credentials
                val serverSocket = ServerSocket(LOOPBACK_PORT)
                val port = serverSocket.localPort
                val redirectUri = "http://127.0.0.1:$port/callback"

                val authUrl = buildString {
                    append(AUTH_URL)
                    append("?client_id=").append(enc(clientId))
                    append("&redirect_uri=").append(enc(redirectUri))
                    append("&response_type=code")
                    append("&scope=").append(enc(SCOPE))
                    append("&code_challenge=").append(challenge)
                    append("&code_challenge_method=S256")
                    append("&access_type=offline")
                    append("&prompt=consent")
                }

                // Open browser
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(browserIntent)

                // Wait for redirect (3-minute timeout)
                serverSocket.soTimeout = 180_000
                val socket = serverSocket.accept()

                // Read first line of the HTTP request: "GET /callback?code=xxx HTTP/1.1"
                val requestLine = socket.getInputStream().bufferedReader().readLine() ?: ""

                // Send a user-friendly HTML response
                val html = "<html><body style='font-family:sans-serif;text-align:center;margin-top:60px'>" +
                    "<h2>Authentication complete!</h2>" +
                    "<p>You can close this tab and return to the app.</p>" +
                    "</body></html>"
                
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                    "Content-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                socket.getOutputStream().write(response.toByteArray())
                socket.close()
                serverSocket.close()

                // Extract code from "GET /callback?code=XXX&... HTTP/1.1"
                val code = Regex("[?&]code=([^&\\s]+)").find(requestLine)
                    ?.groupValues?.get(1)
                    ?.let { URLDecoder.decode(it, "UTF-8") }

                if (code != null) {
                    onComplete(exchangeCodeForToken(code, redirectUri, verifier, clientId))
                } else {
                    Log.e(TAG, "No code in redirect: $requestLine")
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auth flow error", e)
                onComplete(false)
            }
        }.start()
    }

    private fun exchangeCodeForToken(
        code: String,
        redirectUri: String,
        verifier: String,
        clientId: String
    ): Boolean {
        val clientSecret = prefs.googleClientSecret
        return try {
            val params = buildString {
                append("code=").append(enc(code))
                append("&client_id=").append(enc(clientId))
                if (clientSecret.isNotBlank()) append("&client_secret=").append(enc(clientSecret))
                append("&redirect_uri=").append(enc(redirectUri))
                append("&grant_type=authorization_code")
                append("&code_verifier=").append(enc(verifier))
            }
            val json = JSONObject(httpPost(TOKEN_URL, params))
            val accessToken = json.optString("access_token", "")
            val refreshToken = json.optString("refresh_token", "")
            val expiresIn = json.optInt("expires_in", 3600)
            if (accessToken.isEmpty()) {
                Log.e(TAG, "Token response missing access_token: $json")
                return false
            }
            val email = fetchEmail(accessToken)
            if (email.isNotEmpty()) {
                prefs.addGoogleAccount(email)
                prefs.setGoogleAccessToken(email, accessToken)
                if (refreshToken.isNotEmpty()) prefs.setGoogleRefreshToken(email, refreshToken)
                prefs.setGoogleTokenExpiry(email, System.currentTimeMillis() + expiresIn * 1000L)
                true
            } else {
                Log.e(TAG, "Could not fetch email for new token")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            false
        }
    }

    private fun fetchEmail(token: String): String {
        return try {
            val json = JSONObject(httpGet(USERINFO_URL, token))
            json.optString("email", "")
        } catch (e: Exception) {
            Log.e(TAG, "Fetch email error", e)
            ""
        }
    }

    fun getValidAccessToken(email: String): String? {
        if (System.currentTimeMillis() < prefs.getGoogleTokenExpiry(email) - 60_000L) {
            val token = prefs.getGoogleAccessToken(email)
            if (token.isNotEmpty()) return token
        }
        return refreshAccessToken(email)
    }

    private fun refreshAccessToken(email: String): String? {
        val refreshToken = prefs.getGoogleRefreshToken(email).ifEmpty { return null }
        val clientId = prefs.googleClientId.ifEmpty { return null }
        val clientSecret = prefs.googleClientSecret
        return try {
            val params = buildString {
                append("refresh_token=").append(enc(refreshToken))
                append("&client_id=").append(enc(clientId))
                if (clientSecret.isNotBlank()) append("&client_secret=").append(enc(clientSecret))
                append("&grant_type=refresh_token")
            }
            val json = JSONObject(httpPost(TOKEN_URL, params))
            val newToken = json.optString("access_token", "").ifEmpty { return null }
            val expiresIn = json.optInt("expires_in", 3600)
            prefs.setGoogleAccessToken(email, newToken)
            prefs.setGoogleTokenExpiry(email, System.currentTimeMillis() + expiresIn * 1000L)
            newToken
        } catch (e: Exception) {
            Log.e(TAG, "Refresh token error for $email", e)
            null
        }
    }

    fun getCalendarList(): List<CalendarAccount> {
        val allCalendars = mutableListOf<CalendarAccount>()
        val accounts = prefs.googleAccounts
        for (email in accounts) {
            val token = getValidAccessToken(email) ?: continue
            try {
                val json = JSONObject(httpGet(CALENDAR_LIST_URL, token))
                val items = json.optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    allCalendars.add(
                        CalendarAccount(
                            id = "google:${item.optString("id", "")}",
                            name = item.optString("summary", item.optString("id", "")),
                            accountName = email,
                            accountType = "google",
                            color = 0xFF4285F4.toInt()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get calendar list error for $email", e)
            }
        }
        return allCalendars
    }

    fun getTodayEvents(disabledCalendarIds: Set<String>): List<CalendarEvent> {
        val allEvents = mutableListOf<CalendarEvent>()

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }
        val timeMin = formatIso8601(startOfDay.timeInMillis)
        val timeMax = formatIso8601(endOfDay.timeInMillis)

        val accounts = prefs.googleAccounts
        for (email in accounts) {
            val token = getValidAccessToken(email) ?: continue
            
            // Get calendars for this specific account
            val calendars = try {
                val json = JSONObject(httpGet(CALENDAR_LIST_URL, token))
                val items = json.optJSONArray("items") ?: continue
                (0 until items.length()).map { i ->
                    val item = items.getJSONObject(i)
                    CalendarAccount(
                        id = "google:${item.optString("id", "")}",
                        name = item.optString("summary", item.optString("id", "")),
                        accountName = email,
                        accountType = "google",
                        color = 0xFF4285F4.toInt()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get calendar list error for $email", e)
                emptyList()
            }

            for (cal in calendars) {
                if (disabledCalendarIds.contains(cal.id)) continue
                val rawId = cal.id.removePrefix("google:")
                try {
                    val url = "$EVENTS_BASE_URL/${enc(rawId)}/events" +
                        "?timeMin=${enc(timeMin)}&timeMax=${enc(timeMax)}" +
                        "&singleEvents=true&orderBy=startTime"
                    val json = JSONObject(httpGet(url, token))
                    val items = json.optJSONArray("items") ?: continue
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val startObj = item.optJSONObject("start") ?: continue
                        val endObj = item.optJSONObject("end") ?: continue
                        val startStr = startObj.optString("dateTime", "").ifEmpty { startObj.optString("date", "") }
                        val endStr = endObj.optString("dateTime", "").ifEmpty { endObj.optString("date", "") }
                        val startMs = parseIso8601(startStr) ?: continue
                        val endMs = parseIso8601(endStr) ?: continue
                        allEvents.add(
                            CalendarEvent(
                                id = "google:${item.optString("id", "")}",
                                title = item.optString("summary", "(No title)"),
                                startTime = startMs,
                                endTime = endMs,
                                calendarId = cal.id,
                                calendarName = cal.name,
                                accountName = email
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching events for ${cal.id}", e)
                }
            }
        }
        return allEvents.sortedBy { it.startTime }
    }

    private fun formatIso8601(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        val offsetMs = cal.timeZone.getOffset(millis)
        val sign = if (offsetMs >= 0) "+" else "-"
        val abs = Math.abs(offsetMs)
        return "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
            sign, abs / 3600000, (abs % 3600000) / 60000
        )
    }

    private fun parseIso8601(iso: String): Long? {
        if (iso.isEmpty()) return null
        return try {
            if (iso.length == 10) {
                val p = iso.split("-")
                Calendar.getInstance().apply {
                    set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt(), 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } else {
                val norm = if (iso.endsWith("Z")) iso.dropLast(1) + "+00:00" else iso
                val dp = norm.substring(0, 10).split("-")
                val tp = norm.substring(11, 19).split(":")
                val tz = norm.substring(19)
                val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.set(dp[0].toInt(), dp[1].toInt() - 1, dp[2].toInt(),
                    tp[0].toInt(), tp[1].toInt(), tp[2].toInt())
                cal.set(Calendar.MILLISECOND, 0)
                var utc = cal.timeInMillis
                if (tz.length >= 6) {
                    val sign = if (tz[0] == '+') -1 else 1
                    val tzp = tz.substring(1).split(":")
                    utc += sign * (tzp[0].toInt() * 3600 + tzp[1].toInt() * 60) * 1000L
                }
                utc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse ISO error: $iso", e)
            null
        }
    }

    private fun httpGet(urlStr: String, token: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlStr: String, params: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(params) }
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}