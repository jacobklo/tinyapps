package net.jacoblo.simpleanki

import android.os.Bundle
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
import net.jacoblo.simpleanki.data.HistoryEntry
import net.jacoblo.simpleanki.data.ViewsRepository
import net.jacoblo.simpleanki.data.recordAnswer
import net.jacoblo.simpleanki.table.TableScreen
import net.jacoblo.simpleanki.testmode.TestMode
import net.jacoblo.simpleanki.ui.theme.SimpleAnkiTheme
import java.io.IOException

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 8) Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val testMode = TestMode.isActive(this)
        val paths = if (testMode) AnkiPaths.testMode() else AnkiPaths.production()
        container = AppContainer(this, paths, testMode)
        container.seedTestModeIfNeeded()
        setContent {
            SimpleAnkiTheme {
                AnkiScreen(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ahead of the composable's ON_RESUME observer, which is what reads the files.
        container.seedTestModeIfNeeded()
        // 1) Check file permission on launch
        if (!container.hasStorageAccess) promptForStorageAccess()
    }

    override fun onDestroy() {
        container.release()
        super.onDestroy()
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

    // Navigation state, plus the stored views the drawer is built from.
    var currentScreen by remember { mutableStateOf<Screen>(Screen.FlipCards) }
    var viewsFile by remember { mutableStateOf(ViewsRepository.defaults(container.settings.table)) }
    val views = viewsFile.views
    val deckQuestions = remember(cards) { cards.map { it.question }.toSet() }

    // Watch lifecycle to reload cards when permission granted
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Never throws. Seeds settings.json from the retired stats.json on first
                // run, and retries here on the resume after permission is granted.
                container.settings = container.settingsRepository.load()
                // Also never throws; creates views.json on first run.
                viewsFile = container.viewsRepository.load(container.settings.table)
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
                is Screen.Table -> {
                    // A stored id naming no view falls back to the first, and re-points
                    // the selection so the drawer highlights what is actually showing.
                    val view = views.firstOrNull { it.id == screen.viewId } ?: views.firstOrNull()
                    LaunchedEffect(view?.id) {
                        if (view != null && view.id != screen.viewId) currentScreen = Screen.Table(view.id)
                    }
                    if (view == null) Text("No views to show.")
                    else TableScreen(history, deckQuestions, view, onViewChanged = { changed ->
                        // A header drag rebuilt the view; store it under the same id.
                        val updated = viewsFile.copy(
                            views = views.map { if (it.id == changed.id) changed else it }
                        )
                        viewsFile = updated
                        try {
                            container.viewsRepository.save(updated)
                        } catch (e: IOException) {
                            Toast.makeText(context, "Could not save views.json - check file permission or free space", Toast.LENGTH_SHORT).show()
                        }
                    }, onRendered = container::dumpRendered)
                }
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
                        val cap = container.settings.history.maxEntries
                        // Both writes happen on every answer, so either can throw here.
                        try {
                            val recorded = recordAnswer(
                                container.historyRepository, container.settingsRepository,
                                container.settings, history, entry, cap
                            )
                            history = recorded.history
                            container.settings = recorded.settings
                        } catch (e: IOException) {
                            // Appended even on failure. The history write runs first and
                            // may well have succeeded; a list left BEHIND disk gets written
                            // back on the next flip and silently drops what it is missing,
                            // while a list ahead of disk is caught up by the next write.
                            history = (history + entry).takeLast(cap)
                            Toast.makeText(context, "Could not save your progress: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        isShowingAnswer = true
                    }
                )
            }
        }
    }
}
