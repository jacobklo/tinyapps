package net.jacoblo.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "autoclicker.recorder.service"

/**
 * Accessibility backend for [GestureExecutor]. Injects already-randomized
 * coordinates; the randomization itself lives in the executor so the root
 * backend behaves the same.
 */
class RecorderService : AccessibilityService() {

    companion object {
        var instance: RecorderService? = null
    }

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

    fun dispatchClick(x: Float, y: Float, duration: Long, onDone: () -> Unit) {
        val path = Path()
        path.moveTo(x, y)
        dispatchPath(path, duration, onDone)
    }

    fun dispatchDrag(points: List<DragPoint>, onDone: () -> Unit) {
        val path = Path()
        path.moveTo(points[0].x, points[0].y)

        var totalDuration = 0L
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
            totalDuration += points[i].dt
        }

        dispatchPath(path, totalDuration, onDone)
    }

    fun dispatchText(text: String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.w(TAG, "no focused input field, dropping text")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun dispatchPath(path: Path, duration: Long, onDone: () -> Unit) {
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1)))

        val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onDone()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onDone()
            }
        }, null)

        if (!dispatched) {
            onDone()
        }
    }
}
