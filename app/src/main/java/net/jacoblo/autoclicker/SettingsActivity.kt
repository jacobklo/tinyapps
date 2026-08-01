package net.jacoblo.autoclicker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jacoblo.autoclicker.ui.theme.AutoClickerTheme

class SettingsActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			AutoClickerTheme {
				SettingsScreen(onBack = { finish() })
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var useRoot by remember { mutableStateOf(AppSettings.useRoot) }
	var requesting by remember { mutableStateOf(false) }
	var evdevReady by remember { mutableStateOf(GestureExecutor.evdevReady) }
	var jitter by remember { mutableStateOf(AppSettings.jitter) }

	fun updateJitter(next: JitterConfig) {
		jitter = next
		AppSettings.jitter = next
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Settings") },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
					}
				}
			)
		}
	) { innerPadding ->
		Column(
			modifier = Modifier
				.padding(innerPadding)
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
		) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(16.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text("Use Root", style = MaterialTheme.typography.bodyLarge)
					Text(
						"Record and replay straight through the touchscreen device. The Accessibility Service is not needed, nothing is overlaid on the target app, and replayed touches carry the real pressure and timing that were captured.",
						style = MaterialTheme.typography.bodySmall
					)
				}
				Spacer(modifier = Modifier.width(16.dp))
				if (requesting) {
					CircularProgressIndicator(modifier = Modifier.width(24.dp))
				} else {
					Switch(
						checked = useRoot,
						onCheckedChange = { wanted ->
							if (wanted) {
								requesting = true
								scope.launch {
									val granted = withContext(Dispatchers.IO) {
										RootShell.open() && GestureExecutor.prepareRoot()
									}
									// Root without a writable touchscreen still works
									// through `input swipe`, so keep it enabled.
									val rooted = withContext(Dispatchers.IO) { RootShell.isOpen }
									requesting = false
									useRoot = rooted
									AppSettings.useRoot = rooted
									evdevReady = granted
									if (!rooted) {
										Toast.makeText(context, "Root access denied", Toast.LENGTH_LONG).show()
									}
								}
							} else {
								useRoot = false
								AppSettings.useRoot = false
								evdevReady = false
								scope.launch {
									withContext(Dispatchers.IO) {
										GestureExecutor.releaseRoot()
										RootShell.close()
									}
								}
							}
						}
					)
				}
			}

			if (useRoot && !evdevReady) {
				Text(
					"Touchscreen device unavailable. Falling back to the input command, which replays drags as a straight line with constant pressure.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 16.dp)
				)
			}

			if (useRoot && evdevReady) {
				HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
				Text(
					"Humanization",
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.padding(horizontal = 16.dp)
				)
				Text(
					"Random variation added to every replayed touch sample. Applied on top of the per-interaction random factors.",
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.padding(horizontal = 16.dp)
				)

				JitterSlider("Position", jitter.positionPx, "px", 0f, 10f) {
					updateJitter(jitter.copy(positionPx = it))
				}
				JitterSlider("Pressure", jitter.pressurePct, "%", 0f, 40f) {
					updateJitter(jitter.copy(pressurePct = it))
				}
				JitterSlider("Timing", jitter.timingPct, "%", 0f, 40f) {
					updateJitter(jitter.copy(timingPct = it))
				}
				JitterSlider("Contact size", jitter.sizePct, "%", 0f, 40f) {
					updateJitter(jitter.copy(sizePct = it))
				}
			}
		}
	}
}

@Composable
private fun JitterSlider(
	label: String,
	value: Int,
	unit: String,
	min: Float,
	max: Float,
	onChange: (Int) -> Unit
) {
	Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
		Text("$label  +/- $value $unit", style = MaterialTheme.typography.bodyMedium)
		Slider(
			value = value.toFloat(),
			onValueChange = { onChange(it.toInt()) },
			valueRange = min..max,
			steps = (max - min).toInt() - 1
		)
	}
}
