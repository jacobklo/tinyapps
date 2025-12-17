package net.jacoblo.autoclicker

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.pow
import kotlin.math.sqrt

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
    
    private var recordButtonView: View? = null
    private var recordButtonIcon: ImageView? = null

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

            // Button 3: Play Recorded (Blue)
            val playButton = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = 0xFF2196F3.toInt() // Material Blue
                }
                val icon = ImageView(context)
                icon.setImageResource(R.drawable.ic_play)
                icon.setColorFilter(Color.WHITE)
                icon.setPadding(20, 20, 20, 20)
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                
                setOnClickListener {
                    val file = RecordingManager.currentSelectedFile
                    if (file != null && RecorderService.instance != null) {
                        val events = RecordingManager.loadRecording(file)
                        RecorderService.instance?.playRecording(events)
                        Toast.makeText(context, "Playing ${file.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Select a recording first", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val paramsPlay = LinearLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                leftMargin = 10 
            }
            addView(playButton, paramsPlay)

            // Button 4: for dragging all these buttons
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

        if (RecorderService.instance == null) {
            Toast.makeText(context, "Please enable Accessibility Service for AutoClicker", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
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

        removeRecordingOverlay()
        RecordingManager.saveRecording(recordedEvents)
        Toast.makeText(context, "Recording Saved", Toast.LENGTH_SHORT).show()
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

    private fun handleRecordingTouch(event: MotionEvent) {
        val currentTime = System.currentTimeMillis()
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                touchStartTime = currentTime

                // Change button to WHITE to indicate input detection
                (recordButtonView?.background as? ShapeDrawable)?.paint?.color = Color.WHITE
                recordButtonView?.invalidate()
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
                
                if (distance < 20) {
                    // Click
                    recordedEvents.add(Interaction("click", startX.toInt(), startY.toInt(), 0, 0, duration, delay))

                    val service = RecorderService.instance
                    if (service != null) {
                        service.performClick(startX, startY, duration, completionCallback)
                    } else {
                        completionCallback()
                    }
                } else {
                    // Drag
                    recordedEvents.add(Interaction("drag", startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt(), duration, delay))

                    val service = RecorderService.instance
                    if (service != null) {
                        service.performDrag(startX, startY, endX, endY, duration, completionCallback)
                    } else {
                        completionCallback()
                    }
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
        System.exit(0)
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
    
    private fun abs(value: Int) = if (value < 0) -value else value
}