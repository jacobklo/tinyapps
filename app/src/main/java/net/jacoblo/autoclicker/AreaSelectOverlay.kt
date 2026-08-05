package net.jacoblo.autoclicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Below this the selection is treated as a stray tap rather than a drag. In dp,
// so the same flick of the finger means the same thing whatever the density: 8
// raw pixels is a third of that on a 420dpi phone and half again on a 640dpi
// one, which made a deliberate small selection register as a cancel.
private const val MIN_SELECTION_DP = 8f

// A hairline on a dense screen and a slab on a sparse one if left in pixels.
private const val BORDER_DP = 2f

/**
 * Full-screen overlay for dragging out a rectangle, system-wide.
 *
 * It dims the screen and punches a clear hole where the selection is, so the
 * user can see what they are about to capture.
 */
class AreaSelectOverlay(
	private val context: Context,
	private val onSelected: (Rect) -> Unit,
	private val onCancelled: () -> Unit
) {

	private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
	private var view: SelectionView? = null

	@SuppressLint("ClickableViewAccessibility")
	fun show() {
		if (view != null) return

		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
				WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
			PixelFormat.TRANSLUCENT
		).apply {
			// Reclaims the navigation bar strip, which is otherwise inset away
			// and left undimmed. The status bar cannot be reclaimed the same
			// way: WindowManager confines an application overlay below it, so
			// the view's origin is not the top of the screen and every
			// coordinate has to be translated by getLocationOnScreen.
			fitInsetsTypes = 0
			// Without this the window stops at the cutout instead of running
			// under it, which loses a strip of selectable screen in landscape.
			layoutInDisplayCutoutMode =
				WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
		}

		val selection = SelectionView(context) { rect ->
			if (rect == null) {
				remove()
				onCancelled()
			} else {
				// The caller captures, so the overlay must be gone first or it
				// would appear in the screenshot.
				remove()
				onSelected(rect)
			}
		}

		view = selection
		windowManager.addView(selection, params)
	}

	fun remove() {
		view?.let {
			try {
				windowManager.removeView(it)
			} catch (e: IllegalArgumentException) {
				// Already detached.
			}
		}
		view = null
	}

	@SuppressLint("ViewConstructor")
	private class SelectionView(
		context: Context,
		private val onDone: (Rect?) -> Unit
	) : View(context) {

		// Screen coordinates, because that is what the caller crops with.
		private var startX = 0f
		private var startY = 0f
		private var currentX = 0f
		private var currentY = 0f
		private var dragging = false

		private val originOnScreen = IntArray(2)

		private val density = resources.displayMetrics.density
		private val minSelection = MIN_SELECTION_DP * density

		private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
		private val borderPaint = Paint().apply {
			color = Color.YELLOW
			style = Paint.Style.STROKE
			strokeWidth = BORDER_DP * density
			isAntiAlias = true
		}
		private val clearPaint = Paint().apply {
			xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
		}

		init {
			// The CLEAR xfermode needs its own layer to punch through the dim.
			setLayerType(LAYER_TYPE_SOFTWARE, null)
		}

		override fun onDraw(canvas: Canvas) {
			canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
			if (!dragging) return
			// The touch is in screen coordinates but the canvas is the view's
			// own. Drawing one in the other put the rectangle a status bar
			// lower than the finger, so an area framed inside it was captured
			// from that far above.
			val rect = selectionRect()
			rect.offset(-originOnScreen[0], -originOnScreen[1])
			canvas.drawRect(rect, clearPaint)
			canvas.drawRect(rect, borderPaint)
		}

		override fun onTouchEvent(event: MotionEvent): Boolean {
			when (event.action) {
				MotionEvent.ACTION_DOWN -> {
					// Re-read per gesture: the window can be laid out differently
					// after a rotation or an inset change.
					getLocationOnScreen(originOnScreen)
					startX = event.rawX
					startY = event.rawY
					currentX = startX
					currentY = startY
					dragging = true
					invalidate()
				}
				MotionEvent.ACTION_MOVE -> {
					currentX = event.rawX
					currentY = event.rawY
					invalidate()
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					dragging = false
					val rect = selectionRect()
					if (abs(currentX - startX) < minSelection || abs(currentY - startY) < minSelection) {
						onDone(null)
					} else {
						onDone(rect)
					}
				}
			}
			return true
		}

		// Normalised so dragging in any direction gives a positive rectangle.
		private fun selectionRect() = Rect(
			min(startX, currentX).toInt(),
			min(startY, currentY).toInt(),
			max(startX, currentX).toInt(),
			max(startY, currentY).toInt()
		)
	}
}
