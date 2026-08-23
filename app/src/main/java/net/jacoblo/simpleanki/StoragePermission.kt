/*
 * The MANAGE_EXTERNAL_STORAGE prompt.
 *
 * Extracted from MainActivity for the reason AnkiDrawer.kt was: that file sits against a
 * line ceiling, and this is the part of it with nothing to do with the game loop.
 */
package net.jacoblo.simpleanki

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * Says why the permission is needed, then opens the all-files-access screen.
 *
 * Two intents, because the per-package screen is the one worth landing on but not every
 * OEM build answers it; the general one always resolves. The caller checks whether access
 * is already held - there is nothing to prompt for when it is.
 */
fun Activity.promptForStorageAccess() {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
	Toast.makeText(this, "Please allow file access to load questions", Toast.LENGTH_LONG).show()
	try {
		startActivity(
			Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
				data = Uri.parse("package:$packageName")
			}
		)
	} catch (_: Exception) {
		startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
	}
}
