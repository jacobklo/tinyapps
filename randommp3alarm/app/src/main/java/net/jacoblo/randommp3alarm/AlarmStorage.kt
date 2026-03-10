package net.jacoblo.randommp3alarm

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray

object AlarmStorage {
    private const val PREFS = "alarm_prefs"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_STREAM = "audio_stream"

    fun saveAlarms(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray()
        alarms.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALARMS, arr.toString()).apply()
    }

    fun loadAlarms(context: Context): MutableList<Alarm> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ALARMS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveAudioStream(context: Context, stream: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_STREAM, stream).apply()
    }

    fun loadAudioStream(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_STREAM, AudioManager.STREAM_ALARM)
}
