package com.screencast.casting.miracast

import android.media.projection.MediaProjection
import android.util.Log
import com.screencast.capture.EncodedFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Miracast source implementation.
 * 
 * Miracast uses:
 * - RTSP for session control (port 7236 by default)
 * - RTP over UDP for video streaming
 * - MPEG2-TS container with H.264 video
 * 
 * This implements the WFD (WiFi Display) source side protocol.
 */
class MiracastSource(
    private val sinkAddress: String
) {
    companion object {
        private const val TAG = "MiracastSource"
        private const val RTSP_PORT = 7236
        private const val RTP_PORT_BASE = 15550
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rtspServer: ServerSocket? = null
    private var rtpSocket: DatagramSocket? = null
    private var rtspClient: Socket? = null
    
    private val isRunning = AtomicBoolean(false)
    private val sequenceNumber = AtomicInteger(0)
    private val timestamp = AtomicInteger(0)
    private val ssrc = (System.currentTimeMillis() and 0xFFFFFFFF).toInt()
    
    private var sinkRtpPort: Int = RTP_PORT_BASE
    private var sinkAddress: InetAddress? = null

    /**
     * Start the Miracast source.
     * This starts the RTSP server and waits for the sink to connect.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        try {
            isRunning.set(true)
            
            // Start RTSP server
            rtspServer = ServerSocket(RTSP_PORT)
            Log.d(TAG, "RTSP server started on port $RTSP_PORT")
            
            // Start RTP socket
            rtpSocket = DatagramSocket()
            Log.d(TAG, "RTP socket ready on port ${rtpSocket?.localPort}")
            
            // Wait for sink to connect (with timeout)
            scope.launch {
                acceptRtspConnection()
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Miracast source", e)
            stop()
            false
        }
    }

    private suspend fun acceptRtspConnection() {
        try {
            rtspServer?.soTimeout = 30000 // 30 second timeout
            rtspClient = rtspServer?.accept()
            Log.d(TAG, "Sink connected from ${rtspClient?.inetAddress}")
            
            sinkAddress = rtspClient?.inetAddress
            
            // Handle RTSP session
            handleRtspSession(rtspClient!!)
        } catch (e: Exception) {
            Log.e(TAG, "RTSP connection error", e)
        }
    }

    private suspend fun handleRtspSession(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)
            
            while (isRunning.get() && !client.isClosed) {
                val request = readRtspRequest(reader) ?: break
                Log.d(TAG, "RTSP Request: ${request.method} ${request.uri}")
                
                val response = handleRtspRequest(request)
                writer.print(response)
                writer.flush()
                Log.d(TAG, "RTSP Response sent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP session error", e)
        }
    }

    private fun readRtspRequest(reader: BufferedReader): RtspRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null
        
        val headers = mutableMapOf<String, String>()
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            val colonIndex = line!!.indexOf(':')
            if (colonIndex > 0) {
                val key = line!!.substring(0, colonIndex).trim()
                val value = line!!.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        
        return RtspRequest(
            method = parts[0],
            uri = parts[1],
            version = parts[2],
            headers = headers
        )
    }

    private fun handleRtspRequest(request: RtspRequest): String {
        val cseq = request.headers["CSeq"] ?: "0"
        
        return when (request.method) {
            "OPTIONS" -> buildOptionsResponse(cseq)
            "GET_PARAMETER" -> buildGetParameterResponse(cseq, request)
            "SET_PARAMETER" -> buildSetParameterResponse(cseq, request)
            "SETUP" -> buildSetupResponse(cseq, request)
            "PLAY" -> buildPlayResponse(cseq)
            "PAUSE" -> buildPauseResponse(cseq)
            "TEARDOWN" -> buildTeardownResponse(cseq)
            else -> buildErrorResponse(cseq, 501, "Not Implemented")
        }
    }

    private fun buildOptionsResponse(cseq: String): String {
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            Public: OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    private fun buildGetParameterResponse(cseq: String, request: RtspRequest): String {
        // WFD capability exchange
        // This is where we advertise our capabilities
        val wfdVideoFormats = "00 00 03 10 0001FFFF 1FFFFFFF 00000FFF 00 0000 0000 00 none none"
        val wfdAudioCodecs = "LPCM 00000003 00"
        val wfdClientRtpPorts = "RTP/AVP/UDP;unicast ${rtpSocket?.localPort ?: RTP_PORT_BASE} 0 mode=play"
        
        val body = """
            wfd_video_formats: $wfdVideoFormats
            wfd_audio_codecs: $wfdAudioCodecs
            wfd_client_rtp_ports: $wfdClientRtpPorts
        """.trimIndent()
        
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            Content-Type: text/parameters
            Content-Length: ${body.length + 2}
            
            $body
            
        """.trimIndent().replace("\n", "\r\n")
    }

    private fun buildSetParameterResponse(cseq: String, request: RtspRequest): String {
        // Parse sink's capabilities from the request body
        // Extract RTP port if provided
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    private fun buildSetupResponse(cseq: String, request: RtspRequest): String {
        // Extract transport info from request
        val transport = request.headers["Transport"] ?: "RTP/AVP/UDP;unicast"
        
        // Parse client port from transport header
        val clientPortMatch = Regex("client_port=(\\d+)").find(transport)
        sinkRtpPort = clientPortMatch?.groupValues?.get(1)?.toIntOrNull() ?: RTP_PORT_BASE
        
        Log.d(TAG, "SETUP: Sink RTP port = $sinkRtpPort")
        
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            Session: 12345678;timeout=30
            Transport: $transport;server_port=${rtpSocket?.localPort}
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    private fun buildPlayResponse(cseq: String): String {
        Log.d(TAG, "PLAY: Starting video stream")
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            Session: 12345678
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    private fun buildPauseResponse(cseq: String): String {
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            Session: 12345678
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    private fun buildTeardownResponse(cseq: String): String {
        scope.launch { stop() }
        return """
            RTSP/1.0 200 OK
            CSeq: $cseq
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    private fun buildErrorResponse(cseq: String, code: Int, message: String): String {
        return """
            RTSP/1.0 $code $message
            CSeq: $cseq
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }

    /**
     * Send video frame over RTP.
     * Frames should be H.264 NAL units wrapped in MPEG2-TS.
     */
    fun sendFrame(frame: EncodedFrame) {
        if (!isRunning.get() || sinkAddress == null) return
        
        try {
            // Wrap H.264 in RTP packet
            val rtpPacket = createRtpPacket(frame.data, frame.isKeyFrame)
            
            val packet = DatagramPacket(
                rtpPacket,
                rtpPacket.size,
                sinkAddress,
                sinkRtpPort
            )
            
            rtpSocket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send RTP packet", e)
        }
    }

    /**
     * Stream encoded frames to the Miracast sink.
     */
    suspend fun streamFrames(frames: Flow<EncodedFrame>) {
        frames.collect { frame ->
            if (isRunning.get()) {
                sendFrame(frame)
            }
        }
    }

    private fun createRtpPacket(payload: ByteArray, marker: Boolean): ByteArray {
        val seq = sequenceNumber.getAndIncrement()
        val ts = timestamp.addAndGet(3000) // ~30fps at 90kHz clock
        
        // RTP header (12 bytes) + payload
        val packet = ByteArray(12 + payload.size)
        
        // Version (2), Padding (0), Extension (0), CSRC count (0)
        packet[0] = 0x80.toByte()
        
        // Marker + Payload type (33 = MPEG2-TS)
        packet[1] = if (marker) 0xA1.toByte() else 0x21.toByte()
        
        // Sequence number (big endian)
        packet[2] = (seq shr 8).toByte()
        packet[3] = seq.toByte()
        
        // Timestamp (big endian)
        packet[4] = (ts shr 24).toByte()
        packet[5] = (ts shr 16).toByte()
        packet[6] = (ts shr 8).toByte()
        packet[7] = ts.toByte()
        
        // SSRC (big endian)
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = ssrc.toByte()
        
        // Copy payload
        System.arraycopy(payload, 0, packet, 12, payload.size)
        
        return packet
    }

    /**
     * Stop the Miracast source.
     */
    fun stop() {
        isRunning.set(false)
        
        try {
            rtspClient?.close()
            rtspServer?.close()
            rtpSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Miracast source", e)
        }
        
        rtspClient = null
        rtspServer = null
        rtpSocket = null
        
        scope.cancel()
        Log.d(TAG, "Miracast source stopped")
    }

    /**
     * Check if the source is running and connected.
     */
    fun isConnected(): Boolean = isRunning.get() && rtspClient?.isConnected == true
}

private data class RtspRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: Map<String, String>
)
