# Screencast

Free, open-source Android screen mirroring to TVs and projectors. No ads, no subscriptions.

[![Latest Release](https://img.shields.io/github/v/release/Bentlybro/screencast)](https://github.com/Bentlybro/screencast/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## Why?

Existing apps charge subscriptions for functionality that should be free. The underlying protocols (DLNA, Miracast, Chromecast) are open — there's no reason to paywall basic screen sharing.

## Features

- **Multi-protocol support** — DLNA, Miracast, and Chromecast all work out of the box
- **Zero configuration** — tap a device, start casting
- **Quality controls** — adjust resolution and bitrate to match your network
- **OTA updates** — automatic update checks via GitHub Releases
- **No bullshit** — no ads, no tracking, no analytics, no subscriptions

## Supported Protocols

| Protocol | Compatibility | Notes |
|----------|---------------|-------|
| **DLNA/UPnP** | Most smart TVs | Widest compatibility |
| **Miracast** | WiFi Direct devices | No router needed |
| **Chromecast** | Chromecast, Android TV | Reverse-engineered Cast v2 |

## Download

Get the latest APK from [GitHub Releases](https://github.com/Bentlybro/screencast/releases).

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **DI:** Hilt
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)

## Architecture

```
app/src/main/java/com/screencast/
├── ui/
│   ├── screens/          # Home, Casting, Settings
│   └── theme/            # Material 3 theming
├── casting/
│   ├── CastManager       # Unified casting interface
│   ├── dlna/             # DLNA/UPnP controller
│   ├── miracast/         # Miracast RTSP/RTP streaming
│   └── chromecast/       # Cast v2 protocol
├── capture/
│   ├── ScreenCapture     # MediaProjection wrapper
│   └── VideoEncoder      # H.264 encoding via MediaCodec
├── discovery/
│   ├── CombinedDiscovery # Unified device discovery
│   ├── SSDPDiscovery     # DLNA discovery (UDP 1900)
│   ├── MiracastDiscovery # WiFi P2P discovery
│   └── ChromecastDiscovery # mDNS discovery
├── streaming/
│   └── StreamingServer   # HTTP server for DLNA
├── update/
│   └── UpdateManager     # GitHub-based OTA updates
└── di/                   # Hilt modules
```

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

## Building

```bash
git clone https://github.com/Bentlybro/screencast.git
cd screencast
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and run directly.

## Contributing

PRs welcome. Keep it simple — this is meant to be a lean, focused app.

## License

MIT — do whatever you want with it.

---

Built for Colton, who just wanted to share his screen without paying a subscription.
