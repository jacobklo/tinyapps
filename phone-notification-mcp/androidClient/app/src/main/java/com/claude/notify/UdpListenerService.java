package com.claude.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class UdpListenerService extends Service {

    private static final String TAG = "ClaudeNotify";
    private static final int PORT = 19876;
    private static final String CHANNEL_LISTENER = "listener_channel";
    private static final String CHANNEL_NOTIFY = "notify_channel";
    private static final int FOREGROUND_ID = 1;
    private volatile boolean running = false;
    private DatagramSocket socket;
    private int notificationId = 100;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle copy-to-clipboard action
        if (intent != null && "COPY_SESSION_ID".equals(intent.getAction())) {
            String sessionId = intent.getStringExtra("session_id");
            if (sessionId != null) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("session_id", sessionId));
            }
            return START_STICKY;
        }

        Log.i(TAG, "Service onStartCommand called");
        startForeground(FOREGROUND_ID, buildListenerNotification());

        if (!running) {
            running = true;
            Log.i(TAG, "Starting UDP listener on port " + PORT);
            new Thread(this::listenLoop).start();
        }

        return START_STICKY;
    }

    private void listenLoop() {
        try {
            socket = new DatagramSocket(PORT);
            Log.i(TAG, "UDP socket bound to port " + PORT);
            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String sessionId = new String(packet.getData(), 0, packet.getLength()).trim();
                Log.i(TAG, "Received UDP packet from " + packet.getAddress().getHostAddress()
                    + ": '" + sessionId + "' (" + packet.getLength() + " bytes)");
                if (!sessionId.isEmpty()) {
                    postSessionNotification(sessionId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "UDP listener error: " + e.getMessage(), e);
        }
    }

    private void postSessionNotification(String sessionId) {
        Log.i(TAG, "Posting notification for session: " + sessionId);
        Intent copyIntent = new Intent(this, UdpListenerService.class);
        copyIntent.setAction("COPY_SESSION_ID");
        copyIntent.putExtra("session_id", sessionId);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, notificationId, copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Claude done")
            .setContentText(sessionId)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(notificationId++, notification);
    }

    private Notification buildListenerNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_LISTENER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Claude Notify")
            .setContentText("Listening for Claude notifications")
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel listenerChannel = new NotificationChannel(
            CHANNEL_LISTENER, "Listener Service",
            NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(listenerChannel);

        NotificationChannel notifyChannel = new NotificationChannel(
            CHANNEL_NOTIFY, "Claude Notifications",
            NotificationManager.IMPORTANCE_HIGH
        );
        manager.createNotificationChannel(notifyChannel);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (socket != null) socket.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
