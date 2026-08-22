package net.jacoblo.simpleanki.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes simple-anki.json, a flat array of {question, answer} objects.
 *
 * The deck is authored by hand, so it is written indented and is never rewritten by the
 * app outside [createSample].
 */
class DeckRepository(private val paths: AnkiPaths) {

	/** Cards in file order, or an empty list when the file is missing or malformed. */
	fun load(): List<AnkiCard> {
		val raw = JsonStore(paths.deck).readOrNull() ?: return emptyList()
		return try {
			val array = JSONArray(raw)
			val list = ArrayList<AnkiCard>(array.length())
			for (i in 0 until array.length()) {
				val obj = array.getJSONObject(i)
				list.add(AnkiCard(obj.getString("question"), obj.getString("answer")))
			}
			list
		} catch (_: Exception) {
			emptyList()
		}
	}

	/** Writes the five-card sample deck used when no file exists. */
	fun createSample() {
		paths.ensureRoot()
		val array = JSONArray()
		SAMPLE_CARDS.forEach { card ->
			val obj = JSONObject()
			obj.put("question", card.question)
			obj.put("answer", card.answer)
			array.put(obj)
		}
		JsonStore(paths.deck).write(array.toString(SAMPLE_INDENT))
	}

	companion object {
		private const val SAMPLE_INDENT = 4

		private val SAMPLE_CARDS = listOf(
			AnkiCard("Capital of France?", "Paris"),
			AnkiCard("2 + 2?", "4"),
			AnkiCard("Color of the sky?", "Blue"),
			AnkiCard("Android mascot?", "Bugdroid"),
			AnkiCard("Language for Android?", "Kotlin")
		)
	}
}
