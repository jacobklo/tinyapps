package net.jacoblo.calendarannouncement

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private var ttsForSettings: TextToSpeech? = null
    private val ttsEngineInfos = mutableStateOf<List<TextToSpeech.EngineInfo>>(emptyList())
    private val ttsVoiceNames = mutableStateOf<List<String>>(emptyList())
    private val googleLoggedIn = mutableStateOf(false)
    private val googleAuthInProgress = mutableStateOf(false)
    private val calendarAccounts = mutableStateOf<List<CalendarAccount>>(emptyList())
    private val refreshTrigger = mutableStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTrigger.value++ }

    private val authResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "net.jacoblo.calendarannouncement.GOOGLE_AUTH_RESULT") {
                val success = intent.getBooleanExtra("success", false)
                googleAuthInProgress.value = false
                googleLoggedIn.value = prefs.isGoogleLoggedIn()
                if (success) {
                    refreshTrigger.value++
                    loadCalendarAccounts()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        googleLoggedIn.value = prefs.isGoogleLoggedIn()

        val filter = IntentFilter("net.jacoblo.calendarannouncement.GOOGLE_AUTH_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(authResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(authResultReceiver, filter)
        }

        ttsForSettings = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsForSettings?.let { t ->
                    ttsEngineInfos.value = t.engines?.toList() ?: emptyList()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ttsVoiceNames.value = t.voices?.map { it.name }?.sorted() ?: emptyList()
                    }
                }
            }
        }

        loadCalendarAccounts()

        setContent {
            MaterialTheme {
                SettingsScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.value++
        
        if (googleAuthInProgress.value && prefs.isGoogleLoggedIn()) {
            googleAuthInProgress.value = false
            googleLoggedIn.value = true
            loadCalendarAccounts()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsForSettings?.shutdown()
        unregisterReceiver(authResultReceiver)
    }

    private fun loadCalendarAccounts() {
        Thread {
            val list = mutableListOf<CalendarAccount>()
            if (hasPermission(Manifest.permission.READ_CALENDAR)) {
                list.addAll(DeviceCalendarReader(this).getCalendarAccounts())
            }
            if (prefs.useGoogleCalendar && prefs.isGoogleLoggedIn()) {
                try {
                    list.addAll(GoogleCalendarClient(this, prefs).getCalendarList())
                } catch (_: Exception) {}
            }
            runOnUiThread { calendarAccounts.value = list }
        }.start()
    }

    private fun startGoogleSignIn() {
        if (prefs.googleClientId.isBlank()) {
            Toast.makeText(this, "Enter your Google OAuth Client ID first.", Toast.LENGTH_LONG).show()
            return
        }
        googleAuthInProgress.value = true
        GoogleCalendarClient(this, prefs).startAuthFlow { success ->
            runOnUiThread {
                googleAuthInProgress.value = false
                if (!success) {
                    Toast.makeText(this, "Failed to start sign-in.", Toast.LENGTH_LONG).show()
                } else {
                    refreshTrigger.value++
                    loadCalendarAccounts()
                }
            }
        }
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen() {
        val trigger by refreshTrigger

        // View State bound to preferences initially, but only saved on explicit action
        var serviceEnabled by remember { mutableStateOf(prefs.serviceEnabled) }
        var syncInterval by remember { mutableStateOf(prefs.syncIntervalMinutes.toString()) }
        var announceBefore by remember { mutableStateOf(prefs.announceBeforeMinutes.toString()) }
        var useGoogle by remember { mutableStateOf(prefs.useGoogleCalendar) }
        val isAuthInProgress by googleAuthInProgress
        val accounts by calendarAccounts
        var disabledIds by remember { mutableStateOf(prefs.disabledCalendarIds) }

        var ttsEngine by remember { mutableStateOf(prefs.ttsEngine) }
        var ttsLang by remember { mutableStateOf(prefs.ttsLanguage) }
        var ttsVoice by remember { mutableStateOf(prefs.ttsVoice) }
        var ttsPitch by remember { mutableStateOf(prefs.ttsPitch) }
        var ttsSpeed by remember { mutableStateOf(prefs.ttsSpeed) }
        var ttsAudioStream by remember { mutableStateOf(prefs.ttsAudioStream) }

        var clientId by remember { mutableStateOf(prefs.googleClientId) }
        var clientSecret by remember { mutableStateOf(prefs.googleClientSecret) }

        val ttsEngines by ttsEngineInfos
        val ttsVoices by ttsVoiceNames

        val hasCalendar = trigger.let { hasPermission(Manifest.permission.READ_CALENDAR) }
        val hasNotify = trigger.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            else true
        }
        val hasExactAlarm = trigger.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            else true
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Calendar Announcement") },
                    actions = {
                        TextButton(onClick = {
                            prefs.serviceEnabled = serviceEnabled
                            syncInterval.toIntOrNull()?.let { prefs.syncIntervalMinutes = it }
                            announceBefore.toIntOrNull()?.let { prefs.announceBeforeMinutes = it }
                            prefs.useGoogleCalendar = useGoogle
                            prefs.disabledCalendarIds = disabledIds
                            
                            prefs.ttsEngine = ttsEngine
                            prefs.ttsLanguage = ttsLang
                            prefs.ttsVoice = ttsVoice
                            prefs.ttsPitch = ttsPitch
                            prefs.ttsSpeed = ttsSpeed
                            prefs.ttsAudioStream = ttsAudioStream
                            
                            prefs.googleClientId = clientId
                            prefs.googleClientSecret = clientSecret
                            
                            if (serviceEnabled) {
                                NotificationService.start(this@MainActivity)
                            } else {
                                NotificationService.stop(this@MainActivity)
                            }

                            Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("SAVE")
                        }
                    }
                )
            }
        ) { paddingValues ->
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // --- PERMISSIONS ---
                Divider()
                Text("Permissions", style = MaterialTheme.typography.titleMedium)

                PermissionRow("Read Calendar", hasCalendar) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                }
                PermissionRow("Post Notifications", hasNotify) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
                PermissionRow("Exact Alarms (TTS timing)", hasExactAlarm) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                }
                val batteryExcluded = trigger.let {
                    (getSystemService(Context.POWER_SERVICE) as PowerManager)
                        .isIgnoringBatteryOptimizations(packageName)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Battery Optimization", modifier = Modifier.weight(1f))
                    if (batteryExcluded) {
                        Text("Excluded", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }) { Text("Exclude") }
                    }
                }

                // --- SERVICE ---
                Divider()
                Text("Service", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable background service", modifier = Modifier.weight(1f))
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = {
                            serviceEnabled = it
                            prefs.serviceEnabled = it
                            if (it) {
                                NotificationService.start(this@MainActivity)
                            } else {
                                NotificationService.stop(this@MainActivity)
                            }
                        }
                    )
                }

                // --- SYNC ---
                Divider()
                Text("Sync Settings", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = syncInterval,
                    onValueChange = { syncInterval = it },
                    label = { Text("Re-sync interval (minutes, default 10)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = announceBefore,
                    onValueChange = { announceBefore = it },
                    label = { Text("Announce before meeting (minutes, default 10)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // --- GOOGLE CALENDAR ---
                Divider()
                Text("Google Calendar (Web API)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Requires a Google Cloud project with Calendar API enabled.\n" +
                    "Credential type: Desktop app",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Use Google Calendar API", modifier = Modifier.weight(1f))
                    Switch(
                        checked = useGoogle,
                        onCheckedChange = { useGoogle = it }
                    )
                }
                if (useGoogle) {
                    if (isAuthInProgress) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            Text("Waiting for browser sign-in...")
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { googleAuthInProgress.value = false }) { Text("Cancel") }
                        }
                    } else {
                        val accountsList = trigger.let { prefs.googleAccounts }
                        if (accountsList.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Not signed in", modifier = Modifier.weight(1f))
                            }
                        } else {
                            accountsList.forEach { accountEmail ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text("Signed in: ${maskEmail(accountEmail)}", modifier = Modifier.weight(1f))
                                    Button(onClick = {
                                        prefs.removeGoogleAccount(accountEmail)
                                        googleLoggedIn.value = prefs.isGoogleLoggedIn()
                                        refreshTrigger.value++
                                        loadCalendarAccounts()
                                    }) { Text("Sign out") }
                                }
                            }
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            Button(onClick = {
                                // Save current Google credentials briefly so startAuthFlow can read them
                                prefs.googleClientId = clientId
                                prefs.googleClientSecret = clientSecret
                                startGoogleSignIn()
                            }) { Text(if (accountsList.isEmpty()) "Sign in via browser" else "Add account") }
                        }
                    }
                }

                // --- CALENDAR ACCOUNTS ---
                Divider()
                Text("Calendar Accounts", style = MaterialTheme.typography.titleMedium)
                if (accounts.isEmpty()) {
                    Text(
                        "No calendars found. Grant calendar permission and tap Refresh.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    accounts.forEach { account ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.name)
                                Text(maskEmail(account.accountName), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = !disabledIds.contains(account.id),
                                onCheckedChange = { enabled ->
                                    val updated = disabledIds.toMutableSet()
                                    if (enabled) updated.remove(account.id) else updated.add(account.id)
                                    disabledIds = updated
                                }
                            )
                        }
                    }
                }
                Button(onClick = { loadCalendarAccounts() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh Calendars")
                }

                // --- TTS ---
                Divider()
                Text("Text-to-Speech Settings", style = MaterialTheme.typography.titleMedium)
                
                // Audio Stream Dropdown
                var streamExpanded by remember { mutableStateOf(false) }
                val streamOptions = listOf(
                    AudioManager.STREAM_ALARM to "Alarm",
                    AudioManager.STREAM_MUSIC to "Media/Music",
                    AudioManager.STREAM_NOTIFICATION to "Notification",
                    AudioManager.STREAM_RING to "Ringtone",
                    AudioManager.STREAM_SYSTEM to "System"
                )
                val currentStreamLabel = streamOptions.find { it.first == ttsAudioStream }?.second ?: "Media/Music"

                ExposedDropdownMenuBox(
                    expanded = streamExpanded,
                    onExpandedChange = { streamExpanded = !streamExpanded }
                ) {
                    OutlinedTextField(
                        readOnly = true,
                        value = currentStreamLabel,
                        onValueChange = {},
                        label = { Text("Audio Channel / Volume Stream") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = streamExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = streamExpanded,
                        onDismissRequest = { streamExpanded = false }
                    ) {
                        streamOptions.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption.second) },
                                onClick = {
                                    ttsAudioStream = selectionOption.first
                                    streamExpanded = false
                                }
                            )
                        }
                    }
                }

                if (ttsEngines.isNotEmpty()) {
                    Text("Engine", style = MaterialTheme.typography.bodyMedium)
                    ttsEngines.forEach { engine ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(
                                selected = ttsEngine == engine.name ||
                                    (ttsEngine.isEmpty() && ttsEngines.first().name == engine.name),
                                onClick = { ttsEngine = engine.name }
                            )
                            Text(engine.label)
                        }
                    }
                }

                OutlinedTextField(
                    value = ttsLang,
                    onValueChange = { ttsLang = it },
                    label = { Text("Language tag (e.g. en-US, fr-FR)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Pitch: ${"%.1f".format(ttsPitch)}")
                Slider(
                    value = ttsPitch,
                    onValueChange = { ttsPitch = it },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Speed: ${"%.1f".format(ttsSpeed)}")
                Slider(
                    value = ttsSpeed,
                    onValueChange = { ttsSpeed = it },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.fillMaxWidth()
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && ttsVoices.isNotEmpty()) {
                    Text("Voice", style = MaterialTheme.typography.bodyMedium)
                    val filtered = if (ttsLang.isNotEmpty())
                        ttsVoices.filter { it.startsWith(ttsLang.substringBefore("-"), ignoreCase = true) }
                    else ttsVoices
                    
                    val voicesScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(voicesScrollState)
                    ) {
                        filtered.forEach { voiceName ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                RadioButton(
                                    selected = ttsVoice == voiceName,
                                    onClick = { ttsVoice = voiceName }
                                )
                                Text(voiceName)
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        ttsForSettings?.let { t ->
                            if (ttsLang.isNotEmpty()) t.language = Locale.forLanguageTag(ttsLang)
                            t.setPitch(ttsPitch)
                            t.setSpeechRate(ttsSpeed)
                            

                            if (ttsVoice.isNotEmpty()) {
                                t.voices?.find { it.name == ttsVoice }?.let { t.voice = it }
                            }
                            t.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(when (ttsAudioStream) {
                                        AudioManager.STREAM_ALARM -> AudioAttributes.USAGE_ALARM
                                        AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION_EVENT
                                        AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                                        AudioManager.STREAM_SYSTEM -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                                        AudioManager.STREAM_VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                                        else -> AudioAttributes.USAGE_MEDIA
                                    })
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )


                            t.speak("Meeting! Test announcement", TextToSpeech.QUEUE_FLUSH, null, "test")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Test TTS") }

                Button(
                    onClick = { startActivity(Intent("com.android.settings.TTS_SETTINGS")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open System TTS Settings") }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            if (granted) {
                Text("Granted", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onGrant) { Text("Grant") }
            }
        }
    }

    private fun maskEmail(email: String): String {
        if (email.isEmpty()) return ""
        val at = email.indexOf('@')
        if (at <= 0) return email
        val local = email.substring(0, at)
        val domain = email.substring(at)
        val keep = minOf(3, local.length)
        return "${local.substring(0, keep)}****$domain"
    }
}