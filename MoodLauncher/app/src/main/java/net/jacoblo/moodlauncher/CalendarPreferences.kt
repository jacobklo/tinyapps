package net.jacoblo.moodlauncher

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

class CalendarPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("calendar_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FONT_COLOR = "font_color"
        private const val KEY_BG_COLOR = "bg_color"
        private const val KEY_TEXT_SCALE = "text_scale"
    }

    fun getFontColor(): Color =
        Color(prefs.getInt(KEY_FONT_COLOR, Color.Black.toArgb()))

    fun getBackgroundColor(): Color =
        Color(prefs.getInt(KEY_BG_COLOR, Color.White.toArgb()))

    fun setFontColor(color: Color) =
        prefs.edit().putInt(KEY_FONT_COLOR, color.toArgb()).apply()

    fun setBackgroundColor(color: Color) =
        prefs.edit().putInt(KEY_BG_COLOR, color.toArgb()).apply()

    fun getTextScale(): Float = prefs.getFloat(KEY_TEXT_SCALE, 1.0f)

    fun setTextScale(scale: Float) =
        prefs.edit().putFloat(KEY_TEXT_SCALE, scale).apply()
}
