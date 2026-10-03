# Capabilities (draft v0.1)

## 1. Standard capability catalog

Tier meanings are defined in [PROTOCOL.md §5.2](PROTOCOL.md#52-permission-tiers): 0 = inform, 1 = scoped read, 2 = act/sensitive (confirm every call), 3 = never remote.

| Capability | Tier | Purpose | Key params |
|---|---|---|---|
| `device.status` | 0 | Battery, charging, network type, locked/unlocked | — |
| `notify.show` | 0 | Show a notification in a user-defined channel | `title`, `body`, `channel`, `priority`, `actions[]`, `ttl_s` |
| `notify.cancel` | 0 | Remove a notification Talaria posted | `notification_id` |
| `tts.speak` | 0 | Speak text aloud | `text`, `voice?`, `interrupt?` |
| `haptic.pulse` | 0 | Vibrate with a pattern | `pattern` |
| `location.get` | 1 | Current location (coarse by default) | `precision` (`coarse`/`fine`) |
| `notifications.recent` | 1 | Recent notifications from **allow-listed apps**, redacted | `since`, `apps?`, `limit` |
| `sms.search` | 1 | Search SMS from **allow-listed senders**, redacted | `sender_regex`, `since`, `limit` |
| `calendar.upcoming` | 1 | Upcoming events (titles and times only by default) | `hours` |
| `events.query` | 1 | Query the device's own event log | `type`, `since` |
| `rules.list` | 1 | List rules (read-only) | — |
| `clipboard.get` | 2 | Read the clipboard | — |
| `clipboard.set` | 2 | Write the clipboard | `text` |
| `camera.capture` | 2 | Take a photo | `lens`, `max_px` |
| `screen.capture` | 2 | Screenshot | — |
| `audio.record` | 2 | Record a short clip | `seconds` (≤ 60) |
| `sms.send` | 2 | Send an SMS | `to`, `text` |
| `url.open` | 2 | Open a URL in the browser | `url` |
| `app.open` | 2 | Launch an app | `package` / `bundle_id` / `path` |
| `script.run` | 2 | Run a **pre-registered** script by name | `name`, `args` (typed per script manifest) |
| `rules.propose` | 2 | Propose a new or changed rule | `rule` |
| `timer.set` / `alarm.set` | 2 | Set a timer or alarm (agent-initiated) | `seconds` / `time`, `label` |
| `call.start` | 2 | Start a phone call | `contact` or `number` |
| `media.control` | 2 | Play, pause, skip in the active media session | `action` |
| `dnd.set` | 2 | Turn Do Not Disturb on or off | `mode`, `until?` |

Commands **you speak** to the on-device assistant router (e.g. "set a timer for 10 minutes") run locally without approval, because you initiated them. The tier 2 entries above apply when **the agent** asks for these actions remotely.
| `files.read` | 2 | Read a file from a **user-shared folder** | `path` |
| `files.write` | 2 | Write into a **user-shared folder** | `path`, `content` |
| `security.*`, `keys.*`, `filters.*`, `permissions.*` | 3 | Never remote | — |

### `script.run` details

Scripts are **never** sent by the agent. The user registers each one on the device with a manifest:

```yaml
# ~/.config/talaria/scripts/backup.yaml
name: backup
description: Push ~/projects to GitHub
command: ["/home/meep/bin/backup.sh"]
args:
  repo: {type: string, enum: ["dashboard", "notes"]}
timeout_s: 120
tier: 2              # may be raised, never lowered
allow_session_grant: true
```

The agent can call only `script.run {name: "backup", args: {repo: "dashboard"}}`. Arguments are validated against the manifest and passed as an argument array, **never through a shell string**.

## 2. Platform support matrix

Legend: ✅ supported · ⚠️ partial or with caveats · ❌ not possible · 🔜 planned later

| Capability | Android | Windows | Linux | iOS |
|---|---|---|---|---|
| Chat | ✅ | ✅ | ✅ | ✅ |
| `device.status` | ✅ `BatteryManager`, `ConnectivityManager` | ✅ WinRT `Battery`, `NetworkInformation` | ✅ UPower, NetworkManager (D-Bus) | ✅ `UIDevice`, `NWPathMonitor` |
| `notify.show` | ✅ `NotificationManager` channels | ✅ App notifications (Windows App SDK) | ✅ `org.freedesktop.Notifications` | ✅ `UNUserNotificationCenter` |
| `tts.speak` | ✅ `TextToSpeech` | ✅ `SpeechSynthesizer` | ⚠️ speech-dispatcher / espeak | ✅ `AVSpeechSynthesizer` (foreground) |
| `location.get` | ✅ Fused Location (or `LocationManager` in builds without Google services) | ⚠️ `Geolocator` (often Wi-Fi based) | ⚠️ GeoClue2 | ✅ CoreLocation |
| Geofence triggers | ✅ `GeofencingClient` / own logic | ❌ (rarely meaningful) | ❌ | ⚠️ Region monitoring (max 20 regions) |
| `notifications.recent` / `notification.posted` | ✅ `NotificationListenerService` | ⚠️ `UserNotificationListener` (consent plus package identity) | ⚠️ D-Bus monitoring (fragile, varies by desktop) | ❌ Not allowed |
| `sms.search` / `sms.received` | ✅ SMS permissions (sideload only) | ❌ | ❌ | ❌ |
| `sms.send` | ✅ | ❌ | ❌ | ❌ (the user can only be shown a compose sheet) |
| `calendar.upcoming` | ✅ `CalendarContract` | ⚠️ Appointments API | ⚠️ via EDS/CalDAV | ✅ EventKit |
| `clipboard.*` | ⚠️ Foreground only (Android 10+) | ✅ | ✅ | ⚠️ Foreground, with a paste prompt |
| `camera.capture` | ✅ CameraX | ✅ MediaCapture | ✅ V4L2 / PipeWire | ⚠️ App in foreground |
| `screen.capture` | ⚠️ MediaProjection (consent each session) | ✅ Graphics Capture API | ⚠️ PipeWire portal (Wayland asks every time) | ❌ |
| `audio.record` | ✅ (foreground service type `microphone`) | ✅ | ✅ | ⚠️ Foreground |
| `script.run` | ⚠️ Embedded JS sandbox; system actions via Shizuku | ✅ PowerShell / exe | ✅ any executable | ❌ (Shortcuts via App Intents only) |
| `app.open` / `url.open` | ✅ Intents | ✅ | ✅ `xdg-open` | ⚠️ URL schemes |
| `files.*` (shared folder) | ⚠️ Storage Access Framework | ✅ | ✅ | ⚠️ Files app container |
| Background rules | ✅ Foreground service + `WorkManager`/alarms (needs battery exemption) | ✅ Tray app / startup task | ✅ systemd user service / autostart | ❌ Mostly no (push wake only) |
| Global hotkey → talk to agent | ❌ (Quick Settings tile instead) | ✅ | ⚠️ X11 yes; Wayland via portal | ❌ (Action Button / Shortcut) |
| Share sheet → agent | ✅ | ⚠️ Share target (packaged app) | ⚠️ Desktop entry / CLI | ✅ Share extension |
| Relay watch | ✅ Wear OS Data Layer | ❌ | ❌ | 🔜 watchOS companion |
| Relay glasses | 🔜 Vendor SDKs via the phone | ❌ | ❌ | 🔜 |
| Wake push | ✅ ntfy / UnifiedPush | ✅ (persistent socket) | ✅ (persistent socket) | ⚠️ APNs via relay |

## 3. Client features per platform

These are app features rather than capabilities the agent can call.

| Feature | Android | Windows | Linux | iOS |
|---|---|---|---|---|
| Pair by QR scan | ✅ CameraX + ZXing (no Google dependency) | ⚠️ Webcam, rarely used | ⚠️ Webcam, rarely used | ✅ |
| Pair by link / code | ✅ `talaria://` deep link | ✅ Scheme handler + paste box | ✅ Scheme handler + paste box | ✅ |
| On-device speech-to-text | ✅ `SpeechRecognizer` on-device (API 31+), whisper.cpp later | ⚠️ `Windows.Media.SpeechRecognition` or whisper.cpp | ⚠️ whisper.cpp / Vosk | ✅ `SFSpeechRecognizer` (on-device mode) |
| Spoken replies (TTS) | ✅ | ✅ | ⚠️ | ✅ |
| Attach photo / PDF / file | ✅ Photo picker, SAF | ✅ | ✅ | ✅ |
| Image downscale + GPS strip | ✅ | ✅ | ✅ | ✅ |
| Agent / group chats, agent profiles | ✅ | ✅ | ✅ | ✅ |
| Workflow view | ✅ | ✅ | ✅ | ✅ |
| Voice replies: pre-filled chat (click-to-chat, share) | ✅ | ✅ (opens WhatsApp/Telegram desktop) | ✅ (web or desktop clients) | ✅ URL schemes |
| Voice replies: hands-free via notification Reply action | ✅ (notification access) | ❌ | ❌ | ❌ |
| Voice replies: SMS | ✅ (sideloaded build) | ❌ | ❌ | ⚠️ Compose sheet only |
| Incoming messages read aloud | ✅ (notification access) | ⚠️ | ⚠️ | ❌ |
| Default digital assistant role | ✅ `ACTION_ASSIST`, then `VoiceInteractionService` | ⚠️ Global hotkey instead | ⚠️ Global hotkey instead | ❌ (Action Button / Shortcut) |
| Launch from power button / corner swipe / headset | ✅ | — | — | ⚠️ Action Button |
| Assistant overlay on top of other apps | ✅ `VoiceInteractionSession` | ✅ Floating window | ✅ Floating window | ❌ |
| Assistant on lock screen (safe actions only) | ✅ | — | — | ❌ |
| Screen context ("what's on my screen?") | ✅ Assist API, opt-in per use | ⚠️ Screenshot with consent | ⚠️ Portal screenshot | ❌ |
| On-device command router (timers, alarms, calls, apps, media) | ✅ Standard intents | ⚠️ Subset | ⚠️ Subset | ⚠️ Via Shortcuts |
| Custom wake word | ⚠️ Foreground service + on-device engine; battery and mic indicator | ✅ | ✅ | ❌ |
| Replace "Hey Google" / Android Auto assistant | ❌ | — | — | ❌ |
| Talaria agent chats in Android Auto | 🔜 Messaging notifications (MessagingStyle + reply/mark-read) | — | — | 🔜 CarPlay |

## 4. Platform notes

### Android (reference platform)
- **Battery management is the number-one reliability risk.** OnePlus/OxygenOS, Xiaomi, Oppo, Vivo, Realme and Samsung stop background apps aggressively. Onboarding MUST walk through: exclusion from battery optimisation, autostart, "lock in recents", and on OnePlus, the per-app battery setting "Allow background activity". See dontkillmyapp.com for vendor steps.
- **Distribution:** SMS, notification-listener and Accessibility access are restricted on Google Play. Ship through **GitHub Releases, F-Droid and Obtainium**. A future Play build MAY omit SMS features.
- **Shizuku** (optional) unlocks system toggles (Wi-Fi, mobile data and so on) without root. It is opt-in and announced as separate `x-shizuku.*` capabilities until standardised.

### Windows
- Unsigned builds trigger SmartScreen. Early releases document this; signing comes later.
- Reading other apps' notifications needs package identity (MSIX or a sparse package) and user consent.

### Linux
- Target GNOME and KDE on Wayland first. Capture features go through xdg-desktop-portal and prompt each time; that is a feature, not a bug.
- Packaging: DEB/RPM from the build tooling; AppImage/Flatpak are welcome contributions.

### iOS (last)
- Building needs macOS (GitHub Actions macOS runners are free for public repos). Distribution needs an Apple Developer account. Background push needs APNs, which means a relay holding Apple credentials.
- Expect: chat, notifications, location and region triggers, camera and Shortcuts integration. No SMS, no reading other apps' notifications, almost no background automation.
