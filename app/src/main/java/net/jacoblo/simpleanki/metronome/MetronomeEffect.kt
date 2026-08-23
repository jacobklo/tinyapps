/*
 * The per-card countdown, and the whole of its lifecycle rule.
 *
 * A countdown rather than a free-running beat: every card gets a fresh full interval,
 * and nothing here keeps a running clock between cards.
 */
package net.jacoblo.simpleanki.metronome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/**
 * Waits [intervalSeconds] for the card identified by [cardKey], then calls [play] followed
 * by [onFire].
 *
 * Cancels and restarts whenever any parameter it keys on changes, and THAT is the entire
 * stop-and-reset rule - no timer bookkeeping, no service, no ViewModel. Leaving the flip
 * screen or backgrounding the app flips a gate, which cancels the coroutine mid-delay so
 * the abandoned attempt writes nothing; coming back restarts the same card from zero with
 * a full interval. There is no partial-progress resume because there is no progress to
 * resume - the delay is the only state.
 *
 * A new [cardKey] cancels a pending countdown the same way, which is what gives a manually
 * advanced card its full interval. The caller owes a key that changes on EVERY draw, not
 * one that merely identifies the card: two consecutive draws of the same card must not let
 * the second inherit the first's remaining time.
 *
 * @param play the tick. A lambda rather than a [ClickPlayer] BECAUSE a parameter is
 *   evaluated during composition, and composition runs before the resume that loads
 *   settings.json - see AppContainer.clickPlayer, which is lazy for exactly that reason.
 *   Reaching for the player from inside this lambda defers construction until the tick,
 *   by which time the configured sound path is known. A ClickPlayer parameter would build
 *   the player too early and silently ignore that path forever.
 * @param onFire runs immediately after [play], on the main thread. Read through
 *   [rememberUpdatedState] so it is the CURRENT lambda rather than the one captured when
 *   the countdown started - a card flipped mid-interval does not restart the effect, and
 *   a stale lambda would still believe the answer was hidden and write a second record.
 */
@Composable
fun MetronomeEffect(
	enabled: Boolean,
	intervalSeconds: Float,
	cardKey: Any?,
	isFlipScreen: Boolean,
	isResumed: Boolean,
	play: () -> Unit,
	onFire: () -> Unit
) {
	val currentPlay by rememberUpdatedState(play)
	val currentOnFire by rememberUpdatedState(onFire)
	LaunchedEffect(enabled, intervalSeconds, cardKey, isFlipScreen, isResumed) {
		// All three gates, plus a card to count down for: cardKey is null before a deck
		// loads, and a metronome with nothing to advance to must not tick.
		if (!enabled || !isFlipScreen || !isResumed || cardKey == null) return@LaunchedEffect
		val millis = (intervalSeconds * MILLIS_PER_SECOND).toLong()
		// A non-positive interval would fire instantly, advance the card, and be restarted
		// by the new key - a spin at full speed that no click could be heard through. It
		// can only come from a hand-edited settings.json, and stopping is the honest reply.
		if (millis <= 0L) return@LaunchedEffect
		delay(millis)
		// Deliberately not wrapped in withContext: this body is dispatched on
		// AndroidUiDispatcher.Main, which is what SoundPoolClickPlayer's unsynchronized
		// fields and its Toast on a bad sound path both require.
		currentPlay()
		currentOnFire()
	}
}

private const val MILLIS_PER_SECOND = 1000f
