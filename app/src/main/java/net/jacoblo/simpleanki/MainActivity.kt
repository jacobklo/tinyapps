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

// 1) New data structure for stats (Modified: Removed bestTime field)
data class CardStats(
    val history: List<Float> = emptyList() // Max 10 items
) {
    // 1) Calculate Best from history
    val bestTime: Float
        get() = if (history.isEmpty()) 9999f else history.minOrNull() ?: 9999f

    // 1) Calculate Average from history
    val averageTime: Float
        get() = if (history.isEmpty()) 0f else history.average().toFloat()
        
    val lastTime: Float
        get() = history.lastOrNull() ?: 0f

    // 4) Calculate Median from history
    val medianTime: Float
        get() {
            if (history.isEmpty()) return 0f
            val sorted = history.sorted()
            val size = sorted.size
            return if (size % 2 == 0) {
                (sorted[size / 2 - 1] + sorted[size / 2]) / 2
            } else {
                sorted[size / 2]
            }
        }
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
    
    // 2) Counter for stats updates
    var statsUpdateCount by remember { mutableIntStateOf(0) }
    
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
                // 7) Load stats and update count
                val (loadedStats, loadedCount) = loadStats()
                stats = loadedStats
                statsUpdateCount = loadedCount
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
                    // 2) Update Counter
                    Text(
                        text = "$statsUpdateCount",
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(end = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )

                    // 6.4) Navigation Icons
                    IconButton(onClick = { currentScreen = Screen.HOME }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { currentScreen = Screen.STATS }) {
                        Icon(Icons.Default.List, contentDescription = "Stats")
                    }

                    // 10) Reset button (All cards)
//                    IconButton(onClick = {
//                        stats = emptyMap()
//                        saveStats(stats, statsUpdateCount)
//                        statsUpdateCount++
//                        Toast.makeText(context, "Stats reset", Toast.LENGTH_SHORT).show()
//                    }) {
//                        Icon(Icons.Default.Refresh, contentDescription = "Reset Stats")
//                    }
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
                StatsScreen(stats, cards.map { it.question })
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
                        
                        // Limit history to 10
                        val newHistory = (oldStat.history + timeTaken).takeLast(10)
                        
                        val newStat = oldStat.copy(history = newHistory)
                        
                        val newStats = stats.toMutableMap()
                        newStats[questionText] = newStat
                        stats = newStats
                        
                        // 7) Save stats and increment count
                        statsUpdateCount = saveStats(newStats, statsUpdateCount)
                        
                        isShowingAnswer = true
                    },
                    onResetCard = { question ->
                        // 3) Reset specific card
                        val newStats = stats.toMutableMap()
                        newStats.remove(question)
                        stats = newStats
                        
                        // 7) Save stats and increment count
                        statsUpdateCount = saveStats(newStats, statsUpdateCount)
                        
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
    val appDir = File(Environment.getExternalStorageDirectory(), "SimpleAnki")
    if (!appDir.exists()) appDir.mkdirs()
    val file = File(appDir, "simple-anki.json")
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
    val appDir = File(Environment.getExternalStorageDirectory(), "SimpleAnki")
    if (!appDir.exists()) appDir.mkdirs()
    val file = File(appDir, "simple-anki.json")
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

// 7) Load stats (UPDATED for CardStats and statsUpdateCount)
fun loadStats(): Pair<Map<String, CardStats>, Int> {
    val appDir = File(Environment.getExternalStorageDirectory(), "SimpleAnki")
    if (!appDir.exists()) appDir.mkdirs()
    val file = File(appDir, "stats.json")
    if (!file.exists()) return Pair(emptyMap(), 0)
    return try {
        val jsonString = file.readText()
        val jsonObject = JSONObject(jsonString)
        val map = mutableMapOf<String, CardStats>()
        var updateCount = 0
        
        val keys = jsonObject.keys()
        while(keys.hasNext()) {
            val key = keys.next()
            // 7) Check for statsUpdateCount key
            if (key == "statsUpdateCount") {
                updateCount = jsonObject.optInt(key, 0)
                continue
            }
            
            val obj = jsonObject.optJSONObject(key)
            if (obj != null) {
                // New format: read history only
                val histArray = obj.getJSONArray("history")
                val history = mutableListOf<Float>()
                for (i in 0 until histArray.length()) {
                    history.add(histArray.getDouble(i).toFloat())
                }
                map[key] = CardStats(history)
            }
        }
        Pair(map, updateCount)
    } catch (e: Exception) {
        Pair(emptyMap(), 0)
    }
}

// 7) Save stats (UPDATED for CardStats and statsUpdateCount)
fun saveStats(stats: Map<String, CardStats>, currentCount: Int): Int {
    // 7) Increment count before saving
    val newCount = currentCount + 1
    
    val appDir = File(Environment.getExternalStorageDirectory(), "SimpleAnki")
    if (!appDir.exists()) appDir.mkdirs()
    val file = File(appDir, "stats.json")
    val jsonObject = JSONObject()
    
    // 7) Save the count
    jsonObject.put("statsUpdateCount", newCount)
    
    stats.forEach { (k, v) ->
        val statObj = JSONObject()
        // No longer saving explicit "best", calculated from history
        val histArray = JSONArray()
        v.history.forEach { histArray.put(it.toDouble()) }
        statObj.put("history", histArray)
        jsonObject.put(k, statObj)
    }
    file.writeText(jsonObject.toString())
    return newCount
}
