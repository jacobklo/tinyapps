package net.jacoblo.ocrtotypst

import android.annotation.SuppressLint
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
import kotlin.math.abs

class Bubble(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private var closeAreaView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var closeAreaParams: WindowManager.LayoutParams? = null

    private val bubbleSize = 100
    private val closeAreaSize = 100

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

            // Button 1
            val button1 = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = Color.RED
                }

                val icon = ImageView(context)
                icon.setImageResource(R.drawable.ic_record)
                icon.setColorFilter(Color.WHITE)
                icon.setPadding(25, 25, 25, 25)
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                setOnClickListener {
                    Toast.makeText(context, "Start OCR...", Toast.LENGTH_SHORT).show()
                    ScreenCaptureManager.captureScreen(context) { bitmap ->
                        if (bitmap != null) {
                            OcrManager.recognizeText(bitmap,
                                onSuccess = { text ->
                                    if (OcrManager.saveTextToFile(text)) {
                                        Toast.makeText(context, "OCR succeed", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFailure = {
                                    Toast.makeText(context, "OCR failed", Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            Toast.makeText(context, "Screen capture failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            addView(button1, LinearLayout.LayoutParams(bubbleSize, bubbleSize))

            // Button 2
            val button2 = FrameLayout(context).apply {
                background = ShapeDrawable(OvalShape()).apply {
                    paint.color = 0xFF2196F3.toInt() // Material Blue
                }
                val icon = ImageView(context)
                icon.setImageResource(R.drawable.ic_play)
                icon.setColorFilter(Color.WHITE)
                icon.setPadding(20, 20, 20, 20)
                addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

                setOnClickListener {
                    Toast.makeText(context, "Button 2 clicked", Toast.LENGTH_SHORT).show()
                }
            }
            val params2 = LinearLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                leftMargin = 10
            }
            addView(button2, params2)

            // Button 3: Drag handle
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
                            close()
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

    private fun close() {
        remove()
    }

    fun remove() {
        try {
            val intent = Intent(context, MediaProjectionService::class.java)
            context.stopService(intent)

            if (bubbleView != null) {
                windowManager.removeView(bubbleView)
                bubbleView = null
            }
            if (closeAreaView != null) {
                windowManager.removeView(closeAreaView)
                closeAreaView = null
            }
        } catch (e: IllegalArgumentException) {
        }
    }

    private fun abs(value: Int) = if (value < 0) -value else value
}