package net.jacoblo.moodlauncher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import net.jacoblo.moodlauncher.ui.theme.MoodLauncherTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()

        setContent {
            MoodLauncherTheme {
                HomeScreen(onSwipeUp = { openAppLauncher() })
            }
        }
    }

    private fun openAppLauncher() {
        startActivity(Intent(this, AppLauncherActivity::class.java))
    }
}

@Composable
fun HomeScreen(onSwipeUp: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { NotesRepository(context) }
    val calendarPrefs = remember { CalendarPreferences(context) }
    val scope = rememberCoroutineScope()

    val keyguardManager = remember {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }
    var isLocked by remember { mutableStateOf(keyguardManager.isKeyguardLocked) }

    // Swallow back press only when unlocked (home screen behaviour).
    // When locked, back falls through to the system → keyguard password screen appears.
    BackHandler(enabled = !isLocked) { /* do nothing — home screen stays */ }

    var notes by remember { mutableStateOf(mapOf<String, DayNote>()) }
    var dialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var totalDrag by remember { mutableFloatStateOf(0f) }
    var fontColor by remember { mutableStateOf(calendarPrefs.getFontColor()) }
    var backgroundColor by remember { mutableStateOf(calendarPrefs.getBackgroundColor()) }
    var textScale by remember { mutableFloatStateOf(calendarPrefs.getTextScale()) }

    // Load persisted notes on first composition
    LaunchedEffect(Unit) {
        notes = repository.loadNotes()
    }

    // Track keyguard state via broadcasts
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                isLocked = keyguardManager.isKeyguardLocked
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)   // user unlocked
            addAction(Intent.ACTION_SCREEN_OFF)     // screen off → locked
            addAction(Intent.ACTION_SCREEN_ON)      // screen on, re-check
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Reload colors/scale whenever the screen resumes
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fontColor = calendarPrefs.getFontColor()
                backgroundColor = calendarPrefs.getBackgroundColor()
                textScale = calendarPrefs.getTextScale()
                isLocked = keyguardManager.isKeyguardLocked
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .pointerInput(isLocked) {
                if (!isLocked) {
                    detectVerticalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            if (totalDrag < -150f) onSwipeUp()
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                }
            }
            .systemBarsPadding()
    ) {
        YearCalendar(
            notes = notes,
            onDayClick = { date -> if (!isLocked) dialogDate = date },
            fontColor = fontColor,
            backgroundColor = backgroundColor,
            textScale = textScale
        )
    }

    // Day-edit dialog — only when unlocked
    if (!isLocked) {
        dialogDate?.let { date ->
            DayEditDialog(
                date = date,
                existing = notes[date.toNoteKey()],
                onSave = { note ->
                    scope.launch {
                        repository.saveNote(date.toNoteKey(), note)
                        notes = notes.toMutableMap().also { it[date.toNoteKey()] = note }
                    }
                    dialogDate = null
                },
                onDismiss = { dialogDate = null }
            )
        }
    }
}
