package net.jacoblo.simpleanki.metronome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.IOException

/** The metronome tick. One sound, played for every tick including a timeout. */
interface ClickPlayer {
	fun play()

	fun release()
}

/**
 * Plays through the media stream, so the click follows the media volume and is silent at
 * index zero.
 *
 * It is NOT silenced by the ringer being on silent: that mutes the ring and notification
 * streams, never STREAM_MUSIC. Which is the behaviour to want here - the metronome is a
 * study aid the user deliberately turned on, and silencing it because the phone should not
 * ring would stop the session without explaining why.
 *
 * Resolution order: [soundPath] when set and readable, else the bundled click.wav asset,
 * else silence. A configured path that cannot be read is reported through [onLoadFailure]
 * once, and the bundled asset is used instead.
 *
 * Everything here runs on the thread that constructed the player. SoundPool delivers its
 * load callback on that thread's Looper, and the metronome ticks from the main thread, so
 * [loaded] and [pendingPlay] are never touched concurrently.
 *
 * @param onLoadFailure receives the configured path that could not be used. Called at most
 *   once per instance, which is once per app launch.
 */
class SoundPoolClickPlayer(
	context: Context,
	soundPath: String?,
	private val onLoadFailure: (String) -> Unit
) : ClickPlayer {

	private val pool = SoundPool.Builder()
		.setMaxStreams(1)
		.setAudioAttributes(
			AudioAttributes.Builder()
				// USAGE_MEDIA rather than USAGE_ASSISTANCE_SONIFICATION so the click rides the
				// media volume the user already set for this app, rather than a system volume
				// they never think about. SONIFICATION as the content type still tells the
				// mixer this is a short cue, not music, so it is not treated as a track.
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.build()
		)
		.build()

	/**
	 * The path handed to SoundPool, or null when the bundled asset was loaded.
	 *
	 * Assigned once, from init. A var only because the load callback below reads it, and a
	 * val assigned in init cannot be captured by a lambda declared there.
	 */
	private var loadedPath: String? = null

	/** Zero when nothing could be loaded at all, in which case [play] is silent. */
	private var soundId = 0

	private var loaded = false

	/**
	 * A [play] that arrived before the sample finished loading, replayed by the load
	 * callback. Without it the first tick after launch would be dropped, since
	 * SoundPool.load returns an id long before the sample is playable.
	 */
	private var pendingPlay = false

	private var released = false

	init {
		pool.setOnLoadCompleteListener { _, sampleId, status ->
			if (sampleId != soundId) return@setOnLoadCompleteListener
			if (status == 0) {
				loaded = true
				if (pendingPlay) {
					pendingPlay = false
					play()
				}
			} else {
				// A file that reads but will not decode fails only here. Report it rather
				// than leaving the user with silent ticks and nothing to go on; there is no
				// second attempt at the asset because the tick is not worth a retry path.
				loadedPath?.let(onLoadFailure)
			}
		}
		val configured = soundPath?.takeIf { canRead(it) }
		if (soundPath != null && configured == null) onLoadFailure(soundPath)
		loadedPath = configured
		soundId = if (configured != null) pool.load(configured, 1) else loadAsset(context)
	}

	override fun play() {
		if (released || soundId == 0) return
		if (!loaded) {
			pendingPlay = true
			return
		}
		// Left at full scale: the stream volume the AudioAttributes select is what the user
		// turns down, so attenuating here as well would just make the click quiet twice.
		pool.play(soundId, 1f, 1f, 1, 0, 1f)
	}

	override fun release() {
		if (released) return
		released = true
		pendingPlay = false
		loaded = false
		pool.release()
	}

	/**
	 * Whether SoundPool will be able to open [path].
	 *
	 * Checked here because SoundPool.load reports a failure only through the async
	 * listener, by which point falling back has to be done re-entrantly.
	 */
	private fun canRead(path: String): Boolean = try {
		File(path).canRead()
	} catch (_: SecurityException) {
		false
	}

	private fun loadAsset(context: Context): Int = try {
		context.assets.openFd(ASSET_NAME).use { pool.load(it, 1) }
	} catch (_: IOException) {
		0
	}

	private companion object {
		const val ASSET_NAME = "click.wav"
	}
}

/** Used by test mode and JVM tests, so an automated run stays silent. */
object NoOpClickPlayer : ClickPlayer {
	override fun play() = Unit

	override fun release() = Unit
}
