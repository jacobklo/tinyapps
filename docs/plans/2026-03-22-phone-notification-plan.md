# Phone Notification System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notify an Android phone via UDP broadcast whenever a Claude Code session finishes responding.

**Architecture:** A Claude Code `Stop` hook runs a bash script that reads the session ID from stdin JSON and broadcasts it via UDP to three /24 subnets. An Android foreground service listens on the UDP port and posts a notification.

**Tech Stack:** Bash (hook script), Java + Android SDK 26+ (Android client), Gradle (build system)

**IMPORTANT:** No git operations. No commits. No version control commands.

---

### Task 1: Claude Code Hook Script

**Files:**
- Create: `MCPServer/notify.sh`

- [ ] **Step 1: Create the notify.sh script**

```bash
#!/bin/bash
# Claude Code Stop hook — broadcasts session ID via UDP

INPUT=$(cat)
SESSION_ID=$(echo "$INPUT" | grep -oP '"session_id"\s*:\s*"\K[^"]*')

if [ -z "$SESSION_ID" ]; then
  exit 0
fi

PORT=19876
BROADCAST_ADDRS="192.168.0.255 192.168.1.255 192.168.2.255"

for ADDR in $BROADCAST_ADDRS; do
  echo -n "$SESSION_ID" > /dev/udp/$ADDR/$PORT 2>/dev/null
done

exit 0
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x MCPServer/notify.sh`

- [ ] **Step 3: Test the script locally**

Run: `echo '{"session_id":"test-123","stop_hook_active":false}' | bash MCPServer/notify.sh; echo "exit: $?"`
Expected: exit code 0, no errors

---

### Task 2: Claude Code Hook Configuration

**Files:**
- Create: `.claude/settings.local.json` (if not exists, or modify if exists)

- [ ] **Step 1: Create the hook configuration**

The `Stop` hook config should be added to `.claude/settings.local.json`:

```json
{
  "hooks": {
    "Stop": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "bash MCPServer/notify.sh",
            "timeout": 5
          }
        ]
      }
    ]
  }
}
```

Note: Use `settings.local.json` so this is local to this project only.

- [ ] **Step 2: Verify the hook config is valid JSON**

Run: `cat .claude/settings.local.json | python3 -m json.tool`
Expected: Pretty-printed JSON with no errors

---

### Task 3: Android Project Scaffolding

**Files:**
- Create: `androidClient/build.gradle`
- Create: `androidClient/settings.gradle`
- Create: `androidClient/gradle.properties`
- Create: `androidClient/app/build.gradle`
- Create: `androidClient/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create root build.gradle**

```groovy
// androidClient/build.gradle
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.2.0'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
```

- [ ] **Step 2: Create settings.gradle**

```groovy
// androidClient/settings.gradle
rootProject.name = 'ClaudeNotify'
include ':app'
```

- [ ] **Step 3: Create gradle.properties**

```properties
android.useAndroidX=true
```

- [ ] **Step 4: Create app/build.gradle**

```groovy
// androidClient/app/build.gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.claude.notify'
    compileSdk 34

    defaultConfig {
        applicationId "com.claude.notify"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.core:core:1.12.0'
}
```

- [ ] **Step 5: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:label="Claude Notify"
        android:supportsRtl="true">

        <receiver
            android:name=".BootReceiver"
            android:exported="true"
            android:enabled="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <service
            android:name=".UdpListenerService"
            android:exported="false"
            android:foregroundServiceType="specialUse" />

    </application>
</manifest>
```

---

### Task 4: UdpListenerService

**Files:**
- Create: `androidClient/app/src/main/java/com/claude/notify/UdpListenerService.java`

- [ ] **Step 1: Create the UdpListenerService**

This service:
1. Runs as a foreground service with a persistent "Listening" notification
2. Opens a UDP socket on port 19876
3. When a packet arrives, posts a notification with the session ID
4. Tapping the notification copies session ID to clipboard

```java
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
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class UdpListenerService extends Service {

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

        startForeground(FOREGROUND_ID, buildListenerNotification());

        if (!running) {
            running = true;
            new Thread(this::listenLoop).start();
        }

        return START_STICKY;
    }

    private void listenLoop() {
        try {
            socket = new DatagramSocket(PORT);
            byte[] buffer = new byte[1024];

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String sessionId = new String(packet.getData(), 0, packet.getLength()).trim();
                if (!sessionId.isEmpty()) {
                    postSessionNotification(sessionId);
                }
            }
        } catch (Exception e) {
            // Socket closed or error — service will restart via START_STICKY
        }
    }

    private void postSessionNotification(String sessionId) {
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
```

---

### Task 5: BootReceiver

**Files:**
- Create: `androidClient/app/src/main/java/com/claude/notify/BootReceiver.java`

- [ ] **Step 1: Create the BootReceiver**

```java
package com.claude.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, UdpListenerService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
```

---

### Task 6: First-Launch Helper Activity

**Files:**
- Create: `androidClient/app/src/main/java/com/claude/notify/StartActivity.java`
- Modify: `androidClient/app/src/main/AndroidManifest.xml`

Since the service can't start itself on first install (boot receiver only triggers on reboot), we need a minimal activity that:
1. Requests notification permission (required on Android 13+)
2. Requests battery optimization exemption
3. Starts the foreground service
4. Immediately finishes (no UI stays visible)

- [ ] **Step 1: Create StartActivity**

```java
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
```

- [ ] **Step 2: Add StartActivity to AndroidManifest.xml**

Add inside the `<application>` tag:

```xml
        <activity
            android:name=".StartActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

---

### Task 7: Gradle Wrapper

**Files:**
- Create: `androidClient/gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Add Gradle wrapper properties**

Create `androidClient/gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 2: Generate full Gradle wrapper**

On a machine with Gradle or Android Studio installed, run from the `androidClient/` directory:

```bash
cd androidClient
gradle wrapper --gradle-version 8.5
```

This generates `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`. If Gradle is not installed, open the project in Android Studio which will generate the wrapper automatically.

---

### Task 8: End-to-End Test

- [ ] **Step 1: Test the hook script with a direct UDP send**

Verify the script runs without error and sends UDP packets:

```bash
# Test 1: Verify script parses JSON and exits cleanly
echo '{"session_id":"test-session-abc123"}' | bash MCPServer/notify.sh; echo "exit: $?"
# Expected: exit: 0

# Test 2: Send a direct UDP packet to localhost and verify with socat
# Terminal 1:
socat UDP-RECV:19876 STDOUT &
SOCAT_PID=$!
# Terminal 2:
echo -n "test-session-123" > /dev/udp/127.0.0.1/19876
sleep 1
kill $SOCAT_PID 2>/dev/null
# Expected: "test-session-123" printed in terminal 1
```

- [ ] **Step 2: Build the Android app**

On a machine with Android SDK:

```bash
cd androidClient
./gradlew assembleDebug
```

The APK will be at `androidClient/app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 3: Install and test on Android device**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. Open "Claude Notify" from launcher — it requests permissions and starts the service
2. Verify persistent "Listening" notification appears
3. From dev machine: `echo -n "test-session-123" > /dev/udp/<phone-ip>/19876`
4. Verify notification appears on phone with "test-session-123"
5. Tap notification — verify session ID copied to clipboard
