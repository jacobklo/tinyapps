package net.jacoblo.ocrtotypst

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenCaptureManager {
    private var resultCode: Int = 0
    private var resultData: Intent? = null

    fun setPermissionResult(code: Int, data: Intent) {
        resultCode = code
        resultData = data
    }

    fun captureScreen(context: Context, onCaptured: (Bitmap?) -> Unit) {
        val data = resultData
        if (data == null) {
            onCaptured(null)
            return
        }
        // Intent can only be used once. Clear it to prevent reuse which causes crashes.
        resultData = null

        var mediaProjection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null

        try {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                onCaptured(null)
                return
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            // Required for Android 14+
            val handler = Handler(Looper.getMainLooper())
            mediaProjection.registerCallback(object : MediaProjection.Callback() {}, handler)
            
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null, null
            )

            var captured = false

            imageReader.setOnImageAvailableListener({ reader ->
                if (captured) return@setOnImageAvailableListener
                
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        captured = true
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        
                        image.close()
                        virtualDisplay?.release()
                        mediaProjection?.stop()
                        
                        onCaptured(croppedBitmap)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    virtualDisplay?.release()
                    mediaProjection?.stop()
                    onCaptured(null)
                }
            }, handler)
        } catch (e: Exception) {
            e.printStackTrace()
            virtualDisplay?.release()
            mediaProjection?.stop()
            onCaptured(null)
        }
    }
}