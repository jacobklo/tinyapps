/*
 * The coalesced write behind a drill's scoring taps.
 *
 * Its own file rather than a tail on DrillRoute.kt because it is the one piece of that route
 * with rules a test can reach: no Compose, no Android, just a runs file and a pending list. The
 * route around it is wiring.
 */
package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.DrillRunsRepository
import java.io.IOException

/**
 * The pending write for one drill's runs file, coalesced so that no scoring tap ever waits on
 * one.
 *
 * The spec has every scoring tap autosave, and taken literally that is a rewrite of the entire
 * runs file per tap - at the [MAX_RUNS] retention cap, roughly ten megabytes of JSON on the main
 * thread for each of the fifty-odd taps it takes to score a set. Coalescing is what makes that
 * promise affordable: the list is held here, the caller re-arms a short delay, and only a gap in
 * the tapping reaches [flush]. DrillRoute.flushNow lists the moments that skip the gap.
 *
 * This is a class rather than one more piece of Compose state so that the flushes racing the
 * debounced one can be made safe:
 *
 * 1. The pending list is held HERE and not inside a coroutine, so a debounce cancelled mid-delay
 *    by the screen going away leaves the work behind for the flush that follows instead of taking
 *    it with it. That is the failure this shape exists against, and it is the nastiest one here:
 *    a flush that misses the last taps is invisible until the next load, by which point the marks
 *    are simply gone.
 * 2. TWO locks, not one. [stateLock] is held for the length of a field assignment; [writeLock] is
 *    held across the write itself. One lock over both would be correct and would also block
 *    [schedule] - which runs on the main thread, from a scoring tap - for the whole of a write
 *    already under way. Every tap landing between the debounce firing and that write finishing
 *    would wait on megabytes of JSON inside an input handler, which is the precise cost the
 *    coalescing exists to avoid.
 *
 * The pending list is claimed INSIDE [writeLock] rather than snapshotted before taking it. That
 * ordering is the whole reason two locks are safe: claiming outside would let two flushes carry
 * off two different lists and then race to the file, and an older list landing after a newer one
 * would silently undo the marks in between.
 *
 * Plain monitors and not a Mutex or an actor, because [flush] is called from an onDispose and
 * from a LifecycleEventObserver - neither of them a suspend context - so a Mutex would need a
 * runBlocking to reach and an actor a channel send from a non-suspend callback. @Synchronized is
 * what this shape of caller can actually use.
 *
 * A failed write is HELD for [takeFailure] rather than kept for a retry, which is the same
 * "screen ahead of disk" bargain TableRoute makes. The next load reconciles the file, and a retry
 * against one that keeps refusing would raise the same toast at every flush for the rest of the
 * session.
 */
internal class DrillAutosave(private val repository: DrillRunsRepository) {

	private val stateLock = Any()
	private val writeLock = Any()

	private var pending: List<DrillRun>? = null
	private var failure: IOException? = null

	/**
	 * Holds [runs] as what the file should say, replacing anything already pending.
	 *
	 * Called from the main thread on every scoring tap, and it must stay cheap enough for that:
	 * it takes [stateLock] and assigns one field, and it deliberately does not touch [writeLock].
	 */
	fun schedule(runs: List<DrillRun>) {
		synchronized(stateLock) { pending = runs }
	}

	/**
	 * Writes what is pending, if anything, leaving any failure for [takeFailure].
	 *
	 * Nothing is thrown and nothing is returned. Every caller has to carry on regardless - one is
	 * an onDispose and one is a lifecycle callback - and the toast a failure becomes has to be
	 * raised on the main thread, which is not where the debounced call runs.
	 */
	fun flush() {
		synchronized(writeLock) {
			val runs = synchronized(stateLock) { pending.also { pending = null } } ?: return
			try {
				repository.save(runs, MAX_RUNS)
			} catch (e: IOException) {
				synchronized(stateLock) { failure = e }
			}
		}
	}

	/**
	 * The failure a flush left behind, cleared as it is handed over. Null when there is none.
	 *
	 * Held rather than returned straight out of [flush] because the flush that fails is often not
	 * the call that can report it: the debounced one runs inside a coroutine that the screen
	 * leaving cancels, and its continuation - the toast - never gets to run. Holding it means the
	 * flush on the way out picks the failure up instead, so requirement 5's toast is raised even
	 * when the write that failed was one nobody was left waiting on.
	 *
	 * A second failure before the first is taken REPLACES it. One toast saying the file is
	 * refusing writes is the whole of what a user can act on, and two would only say it twice.
	 */
	fun takeFailure(): IOException? = synchronized(stateLock) { failure.also { failure = null } }
}

/**
 * Runs kept per file, newest first out of the trim, matching history.json's own default.
 *
 * A constant and not a setting: the spec gives the drills the same retention the history log has
 * and adds no field for it. Reading HistorySettings.maxEntries here instead would let a user
 * trimming their card history silently discard drill runs out of two entirely different files.
 */
private const val MAX_RUNS = 5000
