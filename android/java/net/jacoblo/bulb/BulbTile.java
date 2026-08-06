package net.jacoblo.bulb;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings toggle, reachable from the lock screen shade. */
public class BulbTile extends TileService {

	private static final String STATE = "tile_on";

	@Override
	public void onStartListening() {
		render();
	}

	@Override
	public void onClick() {
		boolean on = !Bulb.prefs(this).getBoolean(STATE, false);
		Bulb.prefs(this).edit().putBoolean(STATE, on).apply();
		// State alone -- the bulb keeps whatever colour the widget last set.
		Bulb.send(Bulb.prefs(this).getString("ip", Bulb.DEFAULT_IP), "{\"method\":\"setPilot\",\"params\":{\"state\":" + on + "}}", null);
		render();
	}

	private void render() {
		Tile tile = getQsTile();
		if (tile == null) {
			return;
		}
		tile.setState(Bulb.prefs(this).getBoolean(STATE, false) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
		tile.updateTile();
	}
}
