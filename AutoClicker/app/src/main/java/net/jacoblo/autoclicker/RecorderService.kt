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
            executeEvents(events)
        }
    }

    private suspend fun executeEvents(events: List<Interaction>) {
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
                is ForLoopInteraction -> {
                    repeat(event.repeatCount) {
                        executeEvents(event.interactions)
                    }
                }
                else -> {
                    // Ignore unknown or editor-only interactions
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

        // Recursive helper to dispatch strokes in batches of 10 (max limit)
        // This ensures we respect individual point timings (dt)
        fun dispatchBatches(startIndex: Int) {
            // If we processed all segments, finish
            if (startIndex >= points.size - 1) {
                callback?.invoke()
                return
            }

            val builder = GestureDescription.Builder()
            var startTime = 0L
            var addedStrokes = 0
            var i = startIndex

            // Add strokes until we hit the batch limit or end of points
            while (i < points.size - 1 && addedStrokes < 10) {
                val start = points[i]
                val end = points[i + 1]
                val duration = end.dt.coerceAtLeast(1)

                val path = Path()
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)

                // Continue the gesture unless it's the very last segment
                val isLastOverall = (i == points.size - 2)
                val willContinue = !isLastOverall

                // StrokeDescription with continuation requires API 26+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration, willContinue))
                } else {
                    builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
                }

                startTime += duration
                addedStrokes++
                i++
            }

            val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    // Dispatch the next batch
                    dispatchBatches(i)
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

        dispatchBatches(0)
    }
}
