package net.jacoblo.ocrtotypst

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import net.jacoblo.ocrtotypst.ui.theme.OCRToTypstTheme

class MainActivity : ComponentActivity() {

    private var bubble: Bubble? = null
    private var isMediaProjectionRequested = false

    // Register a result launcher for the overlay permission intent
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissions()
    }

    // Register a result launcher for the storage permission intent
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissions()
    }

    // Register a result launcher for media projection
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureManager.setPermissionResult(result.resultCode, result.data!!)

            val intent = Intent(this, MediaProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            showBubble()
        } else {
            // Permission denied or cancelled, show bubble anyway but capture won't work
            showBubble()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        // Check permissions on startup
        checkPermissions()

        setContent {
            OCRToTypstTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (arePermissionsGranted()) {
            requestMediaProjection()
        }
    }

    private fun checkPermissions() {
        // Check Overlay Permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Check All Files Access Permission (Android 11+)
        if (!Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            storagePermissionLauncher.launch(intent)
            return
        }
        
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        if (!isMediaProjectionRequested) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            isMediaProjectionRequested = true
        } else {
             showBubble()
        }
    }
    
    private fun arePermissionsGranted(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        val storage = Environment.isExternalStorageManager()
        return overlay && storage
    }

    private fun showBubble() {
        if (bubble == null) {
            bubble = Bubble(applicationContext)
        }
        bubble?.show()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    OCRToTypstTheme {
        Greeting("Android")
    }
}