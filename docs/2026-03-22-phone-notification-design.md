# Phone Notification System — Design Spec

## Overview

Two components that communicate over UDP on a local network. A Claude Code hook fires after every agent response, sending the session ID via UDP broadcast. An Android app with no UI listens for these packets and shows a notification.

## Component 1: Claude Code Hook (shell script)

- A bash script at `MCPServer/notify.sh` that sends a UDP packet containing the session ID to three subnet broadcast addresses on port `19876`:
  - `192.168.0.255`
  - `192.168.1.255`
  - `192.168.2.255`
- Uses `echo` + `/dev/udp` or `socat` — no dependencies beyond bash
- Configured as a Claude Code `stop` hook in `settings.json` so it fires automatically after every agent turn
- Payload: the Claude Code session ID string (plain text, no framing)

## Component 2: Android Client

- **No persistent Activity** — a minimal launcher `StartActivity` runs once to request permissions and start the service, then immediately finishes. Day-to-day operation is just a `Service` and a `BroadcastReceiver`
- `BootReceiver` starts the service on device boot
- `UdpListenerService` runs as a **foreground service** (persistent notification saying "Listening for Claude notifications") — required by Android to prevent the OS from killing it
- On receiving a UDP packet on port `19876`, posts a notification with the session ID
- Tapping the notification copies the session ID to clipboard
- **Permissions:**
  - `RECEIVE_BOOT_COMPLETED` — start on boot
  - `FOREGROUND_SERVICE` — keep service alive
  - `POST_NOTIFICATIONS` — show notifications
  - `INTERNET` — for UDP socket
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prevent OS from killing the process
- **Target:** minimum SDK 26 (Android 8.0+)

## Data Flow

```
Claude Code finishes response
  -> Stop hook pipes JSON (containing session_id) to notify.sh via stdin
  -> notify.sh parses session_id and sends UDP broadcast to 192.168.0/1/2.255:19876
  -> Android UdpListenerService receives packet
  -> Posts notification "Claude done: <session_id>"
  -> Tap -> copies session_id to clipboard
```

## Project Structure

```
phone-notification-mcp/
  MCPServer/
    notify.sh              # UDP broadcast script
  androidClient/
    app/
      src/main/
        java/.../
          BootReceiver.java
          UdpListenerService.java
        AndroidManifest.xml
      build.gradle
    build.gradle
    settings.gradle
```

## What's NOT Included (YAGNI)

- No authentication/encryption (local network only)
- No delivery confirmation (fire-and-forget UDP)
- No UI/Activity on Android
- No server discovery/mDNS
- No internet/public network support
