package net.jacoblo.calendarannouncement

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.jacoblo.calendarannouncement.ui.theme.CalendarAnnouncementTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContent {
            CalendarAnnouncementTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            startService()
        }
    }

    private fun startService() {
        val serviceIntent = Intent(this, NotificationService::class.java)
        startForegroundService(serviceIntent)
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }
    // Default to 10 minutes if not set, storing as String for TextField
    var minutesStr by remember {
        mutableStateOf(sharedPreferences.getInt("announce_minutes", 10).toString())
    }

    Column(modifier = modifier.padding(16.dp)) {
        Greeting("This is an app, that read all the Calendar Events/Meetings of today, and announce each meeting 10 minutes before hand, Out loud. ")
        
        Spacer(modifier = Modifier.height(16.dp))

        Greeting("Your Android must have Text-to-speech engine installed. Ideally Speech Recognition & Synthesis, by Google")

        Spacer(modifier = Modifier.height(16.dp))

        Greeting("Your Google Calendar also have to by synced to your phone. You can change this setting in System Settings > Accounts > Your Google account > Calendar")

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = minutesStr,
            onValueChange = { newValue ->
                // Only allow numeric input
                if (newValue.all { it.isDigit() }) {
                    minutesStr = newValue
                    newValue.toIntOrNull()?.let { minutes ->
                        // Persist the new value to SharedPreferences
                        sharedPreferences.edit().putInt("announce_minutes", minutes).apply()
                    }
                }
            },
            label = { Text("Announce minutes before") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
    }
}

@Composable
fun Greeting(s: String, modifier: Modifier = Modifier) {
    Text(
        text = s,
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    CalendarAnnouncementTheme {
        MainScreen()
    }
}
