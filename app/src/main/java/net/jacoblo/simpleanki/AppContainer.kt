package net.jacoblo.simpleanki

import android.content.Context
import android.os.Environment
import net.jacoblo.simpleanki.data.AnkiPaths
import net.jacoblo.simpleanki.data.DeckRepository
import net.jacoblo.simpleanki.data.HistoryRepository
import net.jacoblo.simpleanki.data.Settings
import net.jacoblo.simpleanki.data.SettingsRepository

/**
 * Hand-rolled dependency graph, constructed once in MainActivity.onCreate.
 *
 * Context is a constructor parameter from the outset because Task 14 builds
 * SoundPoolClickPlayer from it; taking it later would churn every call site.
 */
class AppContainer(
	private val context: Context,
	val paths: AnkiPaths,
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
	 */
	var settings: Settings = Settings()

	/**
	 * True when the app holds MANAGE_EXTERNAL_STORAGE, without which every path under
	 * [paths] is unreadable and unwritable.
	 *
	 * It sits here rather than in MainActivity so that file keeps no storage knowledge,
	 * and outside AnkiPaths because it reports a permission, not a path.
	 */
	val hasStorageAccess: Boolean
		get() = Environment.isExternalStorageManager()

	/** Releases held native resources. Called from MainActivity.onDestroy. */
	fun release() {
		// Task 14 releases the SoundPool here; nothing holds native resources yet.
	}
}
