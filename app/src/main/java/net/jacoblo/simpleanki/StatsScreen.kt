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
import net.jacoblo.simpleanki.data.HistoryEntry

// One row of the table, derived from history now that stats.json is gone.
// A null figure means the card has no successful attempt in the window.
private data class StatsRow(
    val question: String,
    val best: Float?,
    val average: Float?,
    val median: Float?,
    val last: Float?
)

// 6) New All Card Stats Page
@Composable
fun StatsScreen(history: List<HistoryEntry>, validQuestions: List<String>) {
    // 6.2) Sortable columns state
    var sortColumn by remember { mutableStateOf(SortColumn.QUESTION) }
    var sortAscending by remember { mutableStateOf(true) }

    val rows = remember(history, validQuestions) {
        // 3) Only questions present in the current card list get a row
        val valid = validQuestions.toSet()
        history.map { it.question }.distinct().filter { it in valid }.map { question ->
            // Best and Avg come from summarize. Med and Last are derived here instead of
            // joining CardSummary, because GameView never shows them.
            val summary = summarize(history, question)
            val times = recentTimes(history, question, SUMMARY_LIMIT)
            StatsRow(question, summary.best, summary.average, medianOf(times), times.firstOrNull())
        }
    }

    // Sort data based on selection
    val sortedData = remember(rows, sortColumn, sortAscending) {
        when (sortColumn) {
            SortColumn.QUESTION -> if (sortAscending) rows.sortedBy { it.question } else rows.sortedByDescending { it.question }
            SortColumn.BEST -> if (sortAscending) rows.sortedBy { it.best } else rows.sortedByDescending { it.best }
            SortColumn.AVERAGE -> if (sortAscending) rows.sortedBy { it.average } else rows.sortedByDescending { it.average }
            SortColumn.MEDIAN -> if (sortAscending) rows.sortedBy { it.median } else rows.sortedByDescending { it.median }
            SortColumn.LAST -> if (sortAscending) rows.sortedBy { it.last } else rows.sortedByDescending { it.last }
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
            itemsIndexed(sortedData) { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 3) Row Count
                    Text("${index + 1}", modifier = Modifier.weight(0.5f).padding(end = 4.dp), style = MaterialTheme.typography.bodyMedium)

                    Text(row.question, modifier = Modifier.weight(2.5f), style = MaterialTheme.typography.bodyMedium)
                    Text(formatCell(row.best), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(formatCell(row.average), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    // 4) Median Value
                    Text(formatCell(row.median), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)

                    Text(formatCell(row.last), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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

// Median of the successful attempts, or null when there are none.
private fun medianOf(times: List<Float>): Float? {
    if (times.isEmpty()) return null
    val sorted = times.sorted()
    val size = sorted.size
    return if (size % 2 == 0) (sorted[size / 2 - 1] + sorted[size / 2]) / 2 else sorted[size / 2]
}

private fun formatCell(value: Float?): String = if (value == null) "-" else "%.2f".format(value)

enum class SortColumn { QUESTION, BEST, AVERAGE, MEDIAN, LAST }
