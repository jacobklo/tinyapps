package net.jacoblo.autoclicker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private const val TAG = "autoclicker.gesture.executor"

/**
 * Replays recorded interactions through whichever backend the user selected.
 *
 * Coordinate randomisation lives here so both backends jitter identically and
 * the backends only ever inject final coordinates. The root backend maps a drag
 * onto a single `input swipe`, which interpolates a straight line: recorded
 * intermediate points and their per-point timing survive only on the
 * accessibility backend.
 */
object GestureExecutor {

	private val scope = CoroutineScope(Dispatchers.Main + Job())

	fun isReady(): Boolean =
		if (AppSettings.useRoot) RootShell.isOpen else RecorderService.instance != null

	fun playRecording(events: List<Interaction>, globalRandom: Int = 0) {
		scope.launch { executeEvents(events, globalRandom) }
	}

	fun click(x: Float, y: Float, duration: Long, randomFactor: Int, onDone: (() -> Unit)? = null) {
		scope.launch {
			runClick(x, y, duration, randomFactor)
			onDone?.invoke()
		}
	}

	fun drag(
		points: List<DragPoint>,
		randomFactorStart: Int,
		randomFactorHighest: Int,
		onDone: (() -> Unit)? = null
	) {
		scope.launch {
			runDrag(points, randomFactorStart, randomFactorHighest)
			onDone?.invoke()
		}
	}

	private suspend fun executeEvents(events: List<Interaction>, globalRandom: Int) {
		events.forEach { event ->
			val randDelay = if (globalRandom > 0) Random.nextInt(0, globalRandom + 1) else 0
			delay(event.delayBefore + randDelay)

			when (event) {
				is ClickInteraction -> runClick(event.x, event.y, event.duration, event.randomFactor)
				is DragInteraction -> runDrag(event.points, event.randomFactorStart, event.randomFactorHighest)
				is TextInteraction -> runText(event.text)
				is ForLoopInteraction -> {
					repeat(event.repeatCount) {
						executeEvents(event.interactions, globalRandom)
					}
				}
				is RandomSelectInteraction -> {
					if (event.interactions.isNotEmpty()) {
						executeEvents(listOf(event.interactions.random()), globalRandom)
					}
				}
				else -> {
					// Editor-only markers never reach playback
				}
			}
		}
	}

	private suspend fun runClick(x: Float, y: Float, duration: Long, randomFactor: Int) {
		val finalX = x + jitter(randomFactor)
		val finalY = y + jitter(randomFactor)

		if (AppSettings.useRoot) {
			val px = finalX.roundToInt()
			val py = finalY.roundToInt()
			// A zero-length swipe is the only `input` form that honours a press duration.
			withContext(Dispatchers.IO) { RootShell.swipe(px, py, px, py, duration) }
			return
		}

		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping click")
			return
		}
		awaitDispatch { done -> service.dispatchClick(finalX, finalY, duration, done) }
	}

	private suspend fun runDrag(points: List<DragPoint>, randomFactorStart: Int, randomFactorHighest: Int) {
		if (points.isEmpty()) return
		val randomized = randomizePath(points, randomFactorStart, randomFactorHighest)

		if (AppSettings.useRoot) {
			val first = randomized.first()
			val last = randomized.last()
			val duration = randomized.sumOf { it.dt }
			withContext(Dispatchers.IO) {
				RootShell.swipe(
					first.x.roundToInt(), first.y.roundToInt(),
					last.x.roundToInt(), last.y.roundToInt(),
					duration
				)
			}
			return
		}

		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping drag")
			return
		}
		awaitDispatch { done -> service.dispatchDrag(randomized, done) }
	}

	private suspend fun runText(text: String) {
		if (text.isEmpty()) return

		if (AppSettings.useRoot) {
			withContext(Dispatchers.IO) { RootShell.text(text) }
			return
		}

		val service = RecorderService.instance
		if (service == null) {
			Log.w(TAG, "no accessibility service, dropping text")
			return
		}
		service.dispatchText(text)
	}

	private suspend fun awaitDispatch(dispatch: (() -> Unit) -> Unit) =
		suspendCancellableCoroutine { cont ->
			dispatch { if (cont.isActive) cont.resume(Unit) }
		}

	private fun jitter(factor: Int): Int =
		if (factor > 0) Random.nextInt(-factor, factor + 1) else 0

	private fun randomizePath(
		points: List<DragPoint>,
		randomFactorStart: Int,
		randomFactorHighest: Int
	): List<DragPoint> {
		if (points.size <= 1) {
			return points.map {
				DragPoint(it.x + jitter(randomFactorStart), it.y + jitter(randomFactorStart), it.dt)
			}
		}

		val mid = (points.size - 1) / 2.0
		return points.mapIndexed { index, point ->
			// Jitter ramps from randomFactorStart at both ends to randomFactorHighest at the middle.
			val t = (1.0 - abs(index - mid) / mid).coerceIn(0.0, 1.0)
			val factor = (randomFactorStart + (randomFactorHighest - randomFactorStart) * t).toInt()
			DragPoint(point.x + jitter(factor), point.y + jitter(factor), point.dt)
		}
	}
}
