package net.jacoblo.bulb;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** UDP control for a WiZ bulb, shared by the widget and the quick settings tile. */
final class Bulb {

	static final String DEFAULT_IP = "192.168.2.202";

	private static final String TAG = "bulb";
	private static final int PORT = 38899;

	private Bulb() {}

	static SharedPreferences prefs(Context context) {
		return context.getSharedPreferences("bulb", Context.MODE_PRIVATE);
	}

	/**
	 * Crossfade the RGB LEDs against the two white LEDs along the whiteness axis.
	 *
	 * <p>Whiteness runs cold white (0) through pure color (50) to warm white (100).
	 * Distance from the midpoint sets how much white is mixed in; the side it falls
	 * on picks which white channel carries it.
	 */
	static String pilot(int color, int whiteness, int brightness) {
		double tilt = (whiteness - 50) / 50.0;
		long white = Math.round(Math.abs(tilt) * 255);
		// The white LEDs draw roughly 4x the current of the color ones, so RGB has
		// to fade out as white comes up or the color washes out to nothing.
		double scale = 1.0 - Math.abs(tilt);
		int rgb = Color.HSVToColor(new float[] {(color % 100) * 3.6f, 1f, 1f});
		return "{\"method\":\"setPilot\",\"params\":{"
				+ "\"state\":" + (brightness > 0)
				+ ",\"dimming\":" + Math.max(10, brightness)
				+ ",\"r\":" + Math.round(Color.red(rgb) * scale)
				+ ",\"g\":" + Math.round(Color.green(rgb) * scale)
				+ ",\"b\":" + Math.round(Color.blue(rgb) * scale)
				+ ",\"c\":" + (tilt < 0 ? white : 0)
				+ ",\"w\":" + (tilt > 0 ? white : 0)
				+ "}}";
	}

	/**
	 * Send on a worker thread, then run {@code done} (may be null).
	 *
	 * <p>A receiver must pass its goAsync() finisher here: once onReceive returns the
	 * system is free to freeze the process, which would strand the packet.
	 */
	static void send(final String ip, final String json, final Runnable done) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try (DatagramSocket socket = new DatagramSocket()) {
					byte[] data = json.getBytes(StandardCharsets.UTF_8);
					socket.send(new DatagramPacket(data, data.length, InetAddress.getByName(ip), PORT));
				} catch (Exception e) {
					// EPERM here means the OS blocked the socket, not that the bulb is down.
					Log.e(TAG, "send to " + ip + " failed", e);
				} finally {
					if (done != null) {
						done.run();
					}
				}
			}
		}).start();
	}
}
