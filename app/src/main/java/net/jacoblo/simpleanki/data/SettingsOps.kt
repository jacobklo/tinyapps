/*
 * What the settings screen does to the stored settings, as pure functions.
 *
 * A sibling of ViewOps.kt and split out for the same reason: a composable is unavoidably
 * Android, and every rule worth testing here - that an interval of zero is refused, that
 * "#GGGGGG" never reaches the page, what a blank sound path means - is pure text
 * handling. SettingsScreen only wires text fields to these.
 *
 * Free of Android imports on purpose, so the tests run on a plain JVM.
 */
package net.jacoblo.simpleanki.data

/**
 * The row tint on a light theme.
 *
 * A purple-grey rather than a neutral one, because the light surface it bands
 * (table.html's "#fffbfe") and the gridline it sits between ("#cac4d0") are both
 * purple-tinted, and a blue-grey band reads as a different palette rather than as the
 * same one a shade darker.
 *
 * Chosen against its surface by measured contrast, not by taste: 1.40:1 where the
 * translucent value it replaces managed 1.15:1. Body text stays at 11.9:1 over it, so a
 * banded row is more visible without being any harder to read.
 */
const val DEFAULT_HIGHLIGHT_LIGHT = "#DAD5E4"

/** The dark counterpart of [DEFAULT_HIGHLIGHT_LIGHT]: 1.46:1 over "#1c1b1f", text at 9.1:1. */
const val DEFAULT_HIGHLIGHT_DARK = "#3B3546"

/**
 * Exactly "#" and six hex digits. No shorthand, and no alpha - see [TableSettings].
 *
 * Unanchored because [Regex.matches] anchors for us: it requires the whole input to
 * match, so a "^...$" here would be two characters that change nothing. The anchoring is
 * the point, though - matched with containsMatchIn instead, "#DAD5E4;" would be a colour.
 */
private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

/**
 * What a settings field's editor produced: the parsed value, or why it was refused.
 *
 * The shape [BuildResult] already uses in ViewOps.kt, made generic because these fields
 * parse to four different types. A refused field shows its message inline and is not
 * written, so nothing malformed reaches settings.json through the UI.
 */
sealed interface FieldResult<out T> {
	data class Ok<T>(val value: T) : FieldResult<T>
	data class Err(val message: String) : FieldResult<Nothing>
}

/**
 * The metronome interval [text] describes, refusing anything that is not a positive
 * finite number of seconds.
 *
 * Zero and negatives are refused here as well as guarded in MetronomeEffect, and the two
 * are not redundant: the effect can only stop dead, which from the user's side is a
 * metronome that silently does nothing. Refusing at the field is what says why.
 *
 * Infinity is refused for the same reason it would have to be: it parses, it is
 * positive, and it is a countdown that never fires.
 */
fun parseIntervalSeconds(text: String): FieldResult<Float> {
	// No trim, unlike the fields below: toFloatOrNull already accepts surrounding
	// whitespace, where toIntOrNull refuses it.
	val value = text.toFloatOrNull()
	// NaN fails the comparison rather than needing a test of its own, since every
	// comparison against NaN is false.
	if (value == null || !value.isFinite() || value <= 0f) {
		return FieldResult.Err("Must be a number of seconds greater than 0")
	}
	return FieldResult.Ok(value)
}

/**
 * The banding interval [text] describes. Zero is valid and means no banding at all, which
 * is why this cannot share [parseWindowSize]'s lower bound.
 */
fun parseHighlightEvery(text: String): FieldResult<Int> = parseAtLeast(text, 0)

/**
 * The window size [text] describes, refusing zero.
 *
 * A window of zero rows is not a window. ViewOps.sized already defaults one away rather
 * than storing it, so accepting it here would store a number that nothing downstream
 * would ever use.
 */
fun parseWindowSize(text: String): FieldResult<Int> = parseAtLeast(text, 1)

/** The group limit [text] describes. Zero is valid and means every attempt in the group. */
fun parseDefaultLimit(text: String): FieldResult<Int> = parseAtLeast(text, 0)

/** [text] as an Int no smaller than [minimum]. */
private fun parseAtLeast(text: String, minimum: Int): FieldResult<Int> {
	val value = text.trim().toIntOrNull()
	if (value == null || value < minimum) return FieldResult.Err("Must be a whole number, $minimum or more")
	return FieldResult.Ok(value)
}

/**
 * [text] as a "#RRGGBB" colour, refused unless it is exactly that.
 *
 * Kept verbatim apart from the surrounding whitespace, rather than normalised to one
 * letter case: CSS does not care, and rewriting what the user typed while they are still
 * typing it is the one thing a field that saves on every keystroke must not do.
 *
 * Shorthand ("#ABC") and an alpha channel ("#AARRGGBB") are refused rather than expanded.
 * Both would be sent to the page verbatim, and the second would blend against the surface
 * behind the row, which is what [TableSettings] says the format exists to avoid.
 */
fun parseHexColor(text: String): FieldResult<String> {
	val trimmed = text.trim()
	if (!HEX_COLOR.matches(trimmed)) return FieldResult.Err("Must be a hex colour such as $DEFAULT_HIGHLIGHT_LIGHT")
	return FieldResult.Ok(trimmed)
}

/**
 * The sound path [text] names, or null for the bundled click.
 *
 * Blank means null rather than an empty path because that is the distinction
 * MetronomeSettings already draws, and SoundPoolClickPlayer treats an empty string as a
 * path it cannot read - a Toast on every launch for a user who only cleared the field.
 */
fun soundPathOrNull(text: String): String? = text.trim().ifEmpty { null }

/**
 * The row tint the page should use under [darkTheme], falling back to the default when
 * what is stored is not a colour.
 *
 * The fallback is for the hand-edited file, not for the UI: the field refuses to write
 * anything malformed, but settings.json can be edited by hand and a typo there must not
 * reach the page as a CSS value. A rejected declaration would leave `--highlight` unset
 * and every banded row would silently lose its tint, which looks exactly like banding
 * being off.
 */
fun TableSettings.highlightColor(darkTheme: Boolean): String {
	val stored = if (darkTheme) highlightColorDark else highlightColorLight
	val fallback = if (darkTheme) DEFAULT_HIGHLIGHT_DARK else DEFAULT_HIGHLIGHT_LIGHT
	return when (val parsed = parseHexColor(stored)) {
		is FieldResult.Ok -> parsed.value
		is FieldResult.Err -> fallback
	}
}
