package net.jacoblo.bulb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/** Home screen widget: three tap bars that drive the bulb over UDP. */
public class BulbWidget extends AppWidgetProvider {

	private static final String ACTION_SET = "net.jacoblo.bulb.SET";
	private static final String EXTRA_ROW = "row";
	private static final String EXTRA_VALUE = "value";

	/** Invisible tap targets laid over each bar; segment i sets the value to i * STEP. */
	private static final int STEP = 2;
	private static final int SEGMENTS = 100 / STEP + 1;

	private static final String[] KEYS = {"color", "white", "bright"};
	private static final int[] DEFAULTS = {33, 50, 70};

	@Override
	public void onReceive(Context context, Intent intent) {
		super.onReceive(context, intent);
		if (!ACTION_SET.equals(intent.getAction())) {
			return;
		}
		int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
		int row = intent.getIntExtra(EXTRA_ROW, 0);
		Bulb.prefs(context).edit().putInt(KEYS[row] + id, intent.getIntExtra(EXTRA_VALUE, 0)).apply();
		// Keep the broadcast alive until the packet is out, or the process may be
		// frozen the instant this method returns.
		final PendingResult pending = goAsync();
		push(context, id, new Runnable() {
			@Override
			public void run() {
				pending.finish();
			}
		});
		render(context, AppWidgetManager.getInstance(context), id);
	}

	@Override
	public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
		for (int id : ids) {
			render(context, manager, id);
		}
	}

	@Override
	public void onDeleted(Context context, int[] ids) {
		SharedPreferences.Editor edit = Bulb.prefs(context).edit();
		for (int id : ids) {
			for (String key : KEYS) {
				edit.remove(key + id);
			}
			edit.remove("ip" + id);
		}
		edit.apply();
	}

	static void render(Context context, AppWidgetManager manager, int id) {
		SharedPreferences prefs = Bulb.prefs(context);
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
		for (int row = 0; row < KEYS.length; row++) {
			int value = prefs.getInt(KEYS[row] + id, DEFAULTS[row]);
			views.setTextViewText(viewId(context, KEYS[row] + "_value"), String.valueOf(value));
			views.setProgressBar(viewId(context, KEYS[row] + "_bar"), 100, value, false);
			// 51 segments is far too many to hand-write as layout ids, so the strip is
			// built here instead. Each child carries its own click target.
			int strip = viewId(context, KEYS[row] + "_seg");
			views.removeAllViews(strip);
			for (int index = 0; index < SEGMENTS; index++) {
				RemoteViews segment = new RemoteViews(context.getPackageName(), R.layout.segment);
				segment.setOnClickPendingIntent(R.id.segment, tap(context, id, row, index));
				views.addView(strip, segment);
			}
		}
		manager.updateAppWidget(id, views);
	}

	static void push(Context context, int id, Runnable done) {
		SharedPreferences prefs = Bulb.prefs(context);
		Bulb.send(prefs.getString("ip" + id, Bulb.DEFAULT_IP), Bulb.pilot(prefs.getInt(KEYS[0] + id, DEFAULTS[0]), prefs.getInt(KEYS[1] + id, DEFAULTS[1]), prefs.getInt(KEYS[2] + id, DEFAULTS[2])), done);
	}

	private static PendingIntent tap(Context context, int id, int row, int index) {
		Intent intent = new Intent(context, BulbWidget.class).setAction(ACTION_SET).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(EXTRA_ROW, row).putExtra(EXTRA_VALUE, index * STEP);
		// Extras are ignored when the system matches PendingIntents, so every segment
		// needs its own request code or they would all fire whichever was built last.
		// 64 leaves room for the 51 segments; 256 keeps neighbouring widget ids apart.
		int request = id * 256 + row * 64 + index;
		return PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private static int viewId(Context context, String name) {
		return context.getResources().getIdentifier(name, "id", context.getPackageName());
	}
}
