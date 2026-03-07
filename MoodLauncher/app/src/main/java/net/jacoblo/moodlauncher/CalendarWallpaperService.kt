package net.jacoblo.moodlauncher

import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CalendarWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CalendarEngine()

    inner class CalendarEngine : Engine() {

        private val job = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Main + job)
        private val handler = Handler(Looper.getMainLooper())

        private val renderer by lazy {
            YearCalendarRenderer(resources.displayMetrics.density)
        }
        private val prefs by lazy { CalendarPreferences(applicationContext) }
        private val repo by lazy { NotesRepository(applicationContext) }

        private var notes: Map<String, DayNote> = emptyMap()

        private val redrawRunnable = Runnable {
            drawFrame()
            scheduleRedraw()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            loadAndDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                loadAndDraw()
                scheduleRedraw()
            } else {
                handler.removeCallbacks(redrawRunnable)
            }
        }

        override fun onDestroy() {
            handler.removeCallbacks(redrawRunnable)
            job.cancel()
        }

        private fun loadAndDraw() {
            scope.launch {
                notes = repo.loadNotes()
                drawFrame()
            }
        }

        // Redraw every minute so today's red circle reflects the current date
        private fun scheduleRedraw() {
            handler.removeCallbacks(redrawRunnable)
            handler.postDelayed(redrawRunnable, 60_000L)
        }

        private fun drawFrame() {
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                renderer.draw(
                    canvas,
                    canvas.width,
                    canvas.height,
                    notes,
                    prefs.getFontColor().toArgb(),
                    prefs.getBackgroundColor().toArgb(),
                    prefs.getTextScale()
                )
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
