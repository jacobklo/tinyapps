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
    
    // Stats: question -> fastest time
    var stats by remember { mutableStateOf(mapOf<String, Float>()) }
    var currentRoundTime by remember { mutableStateOf(0f) }
    var startTime by remember { mutableStateOf(0L) }
    
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
                    // 10) Reset button
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
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (cards.isNotEmpty() && currentCardIndex != -1) {
                val card = cards[currentCardIndex]
                val questionText = card.question
                val bestTime = stats[questionText] ?: 9999f

                // 4) Card styling
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .clickable {
                            if (!isShowingAnswer) {
                                // 5) Flip to answer
                                val now = System.currentTimeMillis()
                                val timeTaken = (now - startTime) / 1000f
                                currentRoundTime = timeTaken
                                
                                // Update stats
                                val newBest = if (timeTaken < bestTime) timeTaken else bestTime
                                val newStats = stats.toMutableMap()
                                newStats[questionText] = newBest
                                stats = newStats
                                saveStats(context.filesDir, newStats)
                                
                                isShowingAnswer = true
                            } else {
                                // 11) Next question random
                                isShowingAnswer = false
                                currentCardIndex = cards.indices.random()
                                startTime = System.currentTimeMillis()
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
                        Text(
                            text = if (isShowingAnswer) card.answer else card.question,
                            style = MaterialTheme.typography.displayMedium, // Very large
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // 6) Statistics
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isShowingAnswer) {
                                Text(
                                    text = "Time: %.2fs".format(currentRoundTime),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = "Best: %.2fs".format(if (isShowingAnswer && currentRoundTime < bestTime && currentRoundTime > 0) currentRoundTime else bestTime),
                                style = MaterialTheme.typography.bodySmall
                            )
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

// 7) Load stats from internal storage
fun loadStats(filesDir: File): Map<String, Float> {
    val file = File(filesDir, "stats.json")
    if (!file.exists()) return emptyMap()
    return try {
        val jsonString = file.readText()
        val jsonObject = JSONObject(jsonString)
        val map = mutableMapOf<String, Float>()
        val keys = jsonObject.keys()
        while(keys.hasNext()) {
            val key = keys.next()
            map[key] = jsonObject.getDouble(key).toFloat()
        }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}

// 7) Save stats to internal storage
fun saveStats(filesDir: File, stats: Map<String, Float>) {
    val file = File(filesDir, "stats.json")
    val jsonObject = JSONObject()
    stats.forEach { (k, v) -> jsonObject.put(k, v.toDouble()) }
    file.writeText(jsonObject.toString())
}
