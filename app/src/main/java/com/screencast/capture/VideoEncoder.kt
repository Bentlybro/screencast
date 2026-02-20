package com.screencast.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.ByteBuffer

/**
 * Encodes screen frames to H.264 video using MediaCodec.
 * 
 * Uses Surface input for efficient frame capture from VirtualDisplay.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 4_000_000, // 4 Mbps default
    private val fps: Int = 30
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC // H.264
        private const val I_FRAME_INTERVAL = 2 // Keyframe every 2 seconds
        private const val TIMEOUT_US = 10_000L // 10ms timeout for dequeue
    }

    private var codec: MediaCodec? = null
    private var isRunning = false
    private val outputChannel = Channel<EncodedFrame>(Channel.BUFFERED)

    /**
     * The input surface to feed frames into.
     * Connect this to VirtualDisplay.
     */
    var inputSurface: Surface? = null
        private set

    /**
     * Flow of encoded video frames.
     */
    val encodedFrames: Flow<EncodedFrame> = outputChannel.receiveAsFlow()

    /**
     * Configure and prepare the encoder.
     */
    fun configure() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            
            // Use Baseline profile for maximum compatibility
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }

        Log.d(TAG, "Encoder configured: ${width}x${height} @ ${bitrate/1_000_000}Mbps, $fps fps")
    }

    /**
     * Start encoding. Call after configure().
     */
    fun start() {
        codec?.let { mediaCodec ->
            mediaCodec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Surface input mode - no input buffers used
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    if (!isRunning) return
                    
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        
                        if (info.size > 0) {
                            // Copy the data
                            val data = ByteArray(info.size)
                            buffer.get(data)
                            
                            val frame = EncodedFrame(
                                data = data,
                                presentationTimeUs = info.presentationTimeUs,
                                isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            )
                            
                            outputChannel.trySend(frame)
                        }
                        
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing output buffer", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Encoder error", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.d(TAG, "Output format changed: $format")
                }
            })
            
            mediaCodec.start()
            isRunning = true
            Log.d(TAG, "Encoder started")
        }
    }

    /**
     * Request a keyframe to be generated.
     * Useful when a new client connects.
     */
    fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    /**
     * Stop encoding and release resources.
     */
    fun stop() {
        isRunning = false
        try {
            codec?.signalEndOfInputStream()
            codec?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }

    /**
     * Release the encoder.
     */
    fun release() {
        stop()
        try {
            inputSurface?.release()
            inputSurface = null
            codec?.release()
            codec = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
        outputChannel.close()
    }
}

/**
 * Represents an encoded video frame.
 */
data class EncodedFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    val isConfig: Boolean // SPS/PPS data
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncodedFrame
        return presentationTimeUs == other.presentationTimeUs
    }

    override fun hashCode(): Int = presentationTimeUs.hashCode()
}
