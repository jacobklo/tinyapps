package net.jacoblo.simpleanki

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 6) New All Card Stats Page
@Composable
fun StatsScreen(stats: Map<String, CardStats>, validQuestions: List<String>) {
    // 6.2) Sortable columns state
    var sortColumn by remember { mutableStateOf(SortColumn.QUESTION) }
    var sortAscending by remember { mutableStateOf(true) }

    // Sort data based on selection
    val sortedData = remember(stats, sortColumn, sortAscending, validQuestions) {
        // 3) Filter stats to only include questions present in the current card list
        val validStats = stats.filterKeys { it in validQuestions }
        val list = validStats.entries.toList()
        when (sortColumn) {
            SortColumn.QUESTION -> if (sortAscending) list.sortedBy { it.key } else list.sortedByDescending { it.key }
            SortColumn.BEST -> if (sortAscending) list.sortedBy { it.value.bestTime } else list.sortedByDescending { it.value.bestTime }
            SortColumn.AVERAGE -> if (sortAscending) list.sortedBy { it.value.averageTime } else list.sortedByDescending { it.value.averageTime }
            SortColumn.MEDIAN -> if (sortAscending) list.sortedBy { it.value.medianTime } else list.sortedByDescending { it.value.medianTime }
            SortColumn.LAST -> if (sortAscending) list.sortedBy { it.value.lastTime } else list.sortedByDescending { it.value.lastTime }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 6.1) Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp)
        ) {
            // 3) Row Count Header
            Text("#", modifier = Modifier.weight(0.5f).padding(end = 4.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

            HeaderCell("Question", Modifier.weight(2.5f)) {
                if (sortColumn == SortColumn.QUESTION) sortAscending = !sortAscending else { sortColumn = SortColumn.QUESTION; sortAscending = true }
            }
            HeaderCell("Best", Modifier.weight(1f)) {
                if (sortColumn == SortColumn.BEST) sortAscending = !sortAscending else { sortColumn = SortColumn.BEST; sortAscending = true }
            }
            HeaderCell("Avg", Modifier.weight(1f)) {
                if (sortColumn == SortColumn.AVERAGE) sortAscending = !sortAscending else { sortColumn = SortColumn.AVERAGE; sortAscending = true }
            }
            // 4) Median Header
            HeaderCell("Med", Modifier.weight(1f)) {
                if (sortColumn == SortColumn.MEDIAN) sortAscending = !sortAscending else { sortColumn = SortColumn.MEDIAN; sortAscending = true }
            }
            HeaderCell("Last", Modifier.weight(1f)) {
                if (sortColumn == SortColumn.LAST) sortAscending = !sortAscending else { sortColumn = SortColumn.LAST; sortAscending = true }
            }
        }
        
        // 6.3) Scrollable list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(sortedData) { index, (question, stat) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 3) Row Count
                    Text("${index + 1}", modifier = Modifier.weight(0.5f).padding(end = 4.dp), style = MaterialTheme.typography.bodyMedium)

                    Text(question, modifier = Modifier.weight(2.5f), style = MaterialTheme.typography.bodyMedium)
                    // Show '-' if default 9999
                    Text(
                        text = if (stat.bestTime >= 9999f) "-" else "%.2f".format(stat.bestTime),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("%.2f".format(stat.averageTime), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    // 4) Median Value
                    Text("%.2f".format(stat.medianTime), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    
                    Text("%.2f".format(stat.lastTime), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
fun HeaderCell(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = modifier.clickable(onClick = onClick),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall
    )
}

enum class SortColumn { QUESTION, BEST, AVERAGE, MEDIAN, LAST }
