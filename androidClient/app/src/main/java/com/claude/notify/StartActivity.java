package com.claude.notify;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

public class StartActivity extends Activity {

    private static final int NOTIFICATION_PERMISSION_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                new String[]{"android.permission.POST_NOTIFICATIONS"},
                NOTIFICATION_PERMISSION_CODE
            );
        } else {
            startServiceAndFinish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startServiceAndFinish();
    }

    private void startServiceAndFinish() {
        // Request battery optimization exemption
        Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        batteryIntent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(batteryIntent);

        // Start the listener service
        Intent serviceIntent = new Intent(this, UdpListenerService.class);
        startForegroundService(serviceIntent);

        finish();
    }
}
