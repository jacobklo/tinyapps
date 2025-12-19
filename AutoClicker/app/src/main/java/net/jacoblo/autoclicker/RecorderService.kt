package net.jacoblo.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.math.abs

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
                    // Pass randomFactor to performClick
                    performClick(event.x, event.y, event.duration, event.randomFactor)
                    delay(event.duration)
                }
                is DragInteraction -> {
                    // Calculate total duration
                    val totalDuration = event.points.sumOf { it.dt }
                    // Pass random parameters to performDrag
                    performDrag(event.points, event.randomFactorStart, event.randomFactorHighest)
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

    fun performClick(x: Float, y: Float, duration: Long, randomFactor: Int, callback: (() -> Unit)? = null) {
        // Apply randomness
        val dx = if (randomFactor > 0) Random.nextInt(-randomFactor, randomFactor + 1) else 0
        val dy = if (randomFactor > 0) Random.nextInt(-randomFactor, randomFactor + 1) else 0
        
        val finalX = x + dx
        val finalY = y + dy

        val path = Path()
        path.moveTo(finalX, finalY)
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
    fun performDrag(points: List<DragPoint>, randomFactorStart: Int, randomFactorHighest: Int, callback: (() -> Unit)? = null) {
        if (points.isEmpty()) {
            callback?.invoke()
            return
        }

        // Calculate randomized points
        val randomizedPoints = if (points.size > 1) {
            val n = points.size
            points.mapIndexed { index, point ->
                val mid = (n - 1) / 2.0
                val dist = abs(index - mid)
                val normDist = if (mid > 0) dist / mid else 0.0 // 0 at middle, 1 at ends. If n=1, mid=0, normDist=0? Handling 1 point case

                // Logic: Start -> Highest -> Start.
                // At ends (normDist=1), factor = Start.
                // At middle (normDist=0), factor = Highest.
                // We want factor = Start + (Highest - Start) * (1 - normDist)

                val t = if (mid > 0) (1.0 - (dist / mid)).coerceIn(0.0, 1.0) else 0.0 // 1.0 at center, 0.0 at ends
                // Actually if n=1, mid=0. division by zero.
                // If n=1, we can just use randomFactorStart (or Highest, doesn't matter much for 1 point drag which is weird).
                // Existing check points.size > 1 handles single point via else block? No.

                val currentFactor = (randomFactorStart + (randomFactorHighest - randomFactorStart) * t).toInt()

                val dx = if (currentFactor > 0) Random.nextInt(-currentFactor, currentFactor + 1) else 0
                val dy = if (currentFactor > 0) Random.nextInt(-currentFactor, currentFactor + 1) else 0

                DragPoint(point.x + dx, point.y + dy, point.dt)
            }
        } else {
            // Handle single point case if necessary, though drags usually > 1 point
            points.map {
                val dx = if (randomFactorStart > 0) Random.nextInt(-randomFactorStart, randomFactorStart + 1) else 0
                val dy = if (randomFactorStart > 0) Random.nextInt(-randomFactorStart, randomFactorStart + 1) else 0
                DragPoint(it.x + dx, it.y + dy, it.dt)
            }
        }

        val path = Path()
        // Move to the first point
        path.moveTo(randomizedPoints[0].x, randomizedPoints[0].y)

        var totalDuration = 0L
        // Loop through remaining points for lineTo
        for (i in 1 until randomizedPoints.size) {
            path.lineTo(randomizedPoints[i].x, randomizedPoints[i].y)
            totalDuration += randomizedPoints[i].dt
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
