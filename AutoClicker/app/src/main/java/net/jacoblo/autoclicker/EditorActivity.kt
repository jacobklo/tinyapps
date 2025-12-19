package net.jacoblo.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme
import java.io.File

class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val filePath = intent.getStringExtra("FILE_PATH")
        val file = if (filePath != null) File(filePath) else null

        setContent {
            AutoClickerTheme {
                if (file != null && file.exists()) {
                    EditorScreen(file = file, onBack = { finish() })
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("File not found")
                        Button(onClick = { finish() }) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(file: File, onBack: () -> Unit) {
    // Load initial state
    val initialInteractions = remember { RecordingManager.loadRecording(file) }
    val interactions = remember { mutableStateListOf<Interaction>().apply { addAll(initialInteractions) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        RecordingManager.saveRecordingToFile(file, interactions)
                        onBack()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            itemsIndexed(interactions) { index, interaction ->
                InteractionRow(
                    interaction = interaction,
                    onDelete = { interactions.removeAt(index) },
                    onMoveUp = {
                        if (index > 0) {
                            val prev = interactions[index - 1]
                            interactions[index - 1] = interaction
                            interactions[index] = prev
                        }
                    },
                    onMoveDown = {
                        if (index < interactions.size - 1) {
                            val next = interactions[index + 1]
                            interactions[index + 1] = interaction
                            interactions[index] = next
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun InteractionRow(
    interaction: Interaction,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Move Up/Down Controls
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(end = 16.dp)
        ) {
            IconButton(
                onClick = onMoveUp,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
            }
            IconButton(
                onClick = onMoveDown,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
            }
        }
        
        Column(modifier = Modifier.weight(1f)) {
            when (interaction) {
                is ClickInteraction -> {
                    Text("Click", style = MaterialTheme.typography.titleMedium)
                    Text("Start: (${interaction.x.toInt()}, ${interaction.y.toInt()})")
                }
                is DragInteraction -> {
                    Text("Drag", style = MaterialTheme.typography.titleMedium)
                    if (interaction.points.isNotEmpty()) {
                        val start = interaction.points.first()
                        Text("Start: (${start.x.toInt()}, ${start.y.toInt()})")
                    } else {
                        Text("Start: (0, 0)")
                    }
                }
            }
        }
        
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}