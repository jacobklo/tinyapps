package net.jacoblo.moodlauncher

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import net.jacoblo.moodlauncher.ui.theme.MoodLauncherTheme

class SettingsActivity : ComponentActivity() {

    // Prevents auto-finish when we launched a sub-activity for a result
    var suppressFinishOnStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MoodLauncherTheme {
                SettingsScreen()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !suppressFinishOnStop) {
            finish()
        }
        suppressFinishOnStop = false
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val activity = context as SettingsActivity
    val scope = rememberCoroutineScope()

    val driveSync = remember { DriveSync(context) }
    val repository = remember { NotesRepository(context) }
    val calendarPrefs = remember { CalendarPreferences(context) }

    var isSynced by remember { mutableStateOf(driveSync.isSignedIn()) }
    var syncStatus by remember { mutableStateOf("") }
    var hasStoragePermission by remember {
        mutableStateOf(Environment.isExternalStorageManager())
    }
    var fontColor by remember { mutableStateOf(calendarPrefs.getFontColor()) }
    var bgColor by remember { mutableStateOf(calendarPrefs.getBackgroundColor()) }
    var textScale by remember { mutableFloatStateOf(calendarPrefs.getTextScale()) }

    // Google Sign-In result handler
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            task.getResult(Exception::class.java)
            isSynced = driveSync.isSignedIn()
            syncStatus = if (isSynced) "Signed in — auto-sync enabled" else "Sign-in failed"
        } catch (e: Exception) {
            syncStatus = "Sign-in cancelled"
        }
    }

    // Storage permission result handler
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Title ─────────────────────────────────────────────────────────────
        Text(
            text = "MoodLauncher",
            fontSize = 26.sp,
            fontWeight = FontWeight.Thin,
            fontFamily = FontFamily.SansSerif,
            color = Color.Black,
            letterSpacing = 2.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        SectionLabel("Storage")

        if (!hasStoragePermission) {
            StatusText("Storage access required to save notes to Documents.")
            PrimaryButton("Grant Storage Access") {
                activity.suppressFinishOnStop = true
                storagePermLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                )
            }
        } else {
            StatusText("✓  Notes saved to /storage/emulated/0/moodlauncher/notes.json")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Open Calendar ──────────────────────────────────────────────────────
        SectionLabel("Default Launcher")
        PrimaryButton("Open Calendar") {
            activity.startActivity(Intent(context, MainActivity::class.java))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Calendar Appearance ───────────────────────────────────────────────
        SectionLabel("Calendar Appearance")

        StatusText("Background color")
        ColorHsvPicker(color = bgColor) {
            bgColor = it
            calendarPrefs.setBackgroundColor(it)
        }

        Spacer(modifier = Modifier.height(4.dp))

        StatusText("Font color")
        ColorHsvPicker(color = fontColor) {
            fontColor = it
            calendarPrefs.setFontColor(it)
        }

        Spacer(modifier = Modifier.height(4.dp))

        StatusText("Text size  ${(textScale * 100).toInt()}%")
        Slider(
            value = textScale,
            onValueChange = {
                textScale = it
                calendarPrefs.setTextScale(it)
            },
            valueRange = 0.7f..1.8f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Google Drive sync ─────────────────────────────────────────────────
        SectionLabel("Google Drive Sync")

        if (isSynced) {
            StatusText("✓  Signed in — notes auto-sync on every save.")

            OutlinedButton(
                onClick = {
                    scope.launch {
                        syncStatus = "Syncing…"
                        val raw = repository.readRaw()
                        val result = driveSync.syncFile(raw)
                        syncStatus = if (result.isSuccess) "Synced!" else
                            "Sync failed: ${result.exceptionOrNull()?.message}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black)
            ) {
                Text(
                    "Sync Now",
                    color = Color.Black,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp
                )
            }

            OutlinedButton(
                onClick = {
                    val signOutOptions = GoogleSignInOptions.Builder(
                        GoogleSignInOptions.DEFAULT_SIGN_IN
                    ).build()
                    GoogleSignIn.getClient(context, signOutOptions).signOut()
                    isSynced = false
                    syncStatus = "Signed out."
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCCCCCC))
            ) {
                Text(
                    "Sign Out",
                    color = Color(0xFF888888),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 14.sp
                )
            }
        } else {
            StatusText("Sign in with Google to back up and sync your notes automatically.")
            PrimaryButton("Sign in with Google Drive") {
                activity.suppressFinishOnStop = true
                val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(Scope(DriveSync.DRIVE_SCOPE))
                    .build()
                signInLauncher.launch(
                    GoogleSignIn.getClient(activity, options).signInIntent
                )
            }
        }

        if (syncStatus.isNotEmpty()) {
            StatusText(syncStatus)
        }
    }
}

// ── HSV Color Picker ──────────────────────────────────────────────────────────

@Composable
private fun ColorHsvPicker(
    color: Color,
    onColorChange: (Color) -> Unit
) {
    val initHsv = remember {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV(color.toArgb(), it)
        }
    }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initHsv[2]) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Color preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))))
        )

        StatusText("Hue  ${hue.toInt()}°")
        Slider(
            value = hue,
            onValueChange = { newHue ->
                hue = newHue
                onColorChange(Color(android.graphics.Color.HSVToColor(floatArrayOf(newHue, saturation, brightness))))
            },
            valueRange = 0f..360f,
            modifier = Modifier.fillMaxWidth()
        )

        StatusText("Saturation  ${(saturation * 100).toInt()}%")
        Slider(
            value = saturation,
            onValueChange = { newSat ->
                saturation = newSat
                onColorChange(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, newSat, brightness))))
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )

        StatusText("Brightness  ${(brightness * 100).toInt()}%")
        Slider(
            value = brightness,
            onValueChange = { newBri ->
                brightness = newBri
                onColorChange(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, newBri))))
            },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Small helper composables ──────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
        color = Color(0xFF888888),
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontFamily = FontFamily.SansSerif,
        color = Color(0xFF444444),
        lineHeight = 18.sp
    )
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}
