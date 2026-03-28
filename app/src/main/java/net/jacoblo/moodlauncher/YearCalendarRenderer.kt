package net.jacoblo.moodlauncher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private val DAY_HEADERS_RENDERER = arrayOf("S", "M", "T", "W", "T", "F", "S")

class YearCalendarRenderer(private val density: Float) {

    private fun dp(v: Float) = v * density

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        notes: Map<String, DayNote>,
        fontColorArgb: Int,
        backgroundColorArgb: Int,
        textScale: Float
    ) {
        val today = LocalDate.now()
        val year = today.year

        canvas.drawColor(backgroundColorArgb)

        val padH = dp(14f)
        val padV = dp(10f)
        val innerWidth = width - 2 * padH

        // Year title
        val titlePaint = makePaint(dp(22f) * textScale, fontColorArgb, Typeface.NORMAL).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.2f
        }
        val titleH = titlePaint.descent() - titlePaint.ascent()
        val titleBottomPad = dp(10f)
        canvas.drawText(year.toString(), width / 2f, padV - titlePaint.ascent(), titlePaint)

        val gridTop = padV + titleH + titleBottomPad
        val gridH = height - padV - gridTop

        val rowSpacing = dp(10f)
        val colSpacing = dp(10f)
        val rowHeight = (gridH - 3 * rowSpacing) / 4f
        val colWidth = (innerWidth - 2 * colSpacing) / 3f

        for (row in 0..3) {
            for (col in 0..2) {
                val monthIndex = row * 3 + col + 1
                val mLeft = padH + col * (colWidth + colSpacing)
                val mTop = gridTop + row * (rowHeight + rowSpacing)
                drawMonth(
                    canvas, year, monthIndex, today, notes,
                    mLeft, mTop, colWidth, rowHeight,
                    fontColorArgb, textScale
                )
            }
        }
    }

    private fun drawMonth(
        canvas: Canvas,
        year: Int,
        month: Int,
        today: LocalDate,
        notes: Map<String, DayNote>,
        left: Float, top: Float, width: Float, height: Float,
        fontColorArgb: Int, textScale: Float
    ) {
        val firstDay = LocalDate.of(year, month, 1)
        val daysInMonth = firstDay.lengthOfMonth()
        // Java DayOfWeek: MON=1..SUN=7 -> SUN=0, MON=1..SAT=6
        val startOffset = firstDay.dayOfWeek.value % 7
        val monthName = Month.of(month)
            .getDisplayName(TextStyle.SHORT_STANDALONE, Locale.ENGLISH)
            .uppercase()

        val cells = Array(42) { i ->
            val d = i - startOffset + 1
            if (d in 1..daysInMonth) d else null
        }

        // Month name
        val namePaint = makePaint(dp(9f) * textScale, fontColorArgb, Typeface.BOLD).apply {
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.1f
        }
        val nameH = namePaint.descent() - namePaint.ascent()
        canvas.drawText(monthName, left + width / 2f, top - namePaint.ascent(), namePaint)

        var curY = top + nameH + dp(2f)

        // Day headers
        val headerAlpha = (255 * 0.45f).toInt()
        val headerColor = (fontColorArgb and 0x00FFFFFF) or (headerAlpha shl 24)
        val headerPaint = makePaint(dp(7f) * textScale, headerColor, Typeface.NORMAL).apply {
            textAlign = Paint.Align.CENTER
        }
        val headerH = headerPaint.descent() - headerPaint.ascent()
        val cellW = width / 7f
        for (i in 0..6) {
            canvas.drawText(
                DAY_HEADERS_RENDERER[i],
                left + i * cellW + cellW / 2f,
                curY - headerPaint.ascent(),
                headerPaint
            )
        }
        curY += headerH

        // 6 week rows
        val weekH = (height - (curY - top)) / 6f

        val dayPaint = makePaint(dp(7f) * textScale, fontColorArgb, Typeface.NORMAL).apply {
            textAlign = Paint.Align.CENTER
        }
        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(9f) * textScale
            textAlign = Paint.Align.CENTER
        }
        val todayCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        val todayDayPaint = makePaint(dp(7f) * textScale, Color.WHITE, Typeface.NORMAL).apply {
            textAlign = Paint.Align.CENTER
        }
        val circleRadius = dp(8f)

        for (week in 0..5) {
            for (dow in 0..6) {
                val day = cells[week * 7 + dow] ?: continue
                val date = LocalDate.of(year, month, day)
                val isToday = date == today
                val emoji = notes[date.toNoteKey()]?.emoji ?: ""
                val hasEmoji = emoji.isNotBlank()

                val cx = left + dow * cellW + cellW / 2f
                val cy = curY + week * weekH + weekH / 2f

                when {
                    isToday -> {
                        canvas.drawCircle(cx, cy, circleRadius, todayCirclePaint)
                        if (hasEmoji) {
                            canvas.drawText(
                                emoji, cx,
                                cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f,
                                emojiPaint
                            )
                        } else {
                            canvas.drawText(
                                day.toString(), cx,
                                cy - (todayDayPaint.descent() + todayDayPaint.ascent()) / 2f,
                                todayDayPaint
                            )
                        }
                    }
                    hasEmoji -> {
                        canvas.drawText(
                            emoji, cx,
                            cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2f,
                            emojiPaint
                        )
                    }
                    else -> {
                        canvas.drawText(
                            day.toString(), cx,
                            cy - (dayPaint.descent() + dayPaint.ascent()) / 2f,
                            dayPaint
                        )
                    }
                }
            }
        }
    }

    private fun makePaint(textSize: Float, color: Int, style: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, style)
        }
}
