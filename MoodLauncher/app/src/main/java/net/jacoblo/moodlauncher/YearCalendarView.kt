package net.jacoblo.moodlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

private val DAY_HEADERS = listOf("S", "M", "T", "W", "T", "F", "S")

@Composable
fun YearCalendar(
    notes: Map<String, DayNote> = emptyMap(),
    onDayClick: (LocalDate) -> Unit = {},
    fontColor: Color = Color.Black,
    backgroundColor: Color = Color.White,
    textScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val today = remember { LocalDate.now() }
    val year = today.year

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Year title
        Text(
            text = year.toString(),
            fontSize = (22f * textScale).sp,
            fontWeight = FontWeight.Thin,
            fontFamily = FontFamily.SansSerif,
            color = fontColor,
            textAlign = TextAlign.Center,
            letterSpacing = 4.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        )

        // 4 rows × 3 columns
        for (row in 0..3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (col in 0..2) {
                    MonthBox(
                        year = year,
                        month = row * 3 + col + 1,
                        today = today,
                        notes = notes,
                        onDayClick = onDayClick,
                        fontColor = fontColor,
                        textScale = textScale,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
            if (row < 3) Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MonthBox(
    year: Int,
    month: Int,
    today: LocalDate,
    notes: Map<String, DayNote>,
    onDayClick: (LocalDate) -> Unit,
    fontColor: Color,
    textScale: Float,
    modifier: Modifier = Modifier
) {
    val firstDay = remember(year, month) { LocalDate.of(year, month, 1) }
    val daysInMonth = remember(year, month) { firstDay.lengthOfMonth() }
    // Java DayOfWeek: MON=1…SUN=7 → SUN=0, MON=1…SAT=6
    val startOffset = remember(year, month) { firstDay.dayOfWeek.value % 7 }
    val monthName = remember(month) {
        Month.of(month)
            .getDisplayName(TextStyle.SHORT_STANDALONE, Locale.ENGLISH)
            .uppercase()
    }
    // 42 cells = 6 weeks, uniform across all months
    val cells = remember(year, month) {
        Array(42) { i ->
            val day = i - startOffset + 1
            if (day in 1..daysInMonth) day else null
        }
    }

    Column(modifier = modifier) {
        // Month name
        Text(
            text = monthName,
            fontSize = (9f * textScale).sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.SansSerif,
            color = fontColor,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
        )

        // Weekday headers
        Row(modifier = Modifier.fillMaxWidth()) {
            DAY_HEADERS.forEach { h ->
                Text(
                    text = h,
                    fontSize = (7f * textScale).sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif,
                    color = fontColor.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 6 week rows
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            for (week in 0..5) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (dow in 0..6) {
                        val day = cells[week * 7 + dow]
                        val date = if (day != null) LocalDate.of(year, month, day) else null
                        val isToday = date != null && date == today
                        val noteKey = date?.toNoteKey()
                        val emoji = if (noteKey != null) notes[noteKey]?.emoji ?: "" else ""
                        val hasEmoji = emoji.isNotBlank()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(
                                    if (date != null) Modifier.clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { onDayClick(date) } else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (day != null) {
                                when {
                                    isToday -> {
                                        // Red circle with number or emoji
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .background(Color.Red, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (hasEmoji) {
                                                Text(
                                                    text = emoji,
                                                    fontSize = (9f * textScale).sp,
                                                    textAlign = TextAlign.Center
                                                )
                                            } else {
                                                Text(
                                                    text = day.toString(),
                                                    fontSize = (7f * textScale).sp,
                                                    fontWeight = FontWeight.Medium,
                                                    fontFamily = FontFamily.SansSerif,
                                                    color = Color.White,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                    hasEmoji -> {
                                        // Emoji replaces the day number
                                        Text(
                                            text = emoji,
                                            fontSize = (9f * textScale).sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = day.toString(),
                                            fontSize = (7f * textScale).sp,
                                            fontWeight = FontWeight.Light,
                                            fontFamily = FontFamily.SansSerif,
                                            color = fontColor,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
