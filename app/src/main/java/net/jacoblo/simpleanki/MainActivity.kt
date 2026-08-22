package net.jacoblo.simpleanki

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.jacoblo.simpleanki.data.*
import net.jacoblo.simpleanki.ui.theme.SimpleAnkiTheme
import java.io.IOException

enum class Screen { HOME, STATS, HISTORY, QUESTIONS }

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 8) Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Task 7 wires test mode activation; production paths until then.
        container = AppContainer(this, AnkiPaths.production(), testMode = false)
        setContent {
            SimpleAnkiTheme {
                AnkiScreen(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 1) Check file permission on launch
        checkPermission()
    }

    override fun onDestroy() {
        container.release()
        super.onDestroy()
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!container.hasStorageAccess) {
                Toast.makeText(this, "Please allow file access to load questions", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiScreen(container: AppContainer) {
    val context = LocalContext.current
    var cards by remember { mutableStateOf<List<AnkiCard>>(emptyList()) }
    var currentCardIndex by remember { mutableStateOf(-1) }
    var isShowingAnswer by remember { mutableStateOf(false) }
    var currentRoundTime by remember { mutableStateOf(0f) }
    var startTime by remember { mutableStateOf(0L) }
    // History log, now the only source of every per-card figure
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }

    // Navigation state
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    // Watch lifecycle to reload cards when permission granted
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Taking a deck starts the clock on a random card.
                fun take(deck: List<AnkiCard>) {
                    cards = deck
                    currentCardIndex = deck.indices.random()
                    startTime = System.currentTimeMillis()
                }
                // 2) Read cards
                val loaded = container.deckRepository.load()
                if (loaded.isNotEmpty()) {
                    if (cards.isEmpty()) take(loaded)
                } else if (container.hasStorageAccess) {
                    Toast.makeText(context, "Creating sample simple-anki.json", Toast.LENGTH_LONG).show()
                    // createSample propagates IOException; unhandled that is a crash on resume.
                    try {
                        container.deckRepository.createSample()
                    } catch (e: IOException) {
                        Toast.makeText(context, "Could not write simple-anki.json", Toast.LENGTH_LONG).show()
                    }
                    val reloaded = container.deckRepository.load()
                    if (reloaded.isNotEmpty()) take(reloaded)
                }
                // load() rewrites the file when it migrates, so it can throw as well.
                try {
                    history = container.historyRepository.load()
                } catch (e: IOException) {
                    Toast.makeText(context, "Could not migrate history.json", Toast.LENGTH_LONG).show()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Simple Anki") },
                actions = {
                    // 6.4) Navigation Icons
                    IconButton(onClick = { currentScreen = Screen.HOME }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { currentScreen = Screen.STATS }) {
                        Icon(Icons.Default.List, contentDescription = "Stats")
                    }
                    IconButton(onClick = { currentScreen = Screen.HISTORY }) {
                        Icon(Icons.Default.DateRange, contentDescription = "History")
                    }
                    IconButton(onClick = { currentScreen = Screen.QUESTIONS }) {
                        Icon(Icons.Default.Style, contentDescription = "Questions")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                // 6) Stats Page
                Screen.STATS -> StatsScreen(history, cards.map { it.question })
                Screen.HISTORY -> HistoryScreen(history)
                Screen.QUESTIONS -> QuestionsScreen(history)
                // Game Screen
                Screen.HOME -> {
                    val question = cards.getOrNull(currentCardIndex)?.question
                    GameView(
                        cards = cards,
                        currentCardIndex = currentCardIndex,
                        isShowingAnswer = isShowingAnswer,
                        summary = remember(history, question) {
                            question?.let { summarize(history, it) } ?: CardSummary(null, null)
                        },
                        currentRoundTime = currentRoundTime,
                        onNextCard = {
                            isShowingAnswer = false
                            currentCardIndex = cards.indices.random()
                            startTime = System.currentTimeMillis()
                        },
                        onFlip = {
                            val now = System.currentTimeMillis()
                            val timeTaken = (now - startTime) / 1000f
                            currentRoundTime = timeTaken
                            val card = cards[currentCardIndex]
                            // Task 14 sets timedOut when the metronome interval elapses.
                            val entry = HistoryEntry(card.question, card.answer, timeTaken, now, false)
                            // append writes on every answer, so it can throw here too.
                            try {
                                // Task 8 replaces the literal with Settings.history.maxEntries.
                                history = container.historyRepository.append(entry, 5000)
                            } catch (e: IOException) {
                                Toast.makeText(context, "Could not save history.json", Toast.LENGTH_SHORT).show()
                            }
                            isShowingAnswer = true
                        }
                    )
                }
            }
        }
    }
}
