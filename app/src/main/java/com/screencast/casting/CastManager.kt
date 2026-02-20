package com.screencast.casting

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.screencast.capture.ScreenCapture
import com.screencast.casting.dlna.DLNAController
import com.screencast.model.Device
import com.screencast.model.DeviceType
import com.screencast.streaming.StreamingServer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val dlnaController: DLNAController
) {
    companion object {
        private const val TAG = "CastManager"
        private const val STREAM_PORT = 8080
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var screenCapture: ScreenCapture? = null
    private var streamingServer: StreamingServer? = null
    private var targetDevice: Device? = null
    
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
            com.screencast.capture.ScreenCaptureService.startService(context, device.name)
            
            // Get MediaProjection
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection == null) {
                _castState.value = CastState.Error("Failed to get screen capture permission")
                com.screencast.capture.ScreenCaptureService.stopService(context)
                return false
            }

            // Start streaming server
            streamingServer = StreamingServer(STREAM_PORT).apply {
                start()
            }
            val streamUrl = streamingServer!!.getStreamUrl()
            Log.d(TAG, "Stream URL: $streamUrl")

            // Start screen capture
            screenCapture = ScreenCapture(context, mediaProjection).apply {
                configure()
                start()
            }

            // Connect encoded frames to streaming server
            streamingServer!!.setFrameSource(screenCapture!!.encodedFrames)

            // Tell the device to play our stream
            val connected = when (device.type) {
                DeviceType.DLNA -> connectDLNA(device, streamUrl)
                DeviceType.MIRACAST -> connectMiracast(device)
                DeviceType.CHROMECAST -> connectChromecast(device, streamUrl)
                else -> {
                    Log.e(TAG, "Unsupported device type: ${device.type}")
                    false
                }
            }

            if (connected) {
                _castState.value = CastState.Casting(device, streamUrl)
                
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
                    else -> { /* TODO: Handle other types */ }
                }
            }
        }

        // Clean up resources
        screenCapture?.release()
        screenCapture = null
        
        streamingServer?.stop()
        streamingServer = null
        
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
        // TODO: Implement Miracast connection
        // This requires WiFi P2P and is more complex
        Log.w(TAG, "Miracast not yet implemented")
        return false
    }

    private suspend fun connectChromecast(device: Device, streamUrl: String): Boolean {
        // TODO: Implement Chromecast connection
        // This requires Google Cast SDK
        Log.w(TAG, "Chromecast not yet implemented")
        return false
    }

    private fun startConnectionMonitor(device: Device) {
        scope.launch {
            while (_castState.value is CastState.Casting) {
                delay(5000) // Check every 5 seconds
                
                when (device.type) {
                    DeviceType.DLNA -> {
                        val info = dlnaController.getTransportInfo(device)
                        if (info == null || info.state == com.screencast.casting.dlna.TransportState.STOPPED) {
                            Log.d(TAG, "Device stopped playback")
                            // Don't auto-stop, device might just be buffering
                        }
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
