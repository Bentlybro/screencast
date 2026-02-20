package com.screencast.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.flow.Flow

/**
 * Captures the device screen using MediaProjection and encodes it to H.264.
 */
class ScreenCapture(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val VIRTUAL_DISPLAY_NAME = "ScreencastCapture"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: VideoEncoder? = null
    private var isCapturing = false

    // Capture dimensions (can be scaled down for performance)
    private var captureWidth = 1920
    private var captureHeight = 1080
    private var captureDpi = 320

    /**
     * Flow of encoded video frames.
     */
    val encodedFrames: Flow<EncodedFrame>
        get() = encoder?.encodedFrames ?: throw IllegalStateException("Capture not started")

    /**
     * Configure capture settings.
     * Call before start().
     */
    fun configure(
        width: Int = 0,
        height: Int = 0,
        bitrate: Int = 4_000_000,
        fps: Int = 30
    ) {
        // Get screen metrics if not specified
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // Use provided dimensions or scale to 1080p max
        if (width > 0 && height > 0) {
            captureWidth = width
            captureHeight = height
        } else {
            // Scale down if needed, maintaining aspect ratio
            val maxDimension = 1920
            if (metrics.widthPixels > maxDimension || metrics.heightPixels > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels)
                captureWidth = (metrics.widthPixels * scale).toInt()
                captureHeight = (metrics.heightPixels * scale).toInt()
            } else {
                captureWidth = metrics.widthPixels
                captureHeight = metrics.heightPixels
            }
        }
        
        // Ensure dimensions are even (required by encoder)
        captureWidth = captureWidth and 0xFFFE.inv().inv()
        captureHeight = captureHeight and 0xFFFE.inv().inv()
        captureDpi = metrics.densityDpi

        // Create encoder
        encoder = VideoEncoder(captureWidth, captureHeight, bitrate, fps).apply {
            configure()
        }

        Log.d(TAG, "Capture configured: ${captureWidth}x${captureHeight} @ $captureDpi dpi")
    }

    /**
     * Start capturing the screen.
     */
    fun start() {
        if (isCapturing) return
        
        val enc = encoder ?: throw IllegalStateException("Call configure() first")
        val surface = enc.inputSurface ?: throw IllegalStateException("Encoder surface not ready")

        // Register projection callback
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stop()
            }
        }, null)

        // Create virtual display
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            captureWidth,
            captureHeight,
            captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() {
                    Log.d(TAG, "VirtualDisplay paused")
                }

                override fun onResumed() {
                    Log.d(TAG, "VirtualDisplay resumed")
                }

                override fun onStopped() {
                    Log.d(TAG, "VirtualDisplay stopped")
                }
            },
            null
        )

        // Start encoding
        enc.start()
        isCapturing = true
        
        Log.d(TAG, "Screen capture started")
    }

    /**
     * Request a keyframe (useful when new client connects).
     */
    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    /**
     * Stop capturing.
     */
    fun stop() {
        if (!isCapturing) return
        isCapturing = false

        virtualDisplay?.release()
        virtualDisplay = null
        
        encoder?.stop()
        
        Log.d(TAG, "Screen capture stopped")
    }

    /**
     * Release all resources.
     */
    fun release() {
        stop()
        encoder?.release()
        encoder = null
        mediaProjection.stop()
    }

    /**
     * Check if currently capturing.
     */
    fun isCapturing(): Boolean = isCapturing
}
