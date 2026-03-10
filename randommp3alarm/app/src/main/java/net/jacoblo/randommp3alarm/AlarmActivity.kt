package net.jacoblo.randommp3alarm

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import net.jacoblo.randommp3alarm.ui.theme.RandomMp3AlarmTheme

class AlarmActivity : ComponentActivity() {

    private var alarmId: Int = -1
    private var snoozeRemaining: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show above lock screen and wake screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // True fullscreen - hide status and nav bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        alarmId = intent.getIntExtra("alarm_id", -1)
        snoozeRemaining = intent.getIntExtra("snooze_remaining", 0)

        setContent {
            RandomMp3AlarmTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFB71C1C))
                        .clickable { handleDismiss() }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = "Alarm",
                            tint = Color.White,
                            modifier = Modifier.size(160.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "ALARM",
                            color = Color.White,
                            fontSize = 52.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (snoozeRemaining > 0) {
                            Text(
                                text = "Tap anywhere or press a\nvolume/power button to snooze\n($snoozeRemaining snooze(s) remaining)",
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                fontSize = 16.sp
                            )
                        } else {
                            Text(
                                text = "Tap anywhere to dismiss",
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                fontSize = 16.sp
                            )
                        }
                    }

                    // Stop completely button - overlaid at bottom, separate from the clickable column
                    Button(
                        onClick = { handleStopAll() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Black.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 60.dp)
                    ) {
                        Text("Stop Completely", color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_POWER -> {
                    handleDismiss()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        handleDismiss()
    }

    private fun handleDismiss() {
        // Stops audio only; snooze was already scheduled by the service
        startService(
            Intent(this, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra("alarm_id", alarmId)
            }
        )
        finish()
    }

    private fun handleStopAll() {
        // Stops audio AND cancels the pre-scheduled snooze
        startService(
            Intent(this, AlarmService::class.java).apply {
                action = AlarmService.ACTION_STOP_ALL
                putExtra("alarm_id", alarmId)
            }
        )
        finish()
    }
}
