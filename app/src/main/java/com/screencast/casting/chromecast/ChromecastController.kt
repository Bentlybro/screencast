package com.screencast.casting.chromecast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Controls Chromecast devices using the Cast v2 protocol.
 * This is a reverse-engineered implementation (like pychromecast).
 * 
 * Cast v2 protocol:
 * - TLS connection to port 8009
 * - Messages are protobuf with JSON payloads
 * - Uses namespaces for different functionality
 */
@Singleton
class ChromecastController @Inject constructor() {

    companion object {
        private const val TAG = "ChromecastController"
        private const val CAST_PORT = 8009
        
        // Cast namespaces
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        
        // Default Media Receiver app ID (public, no registration needed)
        private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        
        // Source/destination IDs
        private const val SOURCE_ID = "sender-0"
        private const val RECEIVER_ID = "receiver-0"
    }

    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var transportId: String? = null
    private var mediaSessionId: Int? = null
    private var requestId = 0

    /**
     * Connect to a Chromecast device
     */
    suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to Chromecast at $address:$CAST_PORT")
            
            // Create SSL socket with permissive trust (Chromecast uses self-signed certs)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(permissiveTrustManager), SecureRandom())
            val factory = sslContext.socketFactory
            
            socket = factory.createSocket(address, CAST_PORT) as SSLSocket
            socket?.startHandshake()
            
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())
            
            // Send CONNECT message to establish channel
            sendMessage(NS_CONNECTION, RECEIVER_ID, JSONObject().apply {
                put("type", "CONNECT")
            })
            
            // Start heartbeat (Chromecast disconnects after ~5s without ping)
            // In production, this should be on a timer
            
            Log.d(TAG, "Connected to Chromecast")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Chromecast", e)
            disconnect()
            false
        }
    }

    /**
     * Launch the Default Media Receiver and start streaming
     */
    suspend fun startCasting(streamUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Launch Default Media Receiver app
            val launchResponse = launchApp(DEFAULT_MEDIA_RECEIVER)
            if (launchResponse == null) {
                Log.e(TAG, "Failed to launch media receiver")
                return@withContext false
            }
            
            // Connect to the launched app
            transportId = launchResponse
            sendMessage(NS_CONNECTION, transportId!!, JSONObject().apply {
                put("type", "CONNECT")
            })
            
            // Load media
            val loadResult = loadMedia(streamUrl)
            Log.d(TAG, "Media load result: $loadResult")
            
            loadResult
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start casting", e)
            false
        }
    }

    /**
     * Launch a Cast app and return its transport ID
     */
    private suspend fun launchApp(appId: String): String? = withContext(Dispatchers.IO) {
        sendMessage(NS_RECEIVER, RECEIVER_ID, JSONObject().apply {
            put("type", "LAUNCH")
            put("appId", appId)
            put("requestId", ++requestId)
        })
        
        // Read response to get transport ID
        repeat(10) {
            val response = readMessage()
            if (response != null) {
                val payload = response.optJSONObject("payload")
                val status = payload?.optJSONObject("status")
                val apps = status?.optJSONArray("applications")
                if (apps != null && apps.length() > 0) {
                    val app = apps.getJSONObject(0)
                    return@withContext app.optString("transportId")
                }
            }
            Thread.sleep(200)
        }
        null
    }

    /**
     * Load media URL into the receiver
     */
    private suspend fun loadMedia(url: String): Boolean = withContext(Dispatchers.IO) {
        val transport = transportId ?: return@withContext false
        
        sendMessage(NS_MEDIA, transport, JSONObject().apply {
            put("type", "LOAD")
            put("requestId", ++requestId)
            put("media", JSONObject().apply {
                put("contentId", url)
                put("contentType", "video/mp4")
                put("streamType", "LIVE")
            })
            put("autoplay", true)
        })
        
        // Wait for media session
        repeat(10) {
            val response = readMessage()
            if (response != null) {
                val payload = response.optJSONObject("payload")
                val sessionId = payload?.optInt("mediaSessionId", -1)
                if (sessionId != null && sessionId != -1) {
                    mediaSessionId = sessionId
                    return@withContext true
                }
            }
            Thread.sleep(200)
        }
        false
    }

    /**
     * Stop casting and close app
     */
    suspend fun stopCasting() = withContext(Dispatchers.IO) {
        try {
            transportId?.let { transport ->
                // Stop media
                mediaSessionId?.let { sessionId ->
                    sendMessage(NS_MEDIA, transport, JSONObject().apply {
                        put("type", "STOP")
                        put("requestId", ++requestId)
                        put("mediaSessionId", sessionId)
                    })
                }
            }
            
            // Stop receiver app
            sendMessage(NS_RECEIVER, RECEIVER_ID, JSONObject().apply {
                put("type", "STOP")
                put("requestId", ++requestId)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping cast", e)
        }
    }

    /**
     * Send a ping to keep connection alive
     */
    suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        try {
            sendMessage(NS_HEARTBEAT, RECEIVER_ID, JSONObject().apply {
                put("type", "PING")
            })
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
        }
    }

    /**
     * Disconnect from Chromecast
     */
    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
        socket = null
        input = null
        output = null
        transportId = null
        mediaSessionId = null
    }

    /**
     * Send a Cast protocol message
     * 
     * Message format (simplified - real Cast uses protobuf):
     * - 4 bytes: message length (big-endian)
     * - N bytes: protobuf CastMessage
     * 
     * For simplicity, we build the protobuf manually
     */
    private fun sendMessage(namespace: String, destinationId: String, payload: JSONObject) {
        val out = output ?: return
        
        val message = buildCastMessage(
            sourceId = SOURCE_ID,
            destinationId = destinationId,
            namespace = namespace,
            payload = payload.toString()
        )
        
        synchronized(out) {
            out.writeInt(message.size)
            out.write(message)
            out.flush()
        }
        
        Log.d(TAG, "Sent: $namespace -> $destinationId: $payload")
    }

    /**
     * Read a Cast protocol message
     */
    private fun readMessage(): JSONObject? {
        val inp = input ?: return null
        
        return try {
            if (inp.available() > 0) {
                val length = inp.readInt()
                val data = ByteArray(length)
                inp.readFully(data)
                parseCastMessage(data)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading message", e)
            null
        }
    }

    /**
     * Build a CastMessage protobuf manually
     * 
     * Proto definition (simplified):
     * message CastMessage {
     *   required string source_id = 2;
     *   required string destination_id = 3;
     *   required string namespace = 4;
     *   required PayloadType payload_type = 5;
     *   optional string payload_utf8 = 6;
     * }
     */
    private fun buildCastMessage(
        sourceId: String,
        destinationId: String,
        namespace: String,
        payload: String
    ): ByteArray {
        val buffer = mutableListOf<Byte>()
        
        // Protocol version = 0 (field 1, varint)
        buffer.addAll(encodeVarintField(1, 0))
        
        // source_id (field 2, string)
        buffer.addAll(encodeStringField(2, sourceId))
        
        // destination_id (field 3, string)
        buffer.addAll(encodeStringField(3, destinationId))
        
        // namespace (field 4, string)
        buffer.addAll(encodeStringField(4, namespace))
        
        // payload_type = STRING (0) (field 5, varint)
        buffer.addAll(encodeVarintField(5, 0))
        
        // payload_utf8 (field 6, string)
        buffer.addAll(encodeStringField(6, payload))
        
        return buffer.toByteArray()
    }

    private fun encodeVarintField(fieldNumber: Int, value: Int): List<Byte> {
        val result = mutableListOf<Byte>()
        // Wire type 0 (varint) = fieldNumber << 3 | 0
        result.add((fieldNumber shl 3).toByte())
        result.addAll(encodeVarint(value))
        return result
    }

    private fun encodeStringField(fieldNumber: Int, value: String): List<Byte> {
        val result = mutableListOf<Byte>()
        val bytes = value.toByteArray(Charsets.UTF_8)
        // Wire type 2 (length-delimited) = fieldNumber << 3 | 2
        result.add(((fieldNumber shl 3) or 2).toByte())
        result.addAll(encodeVarint(bytes.size))
        result.addAll(bytes.toList())
        return result
    }

    private fun encodeVarint(value: Int): List<Byte> {
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result
    }

    /**
     * Parse a CastMessage protobuf
     */
    private fun parseCastMessage(data: ByteArray): JSONObject? {
        var i = 0
        var namespace: String? = null
        var payloadUtf8: String? = null
        
        while (i < data.size) {
            val tag = data[i].toInt() and 0xFF
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            i++
            
            when (wireType) {
                0 -> { // Varint
                    while (i < data.size && (data[i].toInt() and 0x80) != 0) i++
                    i++
                }
                2 -> { // Length-delimited
                    var length = 0
                    var shift = 0
                    while (i < data.size) {
                        val b = data[i++].toInt() and 0xFF
                        length = length or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    
                    if (i + length <= data.size) {
                        val bytes = data.copyOfRange(i, i + length)
                        when (fieldNumber) {
                            4 -> namespace = String(bytes, Charsets.UTF_8)
                            6 -> payloadUtf8 = String(bytes, Charsets.UTF_8)
                        }
                        i += length
                    }
                }
            }
        }
        
        return if (payloadUtf8 != null) {
            try {
                JSONObject().apply {
                    put("namespace", namespace)
                    put("payload", JSONObject(payloadUtf8))
                }
            } catch (e: Exception) {
                null
            }
        } else null
    }

    /**
     * Trust manager that accepts all certificates (Chromecast uses self-signed)
     */
    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
