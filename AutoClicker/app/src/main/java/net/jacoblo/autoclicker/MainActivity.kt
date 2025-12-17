package net.jacoblo.autoclicker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme
import java.io.File

class MainActivity : ComponentActivity() {
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContent {
            AutoClickerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecordingsListScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (arePermissionsGranted()) {
            startBubbleService()
        }
    }

    private fun checkPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // Check for storage permission on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                storagePermissionLauncher.launch(intent)
                return
            }
        }
        
        if (!isAccessibilityServiceEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        startBubbleService()
    }
    
    private fun arePermissionsGranted(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = isAccessibilityServiceEnabled()
        // Verify storage permission status
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        return overlay && accessibility && storage
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return prefString?.contains("$packageName/${RecorderService::class.java.name}") == true
    }

    private fun startBubbleService() {
        val serviceIntent = Intent(this, NotificationService::class.java)
        startForegroundService(serviceIntent)
    }
}

@Composable
fun RecordingsListScreen(modifier: Modifier = Modifier) {
    var recordings by remember { mutableStateOf(RecordingManager.getRecordings()) }
    var selectedFile by remember { mutableStateOf(RecordingManager.currentSelectedFile) }
    var fileToRename by remember { mutableStateOf<File?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Recordings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            items(recordings) { file ->
                RecordingItem(
                    file = file,
                    isSelected = (file == selectedFile),
                    onSelect = {
                        selectedFile = file
                        RecordingManager.currentSelectedFile = file
                    },
                    onRename = { fileToRename = file },
                    onDelete = {
                        RecordingManager.deleteRecording(file)
                        recordings = RecordingManager.getRecordings()
                        if (selectedFile == file) {
                            selectedFile = null
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    if (fileToRename != null) {
        RenameDialog(
            file = fileToRename!!,
            onDismiss = { fileToRename = null },
            onConfirm = { newName ->
                RecordingManager.renameRecording(fileToRename!!, newName)
                recordings = RecordingManager.getRecordings()
                // Update selectedFile if the renamed file was selected
                // (RecordingManager handles updating its internal reference, but we need to update UI state if needed)
                if (selectedFile == fileToRename) {
                     selectedFile = RecordingManager.currentSelectedFile
                }
                fileToRename = null
            }
        )
    }
}

@Composable
fun RecordingItem(
    file: File,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onSelect
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = file.nameWithoutExtension,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onRename) {
            Icon(Icons.Default.Edit, contentDescription = "Rename")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}

@Composable
fun RenameDialog(
    file: File,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(file.nameWithoutExtension) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Recording") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
