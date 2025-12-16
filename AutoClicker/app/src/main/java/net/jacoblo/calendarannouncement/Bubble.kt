package net.jacoblo.autoclicker

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.pow
import kotlin.math.sqrt

class Bubble(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: View? = null
    private var closeAreaView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var closeAreaParams: WindowManager.LayoutParams? = null
    
    private val bubbleSize = 150
    private val closeAreaSize = 200

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubbleView != null) return

        // Setup Close Area (Trash bin)
        closeAreaView = FrameLayout(context).apply {
            background = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                paint.color = Color.RED
                paint.alpha = 0 // Initially invisible
            }
            // Add an X icon or similar if possible, simplified here
            val icon = ImageView(context)
            icon.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
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


        // Setup Bubble
        bubbleView = FrameLayout(context).apply {
            background = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                paint.color = 0xFF2196F3.toInt() // Material Blue
            }
            val icon = ImageView(context)
            icon.setImageResource(R.mipmap.ic_launcher_round)
            addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
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

                        // Only consider it a drag if moved significantly
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
                        } else if (!isDragging) {
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
        
        bubbleView?.setOnClickListener {
             // Do nothing for now
        }

        windowManager.addView(bubbleView, bubbleParams)
    }
    
    private fun showCloseArea() {
         val bg = closeAreaView?.background as? android.graphics.drawable.ShapeDrawable
         bg?.paint?.alpha = 150
         closeAreaView?.invalidate()
    }
    
    private fun hideCloseArea() {
         val bg = closeAreaView?.background as? android.graphics.drawable.ShapeDrawable
         bg?.paint?.alpha = 0
         closeAreaView?.invalidate()
    }

    private fun checkInCloseArea() {
        val bg = closeAreaView?.background as? android.graphics.drawable.ShapeDrawable
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

        val bubbleCenterX = bubbleParams!!.x + bubbleSize / 2
        val bubbleCenterY = bubbleParams!!.y + bubbleSize / 2
        
        // Calculate close area center on screen
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        val closeCenterX = screenWidth / 2
        val closeCenterY = screenHeight - (closeAreaParams!!.y + closeAreaSize / 2) // Gravity BOTTOM

        // Simple distance check
        val distance = sqrt(((bubbleCenterX - closeCenterX).toDouble().pow(2.0) + (bubbleCenterY - closeCenterY).toDouble().pow(2.0)))
        
        return distance < closeAreaSize
    }

    private fun stopService() {
        remove()
        val intent = Intent(context, NotificationService::class.java)
        context.stopService(intent)
        // Also close app if possible, but from Service context we can't easily finish Activity.
        // However, user said "This will close this app".
        // Killing the process or sending broadcast to finish activity is an option.
        // For now, stopping service removes the bubble.
        System.exit(0) // Force close app as requested "This will close this app"
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
        } catch (e: IllegalArgumentException) {
            // View not attached
        }
    }
    
    private fun abs(value: Int) = if (value < 0) -value else value
}
