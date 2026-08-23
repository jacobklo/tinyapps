package net.jacoblo.simpleanki

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DeckRepository
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository
import net.jacoblo.simpleanki.table.RenderedTable
import net.jacoblo.simpleanki.testmode.TestMode

/**
 * Hand-rolled dependency graph, constructed once in MainActivity.onCreate.
 *
 * Context is a constructor parameter from the outset because Task 14 builds
 * SoundPoolClickPlayer from it; taking it later would churn every call site.
 */
class AppContainer(
	private val context: Context,
	val paths: AnkiPaths,
	/**
	 * True when the app was launched under TestMode, which only a debug build can be.
	 *
	 * Carried here rather than re-derived because the intent is not reachable from a
	 * composable, and because [paths] alone must never be what test-mode behaviour keys
	 * off - reading the directory name to decide how to behave is how a production path
	 * eventually gets treated as a test one.
	 */
	val testMode: Boolean
) {
	val deckRepository = DeckRepository(paths)
	val historyRepository = HistoryRepository(paths)
	val settingsRepository = SettingsRepository(paths)
	// Task 8 adds: viewsRepository.
	// Task 14 adds: clickPlayer, selected on `testMode`.

	/**
	 * The settings in force: reloaded from disk on every resume, and rewritten whenever
	 * the lifetime review counter advances.
	 *
	 * Defaults until that first load, because the container is built before storage
	 * permission has been checked and settings.json may not be readable yet.
	 *
	 * Compose state rather than a plain field so the top bar's review count reads
	 * straight through and recomposes on its own. Mirroring it into a second remembered
	 * value would let the badge and the stored count drift without anything noticing.
	 */
	var settings by mutableStateOf(Settings())

	/**
	 * True when the app holds MANAGE_EXTERNAL_STORAGE, without which every path under
	 * [paths] is unreadable and unwritable.
	 *
	 * It sits here rather than in MainActivity so that file keeps no storage knowledge,
	 * and outside AnkiPaths because it reports a permission, not a path.
	 */
	val hasStorageAccess: Boolean
		get() = Environment.isExternalStorageManager()

	/**
	 * Seeds the test-mode fixtures once per launch, on the first call that has storage
	 * access. A no-op outside test mode.
	 *
	 * Deferred rather than run from the constructor because without
	 * MANAGE_EXTERNAL_STORAGE the first write fails with EPERM, and failing that way
	 * inside onCreate kills the process before onResume can show the permission prompt -
	 * which leaves the app unable to heal itself. MainActivity calls this from both, so
	 * the resume after the grant seeds and the run carries on.
	 *
	 * The [seeded] latch matters as much as the permission check does: without it every
	 * resume would wipe, discarding whatever the agent had just recorded.
	 */
	fun seedTestModeIfNeeded() {
		if (!testMode || seeded || !hasStorageAccess) return
		TestMode.seed(paths)
		seeded = true
	}

	/** Whether [seedTestModeIfNeeded] has run. An activity recreation resets it. */
	private var seeded = false

	/**
	 * Writes [table] to dump.json under test mode, and does nothing otherwise.
	 *
	 * The test-mode check sits here rather than at the call site so TableScreen's
	 * onRendered can be wired unconditionally: a screen that has to remember to ask
	 * whether it is under test is a screen that will one day forget to.
	 */
	fun dumpRendered(table: RenderedTable) {
		if (testMode) TestMode.writeDump(paths, table)
	}

	/** Releases held native resources. Called from MainActivity.onDestroy. */
	fun release() {
		// Task 14 releases the SoundPool here; nothing holds native resources yet.
	}
}
