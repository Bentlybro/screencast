# Screencast

Free, open-source Android screen mirroring to TVs and projectors. No ads, no subscriptions.

## Why?

Existing apps charge subscriptions for functionality that should be free. The underlying protocols (DLNA, Miracast, Chromecast) are open — there's no reason to paywall basic screen sharing.

## Features

**Core:**
- Mirror Android screen to smart TVs and projectors
- Support multiple casting protocols for maximum compatibility
- Clean, simple UI — tap a device, start casting
- Audio passthrough where supported

**Protocols (priority order):**
1. **DLNA/UPnP** — Widest compatibility, most smart TVs support this
2. **Miracast** — WiFi Direct, no router needed
3. **Chromecast** — Google Cast SDK for Chromecast/Android TV devices
4. **Roku** — External Control Protocol (if time permits)

**Technical:**
- OTA updates built-in from day one
- Minimal permissions — only what's needed
- No analytics, no tracking, no bullshit

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Min SDK:** 24 (Android 7.0) — covers 95%+ of devices
- **Target SDK:** 34 (Android 14)
- **Build:** Gradle with Kotlin DSL

## Architecture

```
app/
├── ui/                    # Compose UI
│   ├── screens/
│   │   ├── HomeScreen     # Device discovery + list
│   │   ├── CastingScreen  # Active casting view
│   │   └── SettingsScreen
│   └── components/
├── casting/               # Casting implementations
│   ├── CastManager        # Unified interface
│   ├── dlna/              # DLNA/UPnP implementation
│   ├── miracast/          # Miracast/WiFi Direct
│   └── chromecast/        # Google Cast SDK
├── capture/               # Screen capture
│   ├── ScreenCapture      # MediaProjection wrapper
│   └── Encoder            # H.264 encoding
├── discovery/             # Device discovery
│   ├── DeviceDiscovery    # Unified discovery
│   ├── SSDPDiscovery      # DLNA device discovery
│   └── WifiP2pDiscovery   # Miracast discovery
├── update/                # OTA updates
│   └── UpdateManager      # Check + install updates
└── di/                    # Dependency injection (Hilt)
```

## Key Android APIs

| Feature | API |
|---------|-----|
| Screen capture | `MediaProjection` |
| Video encoding | `MediaCodec` (H.264) |
| DLNA discovery | SSDP multicast (UDP 1900) |
| Miracast | `WifiP2pManager` |
| Chromecast | Google Cast SDK |

## Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

## OTA Update Strategy

Using GitHub Releases as the update source:
1. App checks `https://api.github.com/repos/OWNER/screencast/releases/latest` on launch
2. Compares version code with installed version
3. If newer, prompts user to update
4. Downloads APK and triggers install via `ACTION_INSTALL_PACKAGE`

Simple, no backend needed, fully transparent.

## Roadmap

### Phase 1: MVP (DLNA)
- [ ] Project setup (Gradle, Compose, Hilt)
- [ ] Basic UI: device list + casting screen
- [ ] SSDP device discovery
- [ ] MediaProjection screen capture
- [ ] H.264 encoding with MediaCodec
- [ ] DLNA streaming (HTTP server + UPnP control)
- [ ] OTA update system
- [ ] First release

### Phase 2: Miracast
- [ ] WiFi P2P discovery
- [ ] Miracast session establishment
- [ ] HDCP handling (if needed)

### Phase 3: Chromecast
- [ ] Google Cast SDK integration
- [ ] Chromecast device discovery
- [ ] Cast session management

### Phase 4: Polish
- [ ] Quality settings (resolution, bitrate)
- [ ] Latency optimization
- [ ] Battery optimization
- [ ] Roku support (stretch goal)

## Development

```bash
# Clone
git clone https://github.com/Bentlybro/screencast.git
cd screencast

# Open in Android Studio
# Build → Run on device

# Or command line
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT — do whatever you want with it.

---

Built for Colton, who just wanted to share his screen without paying a subscription.
