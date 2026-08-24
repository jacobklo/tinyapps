package net.jacoblo.simpleanki

import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DrillItem
import net.jacoblo.simpleanki.data.DrillRun
import net.jacoblo.simpleanki.data.DrillRunsRepository
import net.jacoblo.simpleanki.data.ItemStatus
import net.jacoblo.simpleanki.drill.DrillKind
import net.jacoblo.simpleanki.drill.runsFile
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers what the coalescer does with the write it is holding: which list reaches the file, what
 * a flush with nothing pending costs, and where a failed write goes.
 *
 * Its own file rather than a section of DrillRunsRepositoryTest, because the thing under test is
 * not the repository - a reader looking for these would not think to open a file named after one.
 *
 * Real files under a TemporaryFolder rather than a fake repository. DrillAutosave is four fields
 * and two monitors; what it can get wrong is what ends up on disk, and a fake would only assert
 * that it called the method the test already knows it calls.
 *
 * An unreadable file is spelled as a DIRECTORY at the file's path, matching the other repository
 * tests: it exists and writing it is refused, the same shape a permission failure takes on
 * device, and unlike chmod it behaves identically whatever user the tests run as.
 */
class DrillAutosaveTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	@Test
	fun aFlushWritesWhatTheLastScheduleLeftPending() {
		val autosave = autosave()

		autosave.schedule(listOf(runOf(1L)))
		// The coalescing this class exists for: a burst of scoring taps schedules once per tap,
		// and only the last of them decides what the file ends up saying.
		autosave.schedule(listOf(runOf(1L), runOf(2L)))
		autosave.flush()

		assertNull(autosave.takeFailure())
		assertEquals(listOf("1", "2"), storedIds())
	}

	@Test
	fun aFlushWithNothingPendingWritesNothingAtAll() {
		val autosave = autosave()

		autosave.flush()

		// Not merely "wrote the same thing twice": the drill screen flushes on every New and on
		// every pause, so a flush that wrote unconditionally would rewrite the whole file at a
		// user who is only looking at a fresh grid, and would leave a runs file behind for a
		// drill that was never run.
		assertFalse(numbersFile().exists())
		assertNull(autosave.takeFailure())
	}

	@Test
	fun aRunAlreadyFlushedIsNotWrittenAgain() {
		val autosave = autosave()
		autosave.schedule(listOf(runOf(1L)))
		autosave.flush()

		// The debounced write and the flushes on pause, on leaving the screen and on New all
		// race for the same pending list, and every one of them is allowed to lose. Losing has
		// to be a no-op: this second flush stands for the main-thread one arriving after the
		// background write has already been and gone.
		assertTrue(numbersFile().delete())
		autosave.flush()
		assertFalse(numbersFile().exists())
	}

	@Test
	fun aFailedWriteIsHeldForTheCallerRatherThanThrown() {
		assertTrue(numbersFile().mkdirs())
		val autosave = autosave()
		autosave.schedule(listOf(runOf(1L)))

		// Held rather than thrown: one caller is an onDispose and another is a lifecycle
		// callback, and neither is anywhere an exception can be carried out of.
		autosave.flush()

		val failure = autosave.takeFailure()
		assertTrue(failure!!.message, failure.message!!.startsWith("refusing to overwrite"))
	}

	@Test
	fun aFailureOutlivesTheFlushThatCausedItAndIsTakenOnlyOnce() {
		assertTrue(numbersFile().mkdirs())
		val autosave = autosave()
		autosave.schedule(listOf(runOf(1L)))
		autosave.flush()

		// The point of holding it. The debounced flush runs inside a coroutine that the screen
		// leaving cancels, so its continuation - the toast - never gets to run; the flush on the
		// way out is what picks the failure up, and it is a DIFFERENT call from the one that
		// failed. Without this the write would fail in silence and requirement 5's toast would
		// simply not appear.
		assertNotNullFailure(autosave)

		// Cleared as it is handed over, so the next flush does not toast about a write that has
		// already been reported once.
		assertNull(autosave.takeFailure())
	}

	@Test
	fun aFailedWriteIsNotRetriedByTheNextFlush() {
		assertTrue(numbersFile().mkdirs())
		val autosave = autosave()
		autosave.schedule(listOf(runOf(1L)))
		autosave.flush()
		assertNotNullFailure(autosave)

		// Dropped rather than kept for a retry, matching TableRoute's "screen ahead of disk": the
		// next load reconciles the file, where a retry against one that keeps refusing would
		// raise the same toast at every flush for the rest of the session.
		autosave.flush()
		assertNull(autosave.takeFailure())
	}

	// ---------------------------------------------------------------------------
	// Fixtures
	// ---------------------------------------------------------------------------

	/** Takes the failure and asserts there was one, for the tests that only need it gone. */
	private fun assertNotNullFailure(autosave: DrillAutosave) {
		assertTrue("a refused write must leave a failure behind", autosave.takeFailure() != null)
	}

	private fun numbersFile(): File = DrillKind.NUMBERS.runsFile(AnkiPaths.at(tempFolder.root))

	private fun autosave(): DrillAutosave = DrillAutosave(DrillRunsRepository(numbersFile()))

	private fun runOf(startedAt: Long): DrillRun = DrillRun(
		id = startedAt.toString(),
		startedAt = startedAt,
		seconds = 1.5f,
		items = listOf(DrillItem("07", ItemStatus.RIGHT), DrillItem("42"))
	)

	private fun storedIds(): List<String> {
		val array = JSONArray(numbersFile().readText())
		return (0 until array.length()).map { array.getJSONObject(it).getString("id") }
	}
}
