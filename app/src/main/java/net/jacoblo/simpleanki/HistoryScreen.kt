package net.jacoblo.simpleanki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(history: List<HistoryEntry>) {
    // Newest first
    val sorted = remember(history) { history.sortedByDescending { it.timestamp } }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp)
        ) {
            Text("#", modifier = Modifier.weight(0.5f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("When", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("Question", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("Answer", modifier = Modifier.weight(2f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text("Time", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(sorted) { index, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("${index + 1}", modifier = Modifier.weight(0.5f), style = MaterialTheme.typography.bodyMedium)
                    Text(fmt.format(Date(entry.timestamp)), modifier = Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
                    Text(entry.question, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                    Text(entry.answer, modifier = Modifier.weight(2f), style = MaterialTheme.typography.bodyMedium)
                    Text("%.2fs".format(entry.timeTaken), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}
