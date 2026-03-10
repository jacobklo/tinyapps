package net.jacoblo.randommp3alarm

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import net.jacoblo.randommp3alarm.ui.theme.RandomMp3AlarmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RandomMp3AlarmTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val alarms = remember { mutableStateListOf<Alarm>() }
    var audioStream by remember { mutableIntStateOf(AudioManager.STREAM_ALARM) }
    var pendingDirectoryPosition by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        val loaded = AlarmStorage.loadAlarms(context)
        alarms.addAll(loaded)
        audioStream = AlarmStorage.loadAudioStream(context)
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val path = getPathFromTreeUri(it) ?: it.toString()
            val pos = pendingDirectoryPosition
            if (pos >= 0 && pos < alarms.size) {
                alarms[pos] = alarms[pos].copy(directoryPath = path)
            }
            pendingDirectoryPosition = -1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Random MP3 Alarm") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                navigationIcon = {
                    TextButton(onClick = {
                        AlarmStorage.saveAlarms(context, alarms.toList())
                        AlarmStorage.saveAudioStream(context, audioStream)
                        alarms.forEach { alarm ->
                            AlarmScheduler.cancelAlarm(context, alarm.id)
                            if (alarm.enabled) AlarmScheduler.scheduleAlarm(context, alarm)
                        }
                        Toast.makeText(context, "Alarms saved", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Save", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val newId = (alarms.maxOfOrNull { it.id } ?: 0) + 1
                alarms.add(Alarm(id = newId))
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Alarm")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            item {
                PermissionsSection(context)
            }
            item {
                AudioChannelSection(audioStream) { audioStream = it }
            }
            itemsIndexed(alarms, key = { _, alarm -> alarm.id }) { index, alarm ->
                AlarmItem(
                    alarm = alarm,
                    onAlarmChanged = { alarms[index] = it },
                    onDelete = { alarms.removeAt(index) },
                    onBrowseDirectory = {
                        pendingDirectoryPosition = index
                        dirPickerLauncher.launch(null)
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionsSection(context: Context) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val notifManager = context.getSystemService(android.app.NotificationManager::class.java)
    var refreshKey by remember { mutableIntStateOf(0) }

    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            val isBatteryOptIgnored = remember(refreshKey) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
            PermissionRow(
                title = "Background / No Battery Optimization",
                granted = isBatteryOptIgnored,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                    refreshKey++
                }
            )

            val hasFilesAccess = remember(refreshKey) {
                Environment.isExternalStorageManager()
            }
            PermissionRow(
                title = "All Files Access (Storage)",
                granted = hasFilesAccess,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                    refreshKey++
                }
            )

            val canScheduleExact = remember(refreshKey) { alarmManager.canScheduleExactAlarms() }
            PermissionRow(
                title = "Schedule Exact Alarms",
                granted = canScheduleExact,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                    refreshKey++
                }
            )

            val notifEnabled = remember(refreshKey) {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            PermissionRow(
                title = "Notifications",
                granted = notifEnabled,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                    refreshKey++
                }
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val canFullScreen = remember(refreshKey) { notifManager.canUseFullScreenIntent() }
                PermissionRow(
                    title = "Lock Screen Popup (Full Screen Intent)",
                    granted = canFullScreen,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                        refreshKey++
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) {
            TextButton(onClick = onClick) { Text("Grant") }
        }
    }
}

@Composable
fun AudioChannelSection(audioStream: Int, onStreamChanged: (Int) -> Unit) {
    val streams = listOf(
        "Alarm" to AudioManager.STREAM_ALARM,
        "Media" to AudioManager.STREAM_MUSIC,
        "Notification" to AudioManager.STREAM_NOTIFICATION,
        "Ringtone" to AudioManager.STREAM_RING,
        "System" to AudioManager.STREAM_SYSTEM
    )
    var expanded by remember { mutableStateOf(false) }
    val selectedName = streams.find { it.second == audioStream }?.first ?: "Alarm"

    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Audio Channel:",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selectedName)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    streams.forEach { (name, stream) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { onStreamChanged(stream); expanded = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmItem(
    alarm: Alarm,
    onAlarmChanged: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onBrowseDirectory: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var snoozeCountText by remember(alarm.id) { mutableStateOf(alarm.snoozeCount.toString()) }
    var snoozeDurationText by remember(alarm.id) { mutableStateOf(alarm.snoozeDurationSeconds.toString()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            // Collapsed header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%02d:%02d", alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = { onAlarmChanged(alarm.copy(enabled = it)) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            // Expandable settings section
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {

                    // Time setter
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    onAlarmChanged(alarm.copy(hour = hour, minute = minute))
                                },
                                alarm.hour, alarm.minute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set Time: ${String.format("%02d:%02d", alarm.hour, alarm.minute)}")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Snooze count
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Snooze count:",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = snoozeCountText,
                            onValueChange = { v ->
                                snoozeCountText = v
                                v.toIntOrNull()?.let { onAlarmChanged(alarm.copy(snoozeCount = it)) }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(90.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Snooze duration
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Snooze duration (sec):",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = snoozeDurationText,
                            onValueChange = { v ->
                                snoozeDurationText = v
                                v.toIntOrNull()?.let { onAlarmChanged(alarm.copy(snoozeDurationSeconds = it)) }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(90.dp),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Directory path display
                    Text(
                        text = "Directory:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = alarm.directoryPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedButton(
                        onClick = onBrowseDirectory,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Browse Directory...")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Recursive search checkbox
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAlarmChanged(alarm.copy(recursive = !alarm.recursive)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = alarm.recursive,
                            onCheckedChange = { onAlarmChanged(alarm.copy(recursive = it)) }
                        )
                        Text("Recursive (search subdirectories)")
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Delete button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDelete,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete alarm")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

fun getPathFromTreeUri(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        when {
            docId == "primary:" || docId == "primary" ->
                Environment.getExternalStorageDirectory().absolutePath
            docId.startsWith("primary:") ->
                "${Environment.getExternalStorageDirectory()}/${docId.removePrefix("primary:")}"
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
