package net.jacoblo.simpleanki

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
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
import net.jacoblo.simpleanki.metronome.MetronomeEffect
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
    // Bumped on every draw; see cardKey below for why the index alone will not do.
    var cardDraw by remember { mutableStateOf(0) }
    // History log, now the only source of every per-card figure
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }

    // Navigation state, plus the stored views the drawer is built from.
    var currentScreen by remember { mutableStateOf<Screen>(Screen.FlipCards) }
    var viewsFile by remember { mutableStateOf(ViewsRepository.defaults(container.settings.table)) }
    // The column sheet lives on the table screen but is opened from the top bar, above it.
    var sheetOpen by remember { mutableStateOf(false) }
    // The metronome's foreground gate. Composition survives backgrounding, so nothing but
    // the lifecycle observer below can tell the countdown the app went away.
    var isResumed by remember { mutableStateOf(false) }

    // Every card change goes through here, so every card starts hidden with a fresh clock.
    fun drawCard() {
        if (cards.isEmpty()) return
        isShowingAnswer = false
        currentCardIndex = cards.indices.random()
        cardDraw++
        startTime = System.currentTimeMillis()
    }

    // Watch lifecycle to reload cards when permission granted
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) isResumed = false
            if (event == Lifecycle.Event.ON_RESUME) {
                isResumed = true
                // Never throws. Seeds settings.json from the retired stats.json on first
                // run, and retries here on the resume after permission is granted.
                container.settings = container.settingsRepository.load()
                // Also never throws; creates views.json on first run.
                viewsFile = container.viewsRepository.load(container.settings.table)
                // 2) Read cards
                val loaded = container.deckRepository.load()
                if (loaded.isNotEmpty()) {
                    if (cards.isEmpty()) { cards = loaded; drawCard() }
                } else if (container.hasStorageAccess) {
                    Toast.makeText(context, "Creating sample simple-anki.json", Toast.LENGTH_LONG).show()
                    // createSample propagates IOException; unhandled that is a crash on resume.
                    try {
                        container.deckRepository.createSample()
                    } catch (e: IOException) {
                        Toast.makeText(context, "Could not create simple-anki.json - check file permission or free space", Toast.LENGTH_LONG).show()
                    }
                    val reloaded = container.deckRepository.load()
                    if (reloaded.isNotEmpty()) { cards = reloaded; drawCard() }
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

    MetronomeEffect(
        enabled = container.settings.metronome.enabled,
        intervalSeconds = container.settings.metronome.intervalSeconds,
        // The draw counter, not the card index: random() can return the index it just
        // returned, and an unchanged key would hand that repeat draw whatever was left
        // of the previous card's interval. Null until a deck loads - no cards, no timer.
        cardKey = if (cards.isEmpty() || currentCardIndex < 0) null else cardDraw,
        isFlipScreen = currentScreen == Screen.FlipCards,
        isResumed = isResumed,
        // A lambda rather than container.clickPlayer: an argument is evaluated during
        // composition, which would force that lazy before settings.json has been read.
        play = { container.clickPlayer.play() },
        onFire = {
            val card = cards.getOrNull(currentCardIndex)
            // An answered card was recorded at flip time, so the tick that follows it
            // only clicks and advances. timeTaken holds the interval that elapsed and
            // stays positive; timedOut is the only failure signal.
            if (card != null && !isShowingAnswer) {
                val interval = container.settings.metronome.intervalSeconds
                history = recordAttempt(container, context, history, HistoryEntry(card.question, card.answer, interval, System.currentTimeMillis(), true))
            }
            drawCard()
        }
    )

    AnkiNavShell(
        lifetimeReviews = container.settings.counters.lifetimeReviews,
        views = viewsFile.views,
        current = currentScreen,
        metronomeEnabled = container.settings.metronome.enabled,
        onOpenColumns = if (currentScreen is Screen.Table) ({ sheetOpen = true }) else null,
        onSelect = { currentScreen = it; sheetOpen = false },
        onMetronomeChange = { container.setMetronomeEnabled(it) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val screen = currentScreen) {
                // 6) Every table view, including the retired stats and history pages.
                is Screen.Table -> TableRoute(
                    container = container,
                    viewsFile = viewsFile,
                    viewId = screen.viewId,
                    history = history,
                    deckQuestions = remember(cards) { cards.map { it.question }.toSet() },
                    sheetOpen = sheetOpen,
                    onViewsFile = { viewsFile = it },
                    onSelect = { currentScreen = Screen.Table(it) },
                    onDismissSheet = { sheetOpen = false }
                )
                Screen.FlipCards -> GameView(
                    cards = cards,
                    currentCardIndex = currentCardIndex,
                    isShowingAnswer = isShowingAnswer,
                    summary = remember(history, cards, currentCardIndex) { summarizeCard(history, cards, currentCardIndex) },
                    currentRoundTime = currentRoundTime,
                    onNextCard = { drawCard() },
                    onFlip = {
                        val now = System.currentTimeMillis()
                        val timeTaken = (now - startTime) / 1000f
                        currentRoundTime = timeTaken
                        val card = cards[currentCardIndex]
                        history = recordAttempt(container, context, history, HistoryEntry(card.question, card.answer, timeTaken, now, false))
                        isShowingAnswer = true
                    }
                )
            }
        }
    }
}
