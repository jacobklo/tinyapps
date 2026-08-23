package net.jacoblo.simpleanki.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Reads and writes simple-anki.json, a flat array of {question, answer} objects.
 *
 * The deck is authored by hand, so it is written indented and is never rewritten by the
 * app outside [createSample].
 */
class DeckRepository(private val paths: AnkiPaths) {

	/**
	 * Cards in file order, or an empty list when the file is missing, unreadable, or
	 * malformed.
	 *
	 * The three are one case here because nothing here writes the deck back: an empty
	 * result costs the user this run's cards, never the file. What they have in common is
	 * their effect on the CALLER, though, not their safety - MainActivity reads an empty
	 * list as "offer the sample", and its storage-access guard only rules out the case
	 * where no path is writable at all. Keeping a malformed deck out of the sample's way
	 * is [createSample]'s job, not this one's.
	 */
	fun load(): List<AnkiCard> {
		val raw = JsonStore(paths.deck).read().textOrNull ?: return emptyList()
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

	/**
	 * Writes the five-card sample deck, moving any existing file aside first.
	 *
	 * Called whenever [load] came back empty AND storage is available, which is not the
	 * same thing as "there is no deck": a malformed simple-anki.json reads as empty too,
	 * and so does a well-formed empty array. Writing unconditionally would therefore
	 * replace a hand-authored deck with five sample cards, and the deck is the one file
	 * here with no backup, no rolling window, and no other copy anywhere.
	 *
	 * So it is quarantined to "simple-anki.json.corrupt" first, exactly as settings.json
	 * and views.json are, and the sample is written into the space that leaves. A
	 * quarantine that already exists is never overwritten, so a second incident throws
	 * instead - the file still on disk is the user's, and refusing to run costs them the
	 * sample rather than the deck.
	 *
	 * @throws IOException when the existing deck cannot be moved aside, or when the write
	 *   itself fails. MainActivity reports both.
	 */
	fun createSample() {
		paths.ensureRoot()
		if (paths.deck.exists() && !JsonStore(paths.deck).quarantine()) {
			throw IOException("refusing to overwrite ${paths.deck.path}")
		}
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
