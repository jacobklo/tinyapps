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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
        checkPermissions()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onResume() {
        super.onResume()
        if (arePermissionsGranted()) {
            // Settings live on shared storage, so the load at process start may
            // have run before all-files access was granted.
            AppSettings.reload()
            startBubbleService()

            setContent {
                AutoClickerTheme {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(
                                title = { Text("Auto Clicker") },
                                actions = {
                                    IconButton(onClick = {
                                        startActivity(Intent(this@MainActivity, TriggersActivity::class.java))
                                    }) {
                                        Icon(Icons.Default.Bolt, contentDescription = "Triggers")
                                    }
                                    IconButton(onClick = {
                                        startActivity(Intent(this@MainActivity, ScreenshotsActivity::class.java))
                                    }) {
                                        Icon(Icons.Default.Image, contentDescription = "Screen areas")
                                    }
                                    IconButton(onClick = {
                                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                    }) {
                                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        RecordingsListScreen(
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
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
        
        // The gesture backend is deliberately not demanded here. Root needs no
        // Accessibility Service at all, and bouncing to its settings screen on
        // every launch would bury the "Use Root" toggle that turns it off.
        // Bubble nudges to Accessibility Settings when recording is pressed.
        startBubbleService()
    }
    
    // Deliberately excludes the gesture backend: the screen has to render even
    // with no backend ready, otherwise the "Use Root" setting is unreachable
    // without first enabling the Accessibility Service it is meant to replace.
    private fun arePermissionsGranted(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        // Verify storage permission status
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        return overlay && storage
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
    val context = LocalContext.current
    // Re-reads whenever a recording is saved, renamed or deleted anywhere in
    // the process, including from the bubble while this screen is on top.
    val revision by RecordingManager.revision.collectAsState()
    val recordings = remember(revision) { RecordingManager.getRecordings() }
    var selectedFile by remember { mutableStateOf(RecordingManager.currentSelectedFile) }
    var fileToRename by remember { mutableStateOf<File?>(null) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }

    if (recordings.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tap the red button on the floating bubble to start recording.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(recordings, key = { it.absolutePath }) { file ->
            val summary = remember(file, revision) {
                summarize(RecordingManager.loadRecording(file).events)
            }
            RecordingItem(
                file = file,
                isSelected = (file == selectedFile),
                summary = summary,
                onSelect = {
                    selectedFile = file
                    RecordingManager.currentSelectedFile = file
                },
                onEdit = {
                    val intent = Intent(context, EditorActivity::class.java).apply {
                        putExtra("FILE_PATH", file.absolutePath)
                    }
                    context.startActivity(intent)
                },
                onRename = { fileToRename = file },
                onDelete = { fileToDelete = file }
            )
            HorizontalDivider()
        }
    }

    // Deleting is immediate and unrecoverable, so make it deliberate.
    val pendingDelete = fileToDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("\"${pendingDelete.nameWithoutExtension}\" will be permanently deleted.") },
            confirmButton = {
                Button(onClick = {
                    RecordingManager.deleteRecording(pendingDelete)
                    if (selectedFile == pendingDelete) {
                        selectedFile = null
                    }
                    fileToDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (fileToRename != null) {
        RenameDialog(
            file = fileToRename!!,
            onDismiss = { fileToRename = null },
            onConfirm = { newName ->
                RecordingManager.renameRecording(fileToRename!!, newName)
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

/**
 * Tapping the row picks which recording the bubble's play button runs; the
 * pencil opens it for editing. Previously an unlabelled radio did the picking
 * while a whole-row tap opened the editor, which read backwards.
 */
@Composable
fun RecordingItem(
    file: File,
    isSelected: Boolean,
    summary: RecordingSummary,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.nameWithoutExtension,
                    style = MaterialTheme.typography.titleMedium
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "PLAYS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = summary.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
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