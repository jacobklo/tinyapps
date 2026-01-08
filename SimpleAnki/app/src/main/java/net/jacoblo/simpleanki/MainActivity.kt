package net.jacoblo.simpleanki

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.jacoblo.simpleanki.ui.theme.SimpleAnkiTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

// Data model for a flashcard
data class AnkiCard(val question: String, val answer: String)

// 1) New data structure for stats
data class CardStats(
    val bestTime: Float = 9999f,
    val history: List<Float> = emptyList() // Max 10 items
) {
    // 1) Calculate Average from history
    val averageTime: Float
        get() = if (history.isEmpty()) 0f else history.average().toFloat()
        
    val lastTime: Float
        get() = history.lastOrNull() ?: 0f
}

enum class Screen { HOME, STATS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 8) Keep screen always on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            SimpleAnkiTheme {
                AnkiScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 1) Check file permission on launch
        checkPermission()
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
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
fun AnkiScreen() {
    val context = LocalContext.current
    var cards by remember { mutableStateOf<List<AnkiCard>>(emptyList()) }
    var currentCardIndex by remember { mutableStateOf(-1) }
    var isShowingAnswer by remember { mutableStateOf(false) }
    
    // 5) Stats: question -> CardStats
    var stats by remember { mutableStateOf(mapOf<String, CardStats>()) }
    var currentRoundTime by remember { mutableStateOf(0f) }
    var startTime by remember { mutableStateOf(0L) }
    
    // Navigation state
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    
    // Watch lifecycle to reload cards when permission granted
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 2) Read cards
                val loaded = loadCards()
                if (loaded.isNotEmpty()) {
                    if (cards.isEmpty()) {
                        cards = loaded
                        currentCardIndex = cards.indices.random()
                        startTime = System.currentTimeMillis()
                    }
                } else {
                    // Create sample if permitted
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                        Toast.makeText(context, "Creating sample simple-anki.json", Toast.LENGTH_LONG).show()
                        createSampleFile()
                        val reloaded = loadCards()
                        if (reloaded.isNotEmpty()) {
                            cards = reloaded
                            currentCardIndex = cards.indices.random()
                            startTime = System.currentTimeMillis()
                        }
                    }
                }
                stats = loadStats(context.filesDir)
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

                    // 10) Reset button (All cards)
                    IconButton(onClick = {
                        stats = emptyMap()
                        saveStats(context.filesDir, stats)
                        Toast.makeText(context, "Stats reset", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Stats")
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
            if (currentScreen == Screen.STATS) {
                // 6) Stats Page
                StatsScreen(stats)
            } else {
                // Game Screen
                GameView(
                    cards = cards,
                    currentCardIndex = currentCardIndex,
                    isShowingAnswer = isShowingAnswer,
                    stats = stats,
                    currentRoundTime = currentRoundTime,
                    startTime = startTime,
                    onNextCard = {
                        isShowingAnswer = false
                        currentCardIndex = cards.indices.random()
                        startTime = System.currentTimeMillis()
                    },
                    onFlip = {
                        val now = System.currentTimeMillis()
                        val timeTaken = (now - startTime) / 1000f
                        currentRoundTime = timeTaken
                        
                        // 1) Update stats with history
                        val questionText = cards[currentCardIndex].question
                        val oldStat = stats[questionText] ?: CardStats()
                        
                        val newBest = if (timeTaken < oldStat.bestTime) timeTaken else oldStat.bestTime
                        // Limit history to 10
                        val newHistory = (oldStat.history + timeTaken).takeLast(10)
                        
                        val newStat = oldStat.copy(bestTime = newBest, history = newHistory)
                        
                        val newStats = stats.toMutableMap()
                        newStats[questionText] = newStat
                        stats = newStats
                        saveStats(context.filesDir, newStats)
                        
                        isShowingAnswer = true
                    },
                    onResetCard = { question ->
                        // 3) Reset specific card
                        val newStats = stats.toMutableMap()
                        newStats.remove(question)
                        stats = newStats
                        saveStats(context.filesDir, newStats)
                        Toast.makeText(context, "Card stats reset", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun GameView(
    cards: List<AnkiCard>,
    currentCardIndex: Int,
    isShowingAnswer: Boolean,
    stats: Map<String, CardStats>,
    currentRoundTime: Float,
    startTime: Long,
    onNextCard: () -> Unit,
    onFlip: () -> Unit,
    onResetCard: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (cards.isNotEmpty() && currentCardIndex != -1) {
            val card = cards[currentCardIndex]
            val questionText = card.question
            val cardStats = stats[questionText] ?: CardStats()
            val bestTime = cardStats.bestTime
            val averageTime = cardStats.averageTime

            // 4) Card styling
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        if (!isShowingAnswer) {
                            onFlip()
                        } else {
                            onNextCard()
                        }
                    },
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isShowingAnswer) card.answer else card.question,
                            style = MaterialTheme.typography.displayMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    // 6) Statistics
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isShowingAnswer) {
                            Text(
                                text = "Time: %.2fs".format(currentRoundTime),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "Best: %.2fs".format(if (bestTime == 9999f) 0f else bestTime),
                            style = MaterialTheme.typography.bodySmall
                        )
                        // 4) Show Average
                        Text(
                            text = "Avg: %.2fs".format(averageTime),
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        // 12) Reset current card stats button
                        IconButton(onClick = { onResetCard(questionText) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset Card Stats")
                        }
                    }
                }
            }
        } else {
            Text(
                text = "No cards found.\nPlease grant permission or check simple-anki.json",
                textAlign = TextAlign.Center
            )
        }
    }
}

// 2) Load cards from external storage
fun loadCards(): List<AnkiCard> {
    val file = File(Environment.getExternalStorageDirectory(), "simple-anki.json")
    if (!file.exists()) return emptyList()
    
    return try {
        val jsonString = file.readText()
        val jsonArray = JSONArray(jsonString)
        val list = mutableListOf<AnkiCard>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(AnkiCard(obj.getString("question"), obj.getString("answer")))
        }
        list
    } catch (e: Exception) {
        emptyList()
    }
}

// 2) Create sample file if not exists
fun createSampleFile() {
    val file = File(Environment.getExternalStorageDirectory(), "simple-anki.json")
    val list = listOf(
        AnkiCard("Capital of France?", "Paris"),
        AnkiCard("2 + 2?", "4"),
        AnkiCard("Color of the sky?", "Blue"),
        AnkiCard("Android mascot?", "Bugdroid"),
        AnkiCard("Language for Android?", "Kotlin")
    )
    val jsonArray = JSONArray()
    list.forEach { 
        val obj = JSONObject()
        obj.put("question", it.question)
        obj.put("answer", it.answer)
        jsonArray.put(obj)
    }
    try {
        file.writeText(jsonArray.toString(4))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// 7) Load stats (UPDATED for CardStats)
fun loadStats(filesDir: File): Map<String, CardStats> {
    val file = File(filesDir, "stats.json")
    if (!file.exists()) return emptyMap()
    return try {
        val jsonString = file.readText()
        val jsonObject = JSONObject(jsonString)
        val map = mutableMapOf<String, CardStats>()
        val keys = jsonObject.keys()
        while(keys.hasNext()) {
            val key = keys.next()
            val obj = jsonObject.optJSONObject(key)
            if (obj != null) {
                // New format
                val best = obj.getDouble("best").toFloat()
                val histArray = obj.getJSONArray("history")
                val history = mutableListOf<Float>()
                for (i in 0 until histArray.length()) {
                    history.add(histArray.getDouble(i).toFloat())
                }
                map[key] = CardStats(best, history)
            } else {
                // Legacy format (just float) or invalid
                val best = jsonObject.getDouble(key).toFloat()
                map[key] = CardStats(bestTime = best)
            }
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}

// 7) Save stats (UPDATED for CardStats)
fun saveStats(filesDir: File, stats: Map<String, CardStats>) {
    val file = File(filesDir, "stats.json")
    val jsonObject = JSONObject()
    stats.forEach { (k, v) ->
        val statObj = JSONObject()
        statObj.put("best", v.bestTime.toDouble())
        val histArray = JSONArray()
        v.history.forEach { histArray.put(it.toDouble()) }
        statObj.put("history", histArray)
        jsonObject.put(k, statObj)
    }
    file.writeText(jsonObject.toString())
}
