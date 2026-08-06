package net.jacoblo.bulb;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

/** Asks for the bulb IP once, when the widget is placed. */
public class ConfigActivity extends Activity {

	private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		// Backing out without saving has to leave no widget behind.
		setResult(RESULT_CANCELED);
		setContentView(R.layout.config);

		widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
		if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
			finish();
			return;
		}

		final EditText ip = findViewById(R.id.ip);
		ip.setText(Bulb.prefs(this).getString("ip" + widgetId, Bulb.DEFAULT_IP));

		findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				String address = ip.getText().toString().trim();
				// The tile has no widget id of its own, so it reads the bare "ip" key.
				Bulb.prefs(ConfigActivity.this).edit().putString("ip" + widgetId, address).putString("ip", address).apply();
				BulbWidget.render(ConfigActivity.this, AppWidgetManager.getInstance(ConfigActivity.this), widgetId);
				setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId));
				finish();
			}
		});
	}
}
