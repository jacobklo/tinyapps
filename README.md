# TinyApps

A collection of small, single-purpose Android apps. Each app lives on its own branch.

All apps are built with Kotlin + Jetpack Compose, targeting Android 12+ (minSdk 31).

---

## Apps
Each branch in this repo is a tiny app.

### AutoClicker
**Branch:** `autoclicker`

Record and replay touch gestures (clicks and drags) on your Android device. Uses the Accessibility Service to inject gestures — no root required.

**How to use:**
1. Grant overlay, storage, and accessibility permissions
2. A floating bubble appears over other apps
3. Tap the red record button, perform your gestures, then tap stop
4. Select a recording from the list and tap the blue play button to replay

**Features:**
- Record clicks and multi-point drag paths with precise timing
- Edit recordings: adjust coordinates, durations, add randomness offsets
- Loop blocks (repeat N times) and random selection blocks (pick one action at random)
- Global random delay to humanize playback
- Recordings saved as JSON in `/sdcard/Recordings/`

---

### Calendar Announcement
**Branch:** `calendar-announcement`

Automatically announces upcoming calendar events using text-to-speech.

**How to use:**
1. Enable the background service and grant calendar/notification permissions
2. Select which calendars to monitor
3. Configure how many minutes before an event to announce (default: 10)
4. Customize TTS voice, pitch, speed, language, and audio channel
5. Tap Save to start

**Features:**
- Reads from device calendars and Google Calendar (via OAuth2)
- Periodic sync (configurable interval, default 10 min)
- Full TTS customization with test button
- Survives reboots and battery optimization
- Encrypted storage for OAuth tokens

---

### Mood Launcher
**Branch:** `mood-launcher`

An Android home launcher with a built-in year-long mood/emotion calendar.

**How to use:**
1. Set as your default home launcher
2. Tap any day on the calendar to add an emoji and notes
3. Swipe up to open the app drawer
4. Access Settings to customize colors, text size, and Google Drive sync

**Features:**
- Full-year calendar view (all 12 months) on home screen
- Per-day emoji and notes tracking, stored as JSON
- Google Drive backup and sync
- Live wallpaper mode showing the calendar
- Customizable colors and text scale via HSV picker
- Multi-profile app launcher (shows work profile apps with badge)

---

### Note Out Loud
**Branch:** `note-out-loud`

A web browser designed for reading practice with text-to-speech and word masking.

**How to use:**
1. Navigate to any URL
2. Press the play button to have the page read aloud paragraph by paragraph
3. Toggle word masking (eye icon) to blank out a percentage of words for comprehension practice
4. Use the Table of Contents sidebar to jump between sections

**Features:**
- Paragraph-by-paragraph TTS with highlighting and auto-scroll
- Sequential or shuffle playback mode
- Configurable word masking percentage (words replaced with underscores)
- Dark mode, zoom controls, multi-tab browsing
- Edit page source and inject custom JavaScript
- Custom blanking script editor

---

### Notepad
**Branch:** `notepad`

A multi-tab text editor with formatting support.

**How to use:**
1. Open files with the folder icon or create new ones with "+"
2. Edit text in a monospace editor with line numbers
3. Use the toolbar for find/replace, undo/redo, indent/unindent, and formatting
4. Save with the save icon

**Features:**
- Multi-tab editing with persistent state across sessions
- Auto-format JSON, XML, HTML, Markdown, and JSONL
- Find & replace, select lines by range
- 50-level undo/redo per tab
- Adjustable font size and dark/light theme
- Preserves LF/CRLF line endings
- Opens files via Android "Open with" intent

---

### OCR to Typst
**Branch:** `ocr-to-typst`

Capture your screen and convert text to Typst markup using OCR.

**How to use:**
1. Grant overlay, storage, and screen capture permissions
2. A floating bubble appears with a record button
3. Navigate to what you want to capture, then tap the record button
4. OCR results are saved as `ocr.typ` in `/Notes/`

**Features:**
- Screen capture via MediaProjection API
- Google ML Kit text recognition (Latin/English)
- Smart formatting: detects bold text, preserves indentation, maintains paragraph structure
- Output in Typst markup format (`*bold*`, spacing, etc.)
- Draggable floating bubble UI with trash-to-close

---

### Phone Notification (Claude Notify)
**Branch:** `phone-notification-mcp`

Get a notification on your phone when a Claude Code session finishes responding.

**How to use:**
1. Install the app and grant notification permissions
2. On your computer, set up the `notify.sh` hook script for Claude Code
3. When Claude finishes responding, a UDP broadcast is sent on your LAN
4. Your phone receives it and shows a notification with the session ID
5. Tap the notification to copy the session ID to clipboard

**Features:**
- Lightweight background UDP listener on port 19876
- No UI — runs entirely via notifications
- Persists across reboots
- Includes the bash hook script (`MCPServer/notify.sh`) for the computer side

---

### Random MP3 Alarm
**Branch:** `random-mp3-alarm`

An alarm clock that plays a random audio file from a folder you choose.

**How to use:**
1. Tap "+" to create a new alarm and set the time
2. Browse to select a folder containing audio files
3. Configure snooze count and duration
4. Enable the alarm and tap Save

**Features:**
- Plays a random file (mp3, m4a, ogg, wav, flac, aac) from a selected directory
- Optional recursive subdirectory search
- Configurable snooze count and duration per alarm
- Fullscreen alarm popup over lock screen
- Selectable audio channel (alarm, media, notification, ringtone, system)
- Multiple simultaneous alarms
- Persists across reboots

---

### Simple Anki
**Branch:** `simple-anki`

A minimal flashcard app with timed performance tracking.

**How to use:**
1. Place your cards in `SimpleAnki/simple-anki.json` on external storage (a sample file is created on first launch)
2. Tap a card to flip between question and answer
3. The app tracks how long you take to answer each card
4. View the Stats screen to see best/average/median/last times per card

**Card file format:**
```json
[
  {"question": "Capital of France?", "answer": "Paris"},
  {"question": "2 + 2?", "answer": "4"}
]
```

**Features:**
- Random card selection from loaded deck
- Tracks last 10 attempts per card with best/average/median/last time
- Sortable stats table
- Per-card stat reset
- Screen stays on during study
