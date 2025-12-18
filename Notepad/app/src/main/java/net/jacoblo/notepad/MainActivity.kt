package net.jacoblo.notepad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    private val notepadState = NotepadState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        restoreState()
        
        // Handle "Open With" intent
        handleIntent(intent)

        setContent {
            // Apply theme based on state
            val colorScheme = if (notepadState.isDarkMode) darkColorScheme() else lightColorScheme()
            
            MaterialTheme(colorScheme = colorScheme) {
                NotepadApp(state = notepadState)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        saveState()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            intent.data?.let { uri ->
                notepadState.loadFile(this, uri)
            }
        }
    }

    private fun saveState() {
        val prefs = getSharedPreferences("notepad_prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Save tabs URIs
        val uris = notepadState.tabs.mapNotNull { it.uri?.toString() }.toSet()
        editor.putStringSet("open_uris", uris)
        
        // Save settings
        editor.putBoolean("dark_mode", notepadState.isDarkMode)
        editor.putFloat("font_size", notepadState.fontSize)
        
        editor.apply()
    }
    
    private fun restoreState() {
         val prefs = getSharedPreferences("notepad_prefs", MODE_PRIVATE)
         notepadState.isDarkMode = prefs.getBoolean("dark_mode", true)
         notepadState.fontSize = prefs.getFloat("font_size", 14f)
         
         val uris = prefs.getStringSet("open_uris", emptySet())
         uris?.forEach { uriString ->
             try {
                 val uri = Uri.parse(uriString)
                 notepadState.loadFile(this, uri)
             } catch (e: Exception) {
                 e.printStackTrace()
             }
         }
    }
}
