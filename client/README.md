# Talaria clients

Kotlin Multiplatform. The Android app and the Windows/Linux desktop app share everything below the UI; iOS comes after v1. M1 builds these modules:

| Module | Status | What it is |
|---|---|---|
| `core/protocol` | **built** | TNP in Kotlin: `frame()`, base64url, key ids, P-256 signature checks, the signed-data builders, SAS, pairing links and short codes, the JSON-RPC codec |
| `core/security` | **built** | The `DeviceKeyStore` interface and P-256 device keys. The Android Keystore version comes with the Android app. |
| `core/security-desktop` | **built** | Windows: key file encrypted with DPAPI. Linux: Secret Service keyring via `secret-tool`, or a file only you can read when there's no keyring (the app shows a warning). |
| `core/session` | **built** | The connection: pairing with the SAS, the signed handshake, answering and sending pings, `status.get`, reconnecting after 1, 2, 4 … 60 s, the connection log, saved pairing, and the Network row check |
| `ui` | **built** | Compose Multiplatform screens: Connect (link, or short code plus address), Confirm code (digits, emoji, countdown), and Connection status (Network, Bridge and agent rows, test button, connection log). `TalariaController` runs pairing and the session behind them. |
| `desktopApp` | **built** | The Windows and Linux app: the screens in a window, a tray icon whose dot shows the state, and start at login |
| `androidApp` | later in M1 | QR scan, foreground service, battery onboarding |

`core/protocol` has one JVM target, which both apps use: Android provides the same `java.security` APIs (SHA-256, ECDSA P-256). Signing goes through the `Signer` interface, so the device key can stay in the platform key store.

Desktop keys are kept in `%APPDATA%\Talaria\device-key.dpapi` on Windows, and in the keyring or `~/.config/talaria/device-key.json` on Linux. On Linux the keyring needs `secret-tool` (package `libsecret-tools`).

## Desktop app

Run it from Gradle:

```
cd client
.\gradlew.bat :desktopApp:run        # Windows
./gradlew :desktopApp:run            # Linux
```

Or build the app folder, which carries its own Java runtime: `gradlew :desktopApp:createDistributable`, then start `desktopApp/build/compose/binaries/main/app/Talaria/Talaria.exe` (Windows) or `.../Talaria/bin/Talaria` (Linux). CI uploads that folder for both systems as the `talaria-desktop-Windows` and `talaria-desktop-Linux` artifacts. MSI and DEB installers come later.

- **Pairing:** run `talaria pair` on the server, then paste the link it prints, or type the short code and the server's address (a host name like `my-server.tailnet.ts.net`, or a full `wss://` address). Compare the code on screen with the terminal before you type `y` there.
- **Tray:** closing the window keeps the connection open in the tray. The dot on the icon is green when connected, amber while connecting or when an agent is degraded, red when the connection is down, and grey before pairing. Quit from the tray menu. On a Linux desktop without a tray, closing the window quits.
- **Start at login** is off until you turn it on in the app. It writes `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\Talaria` on Windows and `~/.config/autostart/talaria.desktop` on Linux, and starts the app in the tray. It needs the packaged app, because a Gradle run has no fixed path to start.
- **Files:** the device key, `bridge.json` (the paired server) and `connection.log` are in `%APPDATA%\Talaria` on Windows and `~/.config/talaria` on Linux.
- **Forget this server** deletes the pairing and the device key, so the next pairing uses a fresh key. Revoke the old device on the server with `talaria devices revoke <id>`.

Only one copy runs at a time; a second one says so and exits.

## Tests

`core/session` also runs integration tests against the real Python bridge: pairing by link and by code, status reports and pushes, revocation, a changed bridge identity, an unreachable bridge, and a bridge from before M1. They start `bridge_harness.py` with Python. Without Python and the bridge installed they're skipped, and with `TALARIA_PYTHON` set (as in CI) they must run.

The tests run every file in [`../spec/vectors/`](../spec/vectors) plus `spec/sas-emoji.json`, the same files the Python bridge is tested against, so the two sides agree byte for byte. Gradle copies them into the test resources.

Windows (PowerShell), using Android Studio's JDK:

```
cd client
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat check
```

Linux or macOS: `cd client && ./gradlew check`. Any JDK from 17 up works. The first run downloads Gradle and the Kotlin compiler.
