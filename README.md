# GlassRemote

Android TV / Google TV remote application built with Kotlin, Jetpack Compose, Hilt, coroutines, protobuf, and ADB-over-Wi-Fi support.

## Build

Requirements: Android Studio with Android SDK Platform 35, JDK 17, and Gradle 8.9 (or use Android Studio's Gradle sync).

```bash
gradle --no-daemon assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow at `.github/workflows/android.yml` builds the debug APK for pushes, pull requests, and manual runs. Download the APK from the workflow run's **Artifacts** section.

## Default local targets

- Living Room TV (Xiaomi): `192.168.31.116`, Remote Protocol v2 port `6466`; ADB port `5555` where enabled and authorized.
- Jio Box: `192.168.31.24`, Remote Protocol v2 port `6466`.

These are example defaults based on one local network. DHCP can change addresses; edit `KnownTargets` or use discovery for your environment.

## Protocol notes

Android TV Remote Protocol v2 uses separate channels: the remote-control channel is typically TCP `6466`; pairing uses TCP `6467`. The app's pairing client negotiates the protocol's six-character hexadecimal pairing code. Do not assume that connecting to `6466` itself triggers the pairing prompt, or that a four-digit numeric PIN is supported by the standard protocol.

Bluetooth HID device registration is subject to Android OS/device support and user permission. The app cannot bypass OS rejection of the HID Device role.

## Security

The client identity is stored in app-private files. The remote certificate fingerprint is pinned per host after successful pairing; a changed certificate then requires re-pairing. Keep the app updated and pair only with devices you trust on your local network.

## Status

The repository includes a CI build workflow. A successful workflow run is the source of truth for whether the project compiles and produces an APK; the build environment used to prepare this archive did not have Android SDK components or network access to download Gradle dependencies.
