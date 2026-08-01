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
		Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
			Row(
				modifier = Modifier.fillMaxWidth().padding(16.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text("Use Root", style = MaterialTheme.typography.bodyLarge)
					Text(
						"Inject gestures with the root input command instead of the Accessibility Service. The Accessibility Service is no longer needed, but drags replay as a straight line from the first to the last point.",
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
									val granted = withContext(Dispatchers.IO) { RootShell.open() }
									requesting = false
									useRoot = granted
									AppSettings.useRoot = granted
									if (!granted) {
										Toast.makeText(context, "Root access denied", Toast.LENGTH_LONG).show()
									}
								}
							} else {
								useRoot = false
								AppSettings.useRoot = false
								scope.launch { withContext(Dispatchers.IO) { RootShell.close() } }
							}
						}
					)
				}
			}
		}
	}
}
