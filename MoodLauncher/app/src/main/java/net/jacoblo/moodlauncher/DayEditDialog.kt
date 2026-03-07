package net.jacoblo.moodlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.BreakIterator
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")

@Composable
fun DayEditDialog(
    date: LocalDate,
    existing: DayNote?,
    onSave: (DayNote) -> Unit,
    onDismiss: () -> Unit
) {
    var emoji by remember { mutableStateOf(existing?.emoji ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Save button at the top ────────────────────────────────────────
            Button(
                onClick = { onSave(DayNote(emoji = emoji, notes = notes)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
            ) {
                Text(
                    "Save",
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }

            // ── Date label ────────────────────────────────────────────────────
            Text(
                text = date.format(dateFormatter),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
                color = Color.Black
            )

            // ── Emoji field ───────────────────────────────────────────────────
            FieldLabel("Emoji (tap and pick — max 1)")
            BasicTextField(
                value = emoji,
                onValueChange = { raw ->
                    // Allow at most 1 grapheme cluster (handles multi-codepoint emoji)
                    emoji = raw.trimToOneGraphemeCluster()
                },
                textStyle = TextStyle(
                    fontSize = 28.sp,
                    fontFamily = FontFamily.SansSerif,
                    color = Color.Black
                ),
                cursorBrush = SolidColor(Color.Black),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )

            // ── Notes field ───────────────────────────────────────────────────
            FieldLabel("Notes")
            BasicTextField(
                value = notes,
                onValueChange = { notes = it },
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif,
                    color = Color.Black,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(Color.Black),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (notes.isEmpty()) {
                            Text(
                                "Write something about this day…",
                                fontSize = 13.sp,
                                color = Color(0xFFAAAAAA),
                                fontFamily = FontFamily.SansSerif
                            )
                        }
                        inner()
                    }
                }
            )

            Spacer(modifier = Modifier.height(0.dp))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
        color = Color(0xFF666666),
        letterSpacing = 0.3.sp
    )
}

/**
 * Trims [this] string to at most one Unicode grapheme cluster.
 * Handles multi-codepoint emoji (e.g. 🏳️‍🌈, 👨‍👩‍👧).
 */
fun String.trimToOneGraphemeCluster(): String {
    if (isEmpty()) return this
    val it = BreakIterator.getCharacterInstance()
    it.setText(this)
    val end = it.next()
    return if (end == BreakIterator.DONE) this else substring(0, end)
}
