package net.jacoblo.autoclicker

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

private const val TAG = "autoclicker.view.hierarchy"

// Written where only root can read it, then read back and removed in the same
// command, so a window's contents never sit on shared storage.
private const val DUMP_PATH = "/data/local/tmp/autoclicker-ui.xml"

private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

/**
 * An editable field, as the window itself reports it.
 *
 * [textLength] is what the field already holds, which is what makes clearing it
 * exact: press backspace that many times rather than guessing at a number large
 * enough to cover anything.
 */
data class FieldNode(
	val left: Int,
	val top: Int,
	val right: Int,
	val bottom: Int,
	val focused: Boolean,
	val textLength: Int
) {
	val centreX: Float get() = (left + right) / 2f
	val centreY: Float get() = (top + bottom) / 2f
}

sealed class FieldSearch {
	data class Found(val field: FieldNode) : FieldSearch()

	/** Phrased for showing to the user, so it names the thing to fix. */
	data class Missing(val reason: String) : FieldSearch()
}

/**
 * Asks the window manager where the editable field is, instead of the script
 * carrying a coordinate for it.
 *
 * A saved area plus an offset only holds while the layout does, and stepping
 * onto the field with TAB depends on a focus order that nothing on screen
 * shows and that changes as the screen fills in. The window already knows;
 * this reads the answer, including across a work profile.
 */
object ViewHierarchy {

	/** Blocks on the root shell; callers run it off the main thread. */
	fun findField(): FieldSearch {
		val xml = dump() ?: return FieldSearch.Missing("cannot read the screen layout, which needs root")

		val fields = try {
			parse(xml)
		} catch (e: Exception) {
			Log.w(TAG, "cannot parse the hierarchy", e)
			return FieldSearch.Missing("the screen layout came back unreadable")
		}

		// The focused one is the answer whenever there is one, which also
		// settles a screen carrying more than one field.
		fields.firstOrNull { it.focused }?.let { return FieldSearch.Found(it) }

		return when (fields.size) {
			0 -> FieldSearch.Missing("no text field on screen")
			1 -> FieldSearch.Found(fields.first())
			// Guessing between them would be a coin toss that fails silently
			// later, when the text lands somewhere nobody looked.
			else -> FieldSearch.Missing("${fields.size} text fields on screen and none focused")
		}
	}

	private fun dump(): String? {
		val process = RootShell.spawn(
			"uiautomator dump $DUMP_PATH >/dev/null 2>&1 && cat $DUMP_PATH && rm -f $DUMP_PATH"
		) ?: return null
		return try {
			val text = process.inputStream.bufferedReader().use { it.readText() }
			text.ifBlank { null }
		} catch (e: Exception) {
			Log.e(TAG, "cannot read the hierarchy dump", e)
			null
		} finally {
			process.destroy()
		}
	}

	private fun parse(xml: String): List<FieldNode> {
		val parser = Xml.newPullParser()
		parser.setInput(StringReader(xml))

		val fields = mutableListOf<FieldNode>()
		var event = parser.eventType
		while (event != XmlPullParser.END_DOCUMENT) {
			if (event == XmlPullParser.START_TAG && parser.name == "node") {
				node(parser)?.let { fields.add(it) }
			}
			event = parser.next()
		}
		return fields
	}

	private fun node(parser: XmlPullParser): FieldNode? {
		val className = parser.getAttributeValue(null, "class").orEmpty()
		val editable = parser.getAttributeValue(null, "editable") == "true"
		if (!editable && !className.contains("EditText")) return null

		val match = BOUNDS.find(parser.getAttributeValue(null, "bounds").orEmpty()) ?: return null
		val (left, top, right, bottom) = match.destructured
		return FieldNode(
			left = left.toInt(),
			top = top.toInt(),
			right = right.toInt(),
			bottom = bottom.toInt(),
			focused = parser.getAttributeValue(null, "focused") == "true",
			textLength = parser.getAttributeValue(null, "text").orEmpty().length
		)
	}
}
