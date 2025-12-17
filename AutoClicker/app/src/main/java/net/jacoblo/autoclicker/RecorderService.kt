package net.jacoblo.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class RecorderService : AccessibilityService() {

    companion object {
        var instance: RecorderService? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for recording in this implementation
    }

    override fun onInterrupt() {
        // Required method
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    fun playRecording(events: List<Interaction>) {
        serviceScope.launch {
            events.forEach { event ->
                delay(event.delayBefore)
                if (event.type == "click") {
                    performClick(event.x.toFloat(), event.y.toFloat(), event.duration)
                } else if (event.type == "drag") {
                    performDrag(event.x.toFloat(), event.y.toFloat(), event.endX.toFloat(), event.endY.toFloat(), event.duration)
                }
                // Wait for the gesture to finish
                delay(event.duration)
            }
        }
    }

    fun performClick(x: Float, y: Float, duration: Long) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))
        dispatchGesture(builder.build(), null, null)
    }

    fun performDrag(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))
        dispatchGesture(builder.build(), null, null)
    }
}
