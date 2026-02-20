# Screencast — Technical Plan

## Phase 1 Breakdown: DLNA MVP

### 1. Project Scaffolding
- Android project with Kotlin DSL
- Jetpack Compose setup
- Hilt for DI
- Version catalog for dependencies
- GitHub Actions for CI/release builds

### 2. Screen Capture Module
```kotlin
// Core flow:
// 1. Request MediaProjection permission
// 2. Create VirtualDisplay
// 3. Feed frames to MediaCodec encoder
// 4. Output H.264 NAL units

class ScreenCapture(
    private val mediaProjection: MediaProjection,
    private val encoder: VideoEncoder
) {
    fun start(width: Int, height: Int, dpi: Int)
    fun stop()
}

class VideoEncoder {
    // MediaCodec in async mode
    // H.264 Baseline Profile for compatibility
    // Target: 1080p @ 30fps, 4-8 Mbps
    fun configure(width: Int, height: Int, bitrate: Int, fps: Int)
    fun start()
    fun getOutputBuffer(): ByteBuffer?
}
```

### 3. DLNA Discovery (SSDP)
```kotlin
// SSDP M-SEARCH to discover MediaRenderer devices
// Multicast: 239.255.255.250:1900

class SSDPDiscovery {
    fun discover(): Flow<DLNADevice>
    
    // M-SEARCH request:
    // M-SEARCH * HTTP/1.1
    // HOST: 239.255.255.250:1900
    // MAN: "ssdp:discover"
    // MX: 3
    // ST: urn:schemas-upnp-org:device:MediaRenderer:1
}

data class DLNADevice(
    val friendlyName: String,
    val location: String,  // URL to device description
    val modelName: String,
    val controlUrl: String // AVTransport control endpoint
)
```

### 4. DLNA Streaming
```kotlin
// Two components:
// 1. HTTP server serving the video stream
// 2. UPnP AVTransport control

class DLNAStreamer {
    private val httpServer: NanoHTTPD  // Embedded HTTP server
    
    fun startServer(port: Int): String  // Returns stream URL
    fun streamFrame(frame: ByteBuffer)
}

class AVTransportControl {
    // SOAP actions to control playback
    fun setAVTransportURI(device: DLNADevice, uri: String)
    fun play(device: DLNADevice)
    fun stop(device: DLNADevice)
}
```

### 5. UI Components
```kotlin
// Compose screens

@Composable
fun HomeScreen(
    devices: List<Device>,
    onDeviceSelected: (Device) -> Unit,
    onRefresh: () -> Unit
)

@Composable  
fun CastingScreen(
    device: Device,
    isConnected: Boolean,
    onStop: () -> Unit
)

@Composable
fun SettingsScreen(
    quality: Quality,
    onQualityChange: (Quality) -> Unit,
    appVersion: String,
    onCheckUpdate: () -> Unit
)
```

### 6. OTA Updates
```kotlin
class UpdateManager(
    private val context: Context,
    private val currentVersion: Int
) {
    suspend fun checkForUpdate(): UpdateInfo?
    suspend fun downloadUpdate(url: String, onProgress: (Float) -> Unit): File
    fun installUpdate(apkFile: File)
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)
```

## Dependencies

```kotlin
// Version catalog (libs.versions.toml)
[versions]
kotlin = "1.9.22"
compose-bom = "2024.02.00"
hilt = "2.50"
coroutines = "1.7.3"
nanohttpd = "2.3.1"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
nanohttpd = { module = "org.nanohttpd:nanohttpd", version.ref = "nanohttpd" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
```

## Testing Approach

1. **Unit tests:** Discovery parsing, SOAP message building
2. **Integration tests:** Full DLNA flow with mock server
3. **Manual testing:** Real devices (need a few different TVs)

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| DLNA implementation complexity | Use existing UPnP library as reference, test incrementally |
| Video encoding latency | Use hardware encoder (MediaCodec), tune settings |
| TV compatibility issues | Test on multiple brands, graceful fallbacks |
| Battery drain | Foreground service with proper notification, auto-stop on disconnect |

## Files to Create First

1. `settings.gradle.kts` — Project settings
2. `build.gradle.kts` (root) — Root build config
3. `app/build.gradle.kts` — App module config
4. `gradle/libs.versions.toml` — Dependency versions
5. `app/src/main/AndroidManifest.xml` — Permissions, services
6. Core Compose setup files

Ready to start coding when you are.
