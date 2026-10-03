# Talaria clients

Kotlin Multiplatform. The Android app and the Windows/Linux desktop app share everything below the UI; iOS comes after v1. M1 builds these modules:

| Module | Status | What it is |
|---|---|---|
| `core/protocol` | **built** | TNP in Kotlin: `frame()`, base64url, key ids, P-256 signature checks, the signed-data builders, SAS, pairing links and short codes, the JSON-RPC codec |
| `core/security` | **built** | The `DeviceKeyStore` interface and P-256 device keys. The Android Keystore version comes with the Android app. |
| `core/security-desktop` | **built** | Windows: key file encrypted with DPAPI. Linux: Secret Service keyring via `secret-tool`, or a file only you can read when there's no keyring (the app shows a warning). |
| `ui`, `desktopApp`, `androidApp` | later in M1 | Connect, confirm code and connection status screens |

`core/protocol` has one JVM target, which both apps use: Android provides the same `java.security` APIs (SHA-256, ECDSA P-256). Signing goes through the `Signer` interface, so the device key can stay in the platform key store.

Desktop keys are kept in `%APPDATA%\Talaria\device-key.dpapi` on Windows, and in the keyring or `~/.config/talaria/device-key.json` on Linux. On Linux the keyring needs `secret-tool` (package `libsecret-tools`).

## Tests

The tests run every file in [`../spec/vectors/`](../spec/vectors) plus `spec/sas-emoji.json`, the same files the Python bridge is tested against, so the two sides agree byte for byte. Gradle copies them into the test resources.

Windows (PowerShell), using Android Studio's JDK:

```
cd client
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat check
```

Linux or macOS: `cd client && ./gradlew check`. Any JDK from 17 up works. The first run downloads Gradle and the Kotlin compiler.
