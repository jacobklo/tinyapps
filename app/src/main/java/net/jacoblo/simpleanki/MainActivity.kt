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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.jacoblo.simpleanki.data.AnkiCard
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DefaultViews
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.recordAnswer
import net.jacoblo.simpleanki.table.TableScreen
import net.jacoblo.simpleanki.ui.theme.SimpleAnkiTheme
import java.io.IOException

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

    // Navigation state. Task 8 swaps DefaultViews for the list stored in views.json.
    var currentScreen by remember { mutableStateOf<Screen>(Screen.FlipCards) }
    val views = remember(container.settings.table) { DefaultViews.all(container.settings.table) }
    val deckQuestions = remember(cards) { cards.map { it.question }.toSet() }

    // Watch lifecycle to reload cards when permission granted
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Never throws. Seeds settings.json from the retired stats.json on first
                // run, and retries here on the resume after permission is granted.
                container.settings = container.settingsRepository.load()
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
                        Toast.makeText(context, "Could not create simple-anki.json - check file permission or free space", Toast.LENGTH_LONG).show()
                    }
                    val reloaded = container.deckRepository.load()
                    if (reloaded.isNotEmpty()) take(reloaded)
                }
                // load() rewrites the file when it migrates, so it can throw as well.
                try {
                    history = container.historyRepository.load()
                } catch (e: IOException) {
                    Toast.makeText(context, "Could not update history.json - check file permission or free space", Toast.LENGTH_LONG).show()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AnkiNavShell(
        lifetimeReviews = container.settings.counters.lifetimeReviews,
        views = views,
        current = currentScreen,
        onSelect = { currentScreen = it }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val screen = currentScreen) {
                // 6) Every table view, including the retired stats and history pages.
                // A view id with no view left behind it falls back to the first view.
                is Screen.Table -> TableScreen(
                    history, deckQuestions,
                    views.firstOrNull { it.id == screen.viewId } ?: views.first(),
                    onViewChanged = {}, onRendered = {}
                )
                Screen.FlipCards -> GameView(
                    cards = cards,
                    currentCardIndex = currentCardIndex,
                    isShowingAnswer = isShowingAnswer,
                    summary = remember(history, cards, currentCardIndex) {
                        summarizeCard(history, cards, currentCardIndex)
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
                        // Both writes happen on every answer, so either can throw here.
                        try {
                            // Task 8 replaces the literal with Settings.history.maxEntries.
                            val recorded = recordAnswer(
                                container.historyRepository, container.settingsRepository,
                                container.settings, entry, 5000
                            )
                            history = recorded.history
                            container.settings = recorded.settings
                        } catch (e: IOException) {
                            Toast.makeText(context, "Could not save history.json or settings.json - check file permission or free space", Toast.LENGTH_SHORT).show()
                        }
                        isShowingAnswer = true
                    }
                )
            }
        }
    }
}
