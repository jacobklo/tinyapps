package net.jacoblo.autoclicker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a step can find out before it acts.
 *
 * Four different ways of asking the device a question -- match a saved picture,
 * read the words on screen, ask the window where its text field is, and wait on
 * the code service -- with nothing in common except that every one of them
 * blocks, and that a step which cannot get its answer is skipped rather than
 * fatal. The sealed results they already return say why they failed, which is
 * what the executor turns into the message the user sees.
 */
interface Finder {

	suspend fun findArea(name: String): AreaSearch

	/**
	 * [thorough] asks for the frame to be taken apart rather than simply read,
	 * which is slower by seconds and finds phrases a single reading cannot.
	 */
	suspend fun findText(phrase: String, thorough: Boolean = true): TextSearch

	suspend fun findField(): FieldSearch

	/** Polls [url] until it answers 2xx or [timeoutMs] elapses; returns the body, or null. */
	suspend fun httpGet(url: String, timeoutMs: Long, intervalMs: Long): String?
}

/** Each call is blocking, so each is moved off the caller's thread here. */
object DeviceFinder : Finder {

	override suspend fun findArea(name: String): AreaSearch =
		withContext(Dispatchers.IO) { ScreenConditions.search(name) }

	override suspend fun findText(phrase: String, thorough: Boolean): TextSearch =
		withContext(Dispatchers.IO) { ScreenText.find(phrase, thorough = thorough) }

	override suspend fun findField(): FieldSearch =
		withContext(Dispatchers.IO) { ViewHierarchy.findField() }

	override suspend fun httpGet(url: String, timeoutMs: Long, intervalMs: Long): String? =
		withContext(Dispatchers.IO) { Http.pollGet(url, timeoutMs, intervalMs) }
}
