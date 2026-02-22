package com.screencast.casting

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.screencast.capture.ScreenCapture
import com.screencast.casting.chromecast.ChromecastController
import com.screencast.casting.dlna.DLNAController
import com.screencast.casting.miracast.MiracastSource
import com.screencast.data.SettingsRepository
import com.screencast.discovery.CombinedDiscovery
import com.screencast.model.Device
import com.screencast.model.DeviceType
import com.screencast.streaming.StreamingServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the entire casting session.
 * 
 * Coordinates screen capture, video encoding, HTTP streaming, and device control.
 */
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dlnaController: DLNAController,
    private val chromecastController: ChromecastController,
    private val combinedDiscovery: CombinedDiscovery,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "CastManager"
        private const val STREAM_PORT = 8080
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var screenCapture: ScreenCapture? = null
    private var streamingServer: StreamingServer? = null
    private var miracastSource: MiracastSource? = null
    private var targetDevice: Device? = null
    private var mediaProjection: MediaProjection? = null
    
    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    /**
     * Start casting to a device.
     * 
     * @param device Target device to cast to
     * @param resultCode MediaProjection result code from permission request
     * @param resultData MediaProjection result data from permission request
     */
    suspend fun startCasting(
        device: Device,
        resultCode: Int,
        resultData: Intent
    ): Boolean {
        if (_castState.value is CastState.Casting) {
            Log.w(TAG, "Already casting")
            return false
        }

        _castState.value = CastState.Connecting(device)
        targetDevice = device

        return try {
            // Start foreground service for notification
            // IMPORTANT: On Android 14+, the foreground service MUST be started and
            // startForeground() MUST complete BEFORE getMediaProjection() is called.
            com.screencast.capture.ScreenCaptureService.startService(context, device.name)
            
            // Wait for service to start (required on Android 14+)
            // The service needs time to call startForeground() before we can get MediaProjection
            delay(500)
            
            // Get MediaProjection
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection == null) {
                _castState.value = CastState.Error("Failed to get screen capture permission")
                com.screencast.capture.ScreenCaptureService.stopService(context)
                return false
            }

            // Get quality settings
            val quality = settingsRepository.quality.first()

            // Start streaming server (needed for DLNA and Chromecast)
            if (device.type == DeviceType.DLNA || device.type == DeviceType.CHROMECAST) {
                streamingServer = StreamingServer(STREAM_PORT).apply {
                    start()
                }
            }
            val streamUrl = streamingServer?.getStreamUrl()
            Log.d(TAG, "Stream URL: $streamUrl")

            // Start screen capture with quality settings
            screenCapture = ScreenCapture(context, mediaProjection!!).apply {
                configure(
                    width = quality.width,
                    height = quality.height,
                    bitrate = quality.bitrate,
                    fps = 30
                )
                start()
            }

            // Connect encoded frames to streaming server
            if (streamingServer != null) {
                streamingServer!!.setFrameSource(screenCapture!!.encodedFrames)
            }

            // Tell the device to play our stream
            val connected = when (device.type) {
                DeviceType.DLNA -> connectDLNA(device, streamUrl!!)
                DeviceType.MIRACAST -> connectMiracast(device)
                DeviceType.CHROMECAST -> connectChromecast(device, streamUrl)
                else -> {
                    Log.e(TAG, "Unsupported device type: ${device.type}")
                    false
                }
            }

            if (connected) {
                _castState.value = CastState.Casting(device, streamUrl ?: "miracast://${device.address}")
                
                // Start monitoring connection
                startConnectionMonitor(device)
                true
            } else {
                stopCasting()
                _castState.value = CastState.Error("Failed to connect to device")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting cast", e)
            stopCasting()
            _castState.value = CastState.Error(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Stop the current casting session.
     */
    fun stopCasting() {
        scope.launch {
            // Stop device playback
            targetDevice?.let { device ->
                when (device.type) {
                    DeviceType.DLNA -> dlnaController.stop(device)
                    DeviceType.MIRACAST -> {
                        miracastSource?.stop()
                        miracastSource = null
                        combinedDiscovery.disconnectMiracast { }
                    }
                    DeviceType.CHROMECAST -> {
                        chromecastController.stopCasting()
                        chromecastController.disconnect()
                    }
                    else -> { /* Unsupported */ }
                }
            }
        }

        // Clean up resources
        screenCapture?.release()
        screenCapture = null
        
        streamingServer?.stop()
        streamingServer = null
        
        mediaProjection = null
        
        // Stop foreground service
        com.screencast.capture.ScreenCaptureService.stopService(context)
        
        targetDevice = null
        _castState.value = CastState.Idle
        
        Log.d(TAG, "Casting stopped")
    }

    private suspend fun connectDLNA(device: Device, streamUrl: String): Boolean {
        // Set the stream URI on the device
        if (!dlnaController.setAVTransportURI(device, streamUrl)) {
            Log.e(TAG, "Failed to set AVTransport URI")
            return false
        }

        // Small delay for device to process
        delay(500)

        // Start playback
        if (!dlnaController.play(device)) {
            Log.e(TAG, "Failed to start playback")
            return false
        }

        Log.d(TAG, "DLNA connection established")
        return true
    }

    private suspend fun connectMiracast(device: Device): Boolean {
        // Step 1: Establish WiFi Direct P2P connection
        val p2pConnected = suspendCancellableCoroutine { continuation ->
            combinedDiscovery.connectMiracast(device) { success ->
                Log.d(TAG, "P2P connection result: $success")
                continuation.resume(success) {}
            }
        }
        
        if (!p2pConnected) {
            Log.e(TAG, "Failed to establish P2P connection")
            return false
        }
        
        // Wait for P2P connection to stabilize
        delay(2000)
        
        // Step 2: Start Miracast source (RTSP server + RTP streaming)
        miracastSource = MiracastSource(device.address)
        if (!miracastSource!!.start()) {
            Log.e(TAG, "Failed to start Miracast source")
            return false
        }
        
        Log.d(TAG, "Miracast RTSP server started, waiting for sink to connect...")
        
        // Step 3: Stream video frames to the sink
        scope.launch {
            try {
                screenCapture?.let { capture ->
                    miracastSource?.streamFrames(capture.encodedFrames)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Miracast streaming error", e)
            }
        }
        
        Log.d(TAG, "Miracast connection established")
        return true
    }

    private suspend fun connectChromecast(device: Device, streamUrl: String?): Boolean {
        if (streamUrl == null) {
            Log.e(TAG, "Stream URL required for Chromecast")
            return false
        }
        
        // Connect to Chromecast
        if (!chromecastController.connect(device.address)) {
            Log.e(TAG, "Failed to connect to Chromecast")
            return false
        }
        
        // Start heartbeat to keep connection alive
        scope.launch {
            while (_castState.value is CastState.Casting || _castState.value is CastState.Connecting) {
                delay(3000)
                chromecastController.sendHeartbeat()
            }
        }
        
        // Start casting
        val success = chromecastController.startCasting(streamUrl)
        if (success) {
            Log.d(TAG, "Chromecast connection established")
        }
        return success
    }

    private fun startConnectionMonitor(device: Device) {
        scope.launch {
            while (_castState.value is CastState.Casting) {
                delay(5000) // Check every 5 seconds
                
                when (device.type) {
                    DeviceType.DLNA -> {
                        val info = dlnaController.getTransportInfo(device)
                        if (info == null) {
                            Log.w(TAG, "Lost connection to device")
                        }
                    }
                    DeviceType.MIRACAST -> {
                        // Miracast connection is managed by the system
                        // We'd need to monitor WifiP2pManager connection state
                    }
                    else -> { /* TODO */ }
                }
            }
        }
    }
}

/**
 * Represents the current casting state.
 */
sealed class CastState {
    data object Idle : CastState()
    data class Connecting(val device: Device) : CastState()
    data class Casting(val device: Device, val streamUrl: String) : CastState()
    data class Error(val message: String) : CastState()
}
