# AutoClicker

Records touch gestures on an Android phone and replays them as a script: taps and swipes, but also typing, waiting for something to appear on screen, branching on what it finds, and running as a step in someone else's automation.

Root is the interesting case. With it the app records straight off the touchscreen device and replays through the same node, so a replayed gesture carries the pressure curve and timing of the finger that made it. Without it there is an AccessibilityService fallback that can dispatch gestures and set text, and nothing else.

## The two backends

`useRoot` in settings picks one, and it is read per gesture, so the switch takes effect without restarting anything.

| | Root | Accessibility |
|---|---|---|
| Records by | reading `/dev/input/eventN` passively | a full-screen overlay that swallows each touch and re-injects it |
| Replays by | writing `input_event` structs to the same node, or `input swipe` if that node is not writable | `dispatchGesture` |
| Pressure and contact size | the recorded ones | none -- the platform sends constants |
| Keys, launching an app, shell | yes | no; the step logs and is skipped |
| Reading the screen | yes -- `screencap`, `uiautomator dump` | no |
| Announces itself | no | listed in `ENABLED_ACCESSIBILITY_SERVICES` |

Anything that reads the screen -- image anchors, text anchors, `Focus field`, and the conditions built on them -- needs root, because it goes through `screencap` or `uiautomator dump`.

## How it fits together

- **`Recording.kt`** -- the seventeen step types, the JSON codec, and the tap-versus-drag rule both recorders share.
- **`Blocks.kt`** -- the ten editor-only block markers, and `flatten`/`buildHierarchy`, which convert between a block as a tree node and a block as a start/end pair in the editor's flat list.
- **`Interpreter.kt`** -- runs a list of steps. Takes a `Backend` and a `Finder`, which is what makes it testable off a phone.
- **`Backend.kt`** / **`Finder.kt`** -- what a step does to the device, and what it can ask about it. `RootBackend`, `AccessibilityBackend`, `DeviceFinder`.
- **`GestureExecutor.kt`** -- owns a playback: the job, the stop, the wake lock, the observable playing flag, and evdev setup.
- **`Expression.kt`** / **`ScriptContext.kt`** -- the small language behind conditions, `Set variable`, and the `{braces}` in typed text and toasts. Built-ins: `contains`, `count`, `random`, `image`, `waitImage`, `textAppear`, `waitTextAppear`, `jq`.
- **Seeing the screen** -- `ScreenCapture` (raw `screencap`), `TemplateMatcher` (finds a saved area), `ScreenText` (ML Kit OCR, bundled, never leaves the device), `ViewHierarchy` (`uiautomator dump`).
- **Drivers** -- `Bubble` (the floating overlay), `TriggerRunner` (app opened/closed, screen on/off, unlocked, notification), `ControlServer` (another app).
- **UI** -- `MainActivity` (recordings), `EditorActivity`, `ScreenshotsActivity`, `TriggersActivity`, `SettingsActivity`, all Compose.

`NotificationService` is the foreground service that hosts the bubble, the trigger poller and the control server, and carries the Stop action. That action matters: the bubble's own stop button is a touch on the screen the script is driving, and under evdev replay a real finger shares a multitouch slot with the injected stream and is easily lost.

## Files

Everything the app owns is under `/sdcard/autoclicker/`, so a script, the images it matches against and the settings that drive it move as a unit. Needs all-files access.

```text
/sdcard/autoclicker/
-settings.json                 # useRoot, control port, jitter
-globals.json                  # variables a run starts from
-triggers.json                 # what fires which recording
-recordings/
--<name>.json                  # one script; {"globalRandom": N, "events": [...]}

-screenshots/
--index.json                   # name, file, crop rect, screen size it was cropped at
--<name>.png                   # the saved area itself
```

A recording is `{"globalRandom": N, "events": [...]}`, and each event carries `delayBefore`, `name`, and a `type` from: `click`, `drag`, `text`, `key`, `launch`, `shell`, `wait`, `comment`, `toast`, `focus_field`, `set`, `http_get`, `break`, `if`, `while`, `loop`, `random_select`. An unknown type is skipped rather than failing the load, so a file from a newer version still mostly runs -- but a file from an *older* one loses those steps silently, which is worth checking after a step type is renamed.

Coordinates are fractions of the screen when absolute, so a script survives a different display. When a step is anchored to a saved area or a phrase, they are **pixels** from wherever that was found -- a saved area only matches at the resolution it was captured at, so a fraction of some other screen would be wrong exactly when the anchor was right.

## Driving it from another app

`ControlServer` listens on **127.0.0.1:8128** (`controlPort` in settings). Line-delimited JSON, one request per line:

```json
{"reqId": "1", "cmd": "play", "args": {"recording": "signup.json"}}
{"reqId": "1", "status": "ok", "result": {"runId": 3, "events": 16}}
```

Commands: `status`, `play`, `stop`, `vars_list`, `vars_set`, `vars_delete`, `vars_clear`. Set globals first and the script reads them as variables, which is how one recording runs against different data.

**There is no authentication.** Loopback keeps it off the network, but any app on the device can connect, and `play` runs a recording whose `shell` steps execute as root. That is deliberate -- it exists so Droidvate can drive playback rather than reimplementing it -- but it is a real trust boundary and worth knowing before enabling root.

## Depends on

Nothing, in the app. `http_get` takes a URL, so what a recording talks to is the recording's business rather than a build-time dependency. Plain HTTP to a LAN address is expected, which is why `usesCleartextTraffic` is set.

The two shipped recordings do point at one thing: `gmail-six-digit` on the LAN, for the six-digit verification code. It serves `GET /codes` (real, from Gmail) and `GET /demo/codes` (generated, same shape), both a JSON array ranked best-first with a `code` and an `age_seconds` per entry, and both **404 with an empty body when there is nothing** -- which is what makes `http_get` usable against it, since a non-2xx reply keeps the poll waiting instead of storing a miss. Filtering out a code left over from a previous login is now the script's job rather than the step's:

```json
{"type": "http_get", "url": "http://host:5553/codes", "variable": "raw", "timeoutMs": 60000, "intervalMs": 2000}
{"type": "set", "variable": "codes", "expression": "jq(raw, '[.[] | select(.age_seconds <= 120) | .code]')"}
```

Single-quote the jq filter: the expression lexer takes either quote, and `'` avoids escaping every `"` inside the JSON. Note this polls until the service answers at all, not until a *fresh* code exists -- if it replies 200 with nothing but stale entries, the filter yields an empty list and the loop over `codes` does nothing.

## Building

`local.properties` may point at a stale SDK, so set the paths explicitly:

```bash
export JAVA_HOME=/path/to/android-studio/jbr
export ANDROID_HOME=/path/to/sdk
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleDebug
```

Unit tests run on the JVM and cover the expression language, the recording codec, the block flatten/rebuild round-trip, the template matcher, the tap-drag rule, and what each step type does. The last of those uses a fake `Backend` and `Finder`; nothing in the suite needs a device.

The release build minifies and strips `android.util.Log`, so anything you want to see in a release build must not be logged through it.
