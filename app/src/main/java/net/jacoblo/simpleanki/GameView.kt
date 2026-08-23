package net.jacoblo.simpleanki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jacoblo.simpleanki.data.AnkiCard
import net.jacoblo.simpleanki.data.HistoryEntry

/** Attempts a summary looks at, matching the ten the retired CardStats kept. */
const val SUMMARY_LIMIT = 10

/** Best and average of a single card, derived from history. Replaces CardStats. */
data class CardSummary(val best: Float?, val average: Float?)

/**
 * Derives the summary for one question from history, over the newest [limit] attempts.
 * Timed-out attempts are excluded from both figures. Returns nulls when the card has no
 * successful attempt.
 */
fun summarize(history: List<HistoryEntry>, question: String, limit: Int = SUMMARY_LIMIT): CardSummary {
	val times = recentTimes(history, question, limit)
	val best = times.minOrNull() ?: return CardSummary(null, null)
	return CardSummary(best, times.average().toFloat())
}

/**
 * Summary for the card at [index] of [cards], over the newest [SUMMARY_LIMIT] attempts.
 *
 * Returns an empty summary when there is no such card, which is how the game screen
 * renders before a deck loads.
 */
fun summarizeCard(history: List<HistoryEntry>, cards: List<AnkiCard>, index: Int): CardSummary {
	val question = cards.getOrNull(index)?.question ?: return CardSummary(null, null)
	return summarize(history, question)
}

/**
 * Times of the successful attempts among the newest [limit] attempts at [question],
 * newest first.
 *
 * The window is taken before the timeout filter, so a timed-out attempt still consumes
 * one of the [limit] slots instead of letting an older attempt take its place.
 */
private fun recentTimes(history: List<HistoryEntry>, question: String, limit: Int): List<Float> =
	history.filter { it.question == question }
		.sortedByDescending { it.timestamp }
		.take(limit)
		.filter { !it.timedOut }
		.map { it.timeTaken }

@Composable
fun GameView(
	cards: List<AnkiCard>,
	currentCardIndex: Int,
	isShowingAnswer: Boolean,
	summary: CardSummary,
	currentRoundTime: Float,
	onNextCard: () -> Unit,
	onFlip: () -> Unit
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(16.dp),
		contentAlignment = Alignment.Center
	) {
		if (cards.isNotEmpty() && currentCardIndex != -1) {
			val card = cards[currentCardIndex]

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
							text = "Best: " + formatSeconds(summary.best),
							style = MaterialTheme.typography.bodySmall
						)
						// 4) Show Average
						Text(
							text = "Avg: " + formatSeconds(summary.average),
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

/** Formats a derived time, or "-" when the card has no successful attempt yet. */
private fun formatSeconds(value: Float?): String =
	if (value == null) "-" else "%.2fs".format(value)
