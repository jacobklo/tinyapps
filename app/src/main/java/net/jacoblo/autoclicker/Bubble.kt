package net.jacoblo.autoclicker

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// Long enough for the hidden bubble to actually leave the composited frame.
private const val CAPTURE_HIDE_DELAY_MS = 150L

class Bubble(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private var closeAreaView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var closeAreaParams: WindowManager.LayoutParams? = null
    private var recordingParams: WindowManager.LayoutParams? = null

    private val bubbleSize = 100
    private val closeAreaSize = 100

    private var isRecording = false
    private var recordingOverlay: View? = null
    private val recordedEvents = mutableListOf<Interaction>()
    private var lastEventTime = 0L

    // New variables for tracking multi-point drag
    private val currentDragPoints = mutableListOf<DragPoint>()
    private var lastDragPointTime = 0L

    private var recordButtonView: View? = null
    private var recordButtonIcon: ImageView? = null
    private var playButtonIcon: ImageView? = null
    private var areaOverlay: AreaSelectOverlay? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubbleView != null) return

        // Setup Close Area (Trash bin)
        closeAreaView = FrameLayout(context).apply {
            background = ShapeDrawable(OvalShape()).apply {
                paint.color = Color.RED
                paint.alpha = 0 // Initially invisible
            }
            // Add an X icon
            val icon = ImageView(context)
            icon.setImageResource(R.drawable.ic_close)
            icon.setColorFilter(Color.WHITE)
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        closeAreaParams = WindowManager.LayoutParams(
            closeAreaSize,
            closeAreaSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        windowManager.addView(closeAreaView, closeAreaParams)


        // Setup Bubble with buttons
        bubbleView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            // Toggle Button: Start/Stop Recording
            // Initially Start Recording (Red)
            recordButtonView = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = Color.RED
                }

                recordButtonIcon = ImageView(context)
                recordButtonIcon?.setImageResource(R.drawable.ic_record)
                recordButtonIcon?.setColorFilter(Color.WHITE)
                recordButtonIcon?.setPadding(25, 25, 25, 25)
                addView(recordButtonIcon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                setOnClickListener {
                    if (isRecording) {
                        stopRecording()
                    } else {
                        startRecording()
                    }
                }
            }
            addView(recordButtonView, LinearLayout.LayoutParams(bubbleSize, bubbleSize))

            // Button 3: Play / Stop (Blue)
            val playButton = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = 0xFF2196F3.toInt() // Material Blue
                }
                playButtonIcon = ImageView(context)
                playButtonIcon?.setImageResource(R.drawable.ic_play)
                playButtonIcon?.setColorFilter(Color.WHITE)
                playButtonIcon?.setPadding(20, 20, 20, 20)
                addView(playButtonIcon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                setOnClickListener {
                    if (GestureExecutor.isPlaying) {
                        GestureExecutor.stop()
                        showPlayIdle()
                        Toast.makeText(context, "Stopped", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val file = RecordingManager.currentSelectedFile
                    if (file == null) {
                        Toast.makeText(context, "Select a recording first", Toast.LENGTH_SHORT).show()
                    } else if (!GestureExecutor.isReady()) {
                        Toast.makeText(context, "Gesture backend not ready", Toast.LENGTH_SHORT).show()
                    } else {
                        val data = RecordingManager.loadRecording(file)
                        playButtonIcon?.setImageResource(R.drawable.ic_stop)
                        // Restores the icon however playback ends, including
                        // when it finishes on its own rather than being stopped.
                        GestureExecutor.playRecording(data.events, data.globalRandom) {
                            showPlayIdle()
                        }
                        Toast.makeText(context, "Playing ${file.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val paramsPlay = LinearLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                leftMargin = 10
            }
            addView(playButton, paramsPlay)

            // Button 4: Capture a screen area (Yellow)
            val screenshotButton = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = 0xFFFFC107.toInt() // Material Amber
                }
                val icon = ImageView(context)
                icon.setImageResource(R.drawable.ic_crop)
                icon.setColorFilter(Color.WHITE)
                icon.setPadding(22, 22, 22, 22)
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                setOnClickListener { startAreaCapture() }
            }
            val paramsShot = LinearLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                leftMargin = 10
            }
            addView(screenshotButton, paramsShot)

            // Button 5: for dragging all these buttons
            val dragButton = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = 0x000000F3.toInt() // Material Blue
                }
                val icon = ImageView(context)
                icon.setImageResource(R.drawable.ic_drag)
                icon.setColorFilter(Color.WHITE)
                icon.setPadding(5, 5, 5, 5)
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            val paramsDrag = LinearLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                leftMargin = 10
            }
            addView(dragButton, paramsDrag)
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            bubbleSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams!!.x
                        initialY = bubbleParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        showCloseArea()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                        }

                        bubbleParams!!.x = initialX + dx
                        bubbleParams!!.y = initialY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)

                        checkInCloseArea()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        hideCloseArea()
                        if (isInCloseArea()) {
                            stopService()
                        }

                        if (!isDragging) {
                            val location = IntArray(2)
                            v.getLocationOnScreen(location)
                            val x = event.rawX - location[0]

                            val container = v as LinearLayout
                            for (i in 0 until container.childCount) {
                                val child = container.getChildAt(i)
                                if (x >= child.left && x <= child.right) {
                                    child.performClick()
                                    break
                                }
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun startRecording() {
        if (isRecording) return

        if (!GestureExecutor.isReady()) {
            if (AppSettings.useRoot) {
                Toast.makeText(context, "Root access not granted", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Please enable Accessibility Service for AutoClicker", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
            return
        }

        isRecording = true
        bubbleView?.invalidate()

        // Update to Stop button style (Green)
        (recordButtonView?.background as? ShapeDrawable)?.paint?.color = Color.GREEN
        // Use Stop icon for stopping recording
        recordButtonIcon?.setImageResource(R.drawable.ic_stop)
        recordButtonView?.invalidate()

        recordedEvents.clear()
        lastEventTime = System.currentTimeMillis()

        // Under root the touchscreen is read directly, so no overlay is needed:
        // the finger's own events reach the app untouched and are only mirrored.
        if (startEvdevRecording()) {
            Toast.makeText(context, "Recording Started (root)", Toast.LENGTH_SHORT).show()
            return
        }

        setupRecordingOverlay()

        // Bring bubble to front by re-adding it
        bubbleView?.let {
            windowManager.removeView(it)
            windowManager.addView(it, bubbleParams)
        }

        Toast.makeText(context, "Recording Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        isRecording = false
        bubbleView?.invalidate()

        // Update to Start button style (Red)
        (recordButtonView?.background as? ShapeDrawable)?.paint?.color = Color.RED
        // Use Record icon for starting recording
        recordButtonIcon?.setImageResource(R.drawable.ic_record)
        recordButtonView?.invalidate()

        EvdevRecorder.stop()
        removeRecordingOverlay()
        RecordingManager.saveRecording(recordedEvents)
        Toast.makeText(context, "Recording Saved", Toast.LENGTH_SHORT).show()
    }

    /**
     * Starts passive touchscreen capture. Returns false when root evdev is not
     * available, so the caller falls back to the overlay recorder.
     */
    /**
     * Drag out a region, then capture it.
     *
     * Both the selection overlay and the bubble itself have to be off screen
     * before screencap runs, or they end up baked into the saved image. The
     * overlay removes itself on release; the bubble is hidden here and restored
     * once the capture has been read.
     */
    private fun startAreaCapture() {
        if (!AppSettings.useRoot || !RootShell.isOpen) {
            Toast.makeText(context, "Screen capture needs root", Toast.LENGTH_LONG).show()
            return
        }
        if (areaOverlay != null) return

        areaOverlay = AreaSelectOverlay(
            context = context,
            onSelected = { rect ->
                areaOverlay = null
                captureRegion(rect)
            },
            onCancelled = {
                areaOverlay = null
                Toast.makeText(context, "Capture cancelled", Toast.LENGTH_SHORT).show()
            }
        )
        areaOverlay?.show()
        Toast.makeText(context, "Drag to select an area", Toast.LENGTH_SHORT).show()
    }

    private fun captureRegion(rect: Rect) {
        bubbleView?.visibility = View.INVISIBLE
        closeAreaView?.visibility = View.INVISIBLE

        // One frame is not always enough for the compositor to drop the hidden
        // windows, so give it a short beat before grabbing the screen.
        bubbleView?.postDelayed({
            Thread {
                val screen = ScreenGeometry.current(context)
                val frame = ScreenCapture.capture()
                val bitmap = frame?.let { ScreenCapture.crop(it, rect) }
                val saved = bitmap?.let { ScreenshotStore.save(it, rect, screen) }

                bubbleView?.post {
                    bubbleView?.visibility = View.VISIBLE
                    closeAreaView?.visibility = View.VISIBLE
                    val message = if (saved == null) "Capture failed" else "Saved ${saved.name}"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }, CAPTURE_HIDE_DELAY_MS)
    }

    /** Playback can end on its own, so the icon is reset from a callback. */
    private fun showPlayIdle() {
        playButtonIcon?.post {
            playButtonIcon?.setImageResource(R.drawable.ic_play)
        }
    }

    /**
     * Keeps the play/stop icon honest for a script this bubble did not start.
     * Playback is also driven over the control server, and a button still showing
     * "play" while a script runs reads as "nothing is running".
     */
    fun setPlaying(playing: Boolean) {
        playButtonIcon?.post {
            playButtonIcon?.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        }
    }

    private fun startEvdevRecording(): Boolean {
        if (!AppSettings.useRoot) return false
        val device = GestureExecutor.evdevDevice ?: return false
        val mainThread = Handler(Looper.getMainLooper())
        return EvdevRecorder.start(
            device = device,
            shouldIgnore = { x, y -> isOnBubble(x, y) },
            // Hop to the main thread so recordedEvents is only ever touched
            // there, exactly as the overlay recorder does.
            onGesture = { interaction -> mainThread.post { recordedEvents.add(interaction) } }
        )
    }

    /**
     * Without a capture overlay the bubble's own buttons are ordinary screen
     * area, so the tap that stops recording would otherwise be recorded too.
     */
    private fun isOnBubble(x: Float, y: Float): Boolean {
        val view = bubbleView ?: return false
        // Read the real on-screen position rather than bubbleParams: the window
        // is laid out below the status bar, so params.y sits ~1 inset too high.
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + view.width &&
                y >= location[1] && y <= location[1] + view.height
    }

    private fun setupRecordingOverlay() {
        // Remove existing if present (prevents duplicates)
        removeRecordingOverlay()

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        recordingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            // We want to receive touches, so NO FLAG_NOT_TOUCHABLE
            // We want it full screen, covering everything
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        recordingOverlay = View(context).apply {
            // Invisible but touchable
            setBackgroundColor(Color.TRANSPARENT)

            setOnTouchListener { _, event ->
                handleRecordingTouch(event)
                true // Consume event
            }
        }

        windowManager.addView(recordingOverlay, recordingParams)
    }

    private fun removeRecordingOverlay() {
        recordingOverlay?.let {
            windowManager.removeView(it)
            recordingOverlay = null
        }
    }

    private var startX = 0f
    private var startY = 0f
    private var touchStartTime = 0L

    // 3) Rewrite to handle multiple coordinates recording
    private fun handleRecordingTouch(event: MotionEvent) {
        val currentTime = System.currentTimeMillis()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                touchStartTime = currentTime

                // Start a new drag path
                currentDragPoints.clear()
                currentDragPoints.add(DragPoint(startX, startY, 0))
                lastDragPointTime = currentTime

                // Change button to WHITE to indicate input detection
                (recordButtonView?.background as? ShapeDrawable)?.paint?.color = Color.WHITE
                recordButtonView?.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.rawX
                val y = event.rawY
                val dt = currentTime - lastDragPointTime
                currentDragPoints.add(DragPoint(x, y, dt))
                lastDragPointTime = currentTime
            }
            MotionEvent.ACTION_UP -> {
                val endX = event.rawX
                val endY = event.rawY
                val duration = currentTime - touchStartTime
                val delay = touchStartTime - lastEventTime

                val distance = sqrt((endX - startX).pow(2) + (endY - startY).pow(2))

                // Restore button to GREEN (Recording state)
                (recordButtonView?.background as? ShapeDrawable)?.paint?.color = Color.GREEN
                recordButtonView?.invalidate()

                // Make overlay non-touchable so the injected gesture can pass through
                recordingParams?.let { params ->
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(recordingOverlay, params)
                }

                // HACK: multiple same event occur in very short time. Check if delay is longer than 250ms to add into record.
                if (delay < 250) {
                    return
                }

                // Callback to restore overlay touchability after gesture injection finishes
                val completionCallback: () -> Unit = {
                    recordingOverlay?.post {
                        recordingParams?.let { params ->
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                            windowManager.updateViewLayout(recordingOverlay, params)
                        }
                    }
                }

                // The overlay reports pixels; interactions are stored and
                // replayed as fractions of the screen.
                val screen = ScreenGeometry.current(context)

                if (distance < 20) {
                    // Click
                    val fx = startX / screen.width
                    val fy = startY / screen.height
                    recordedEvents.add(ClickInteraction(fx, fy, duration, 0, delayBefore = delay))
                    GestureExecutor.click(fx, fy, duration, 0, completionCallback)
                } else {
                    // Drag
                    // Add last point
                    val dt = currentTime - lastDragPointTime
                    if (dt > 0) {
                        currentDragPoints.add(DragPoint(endX, endY, dt))
                    }

                    val points = currentDragPoints.map {
                        it.copy(x = it.x / screen.width, y = it.y / screen.height)
                    }
                    recordedEvents.add(DragInteraction(points, 0, 0, delayBefore = delay))
                    GestureExecutor.drag(points, 0,0,completionCallback)
                }

                lastEventTime = currentTime
            }
        }
    }

    private fun showCloseArea() {
        val bg = closeAreaView?.background as? ShapeDrawable
        bg?.paint?.alpha = 150
        closeAreaView?.invalidate()
    }

    private fun hideCloseArea() {
        val bg = closeAreaView?.background as? ShapeDrawable
        bg?.paint?.alpha = 0
        closeAreaView?.invalidate()
    }

    private fun checkInCloseArea() {
        val bg = closeAreaView?.background as? ShapeDrawable
        if (isInCloseArea()) {
            bg?.paint?.color = Color.RED
            bg?.paint?.alpha = 255
        } else {
            bg?.paint?.color = Color.GRAY
            bg?.paint?.alpha = 150
        }
        closeAreaView?.invalidate()
    }

    private fun isInCloseArea(): Boolean {
        if (bubbleParams == null || closeAreaParams == null) return false

        val currentWidth = bubbleView?.width ?: bubbleSize
        val bubbleCenterX = bubbleParams!!.x + currentWidth / 2
        val bubbleCenterY = bubbleParams!!.y + bubbleSize / 2

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val closeCenterX = screenWidth / 2
        val closeCenterY = screenHeight - (closeAreaParams!!.y + closeAreaSize / 2)

        val distance = sqrt(((bubbleCenterX - closeCenterX).toDouble().pow(2.0) + (bubbleCenterY - closeCenterY).toDouble().pow(2.0)))

        return distance < closeAreaSize
    }

    private fun stopService() {
        remove()
        if (isRecording) stopRecording()
        val intent = Intent(context, NotificationService::class.java)
        context.stopService(intent)
    }

    fun remove() {
        try {
            if (bubbleView != null) {
                windowManager.removeView(bubbleView)
                bubbleView = null
            }
            if (closeAreaView != null) {
                windowManager.removeView(closeAreaView)
                closeAreaView = null
            }
            removeRecordingOverlay()
        } catch (e: IllegalArgumentException) {
        }
    }
}