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
                when (event) {
                    is ClickInteraction -> {
                        performClick(event.x, event.y, event.duration)
                        delay(event.duration)
                    }
                    is DragInteraction -> {
                        // Calculate total duration
                        val totalDuration = event.points.sumOf { it.dt }
                        performDrag(event.points)
                        delay(totalDuration)
                    }
                }
            }
        }
    }

    fun performClick(x: Float, y: Float, duration: Long, callback: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))

        val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback?.invoke()
            }
        }, null)

        if (!dispatched) {
            callback?.invoke()
        }
    }

    // 4) Rewrite performDrag to handle multiple coordinates
    fun performDrag(points: List<DragPoint>, callback: (() -> Unit)? = null) {
        if (points.isEmpty()) {
            callback?.invoke()
            return
        }

        val path = Path()
        // Move to the first point
        path.moveTo(points[0].x, points[0].y)

        var totalDuration = 0L
        // Loop through remaining points for lineTo
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
            totalDuration += points[i].dt
        }

        // Ensure duration is at least 1ms
        val duration = totalDuration.coerceAtLeast(1)

        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))

        val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback?.invoke()
            }
        }, null)

        if (!dispatched) {
            callback?.invoke()
        }
    }
}