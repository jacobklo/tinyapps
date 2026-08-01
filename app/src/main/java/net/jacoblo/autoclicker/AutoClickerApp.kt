package net.jacoblo.autoclicker

import android.app.Application

class AutoClickerApp : Application() {

	override fun onCreate() {
		super.onCreate()
		AppSettings.init(this)
		// Reclaim the root shell after a process restart so the bubble is usable
		// straight away, without waiting for the user to open the settings screen.
		if (AppSettings.useRoot) {
			Thread {
				if (RootShell.open()) GestureExecutor.prepareRoot()
			}.start()
		}
	}
}
