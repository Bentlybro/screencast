package com.screencast.streaming

import android.util.Log
import com.screencast.capture.EncodedFrame
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HTTP server that streams H.264 video to DLNA renderers.
 * 
 * Provides an HTTP endpoint that serves the video stream in a format
 * compatible with DLNA MediaRenderer devices.
 */
class StreamingServer(
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "StreamingServer"
        private const val STREAM_PATH = "/stream"
        private const val BUFFER_SIZE = 65536
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var frameSource: Flow<EncodedFrame>? = null
    private var streamJob: Job? = null
    
    // Active client connections
    private val clients = CopyOnWriteArrayList<ClientStream>()
    
    // Config data (SPS/PPS) to send to new clients
    private var configData: ByteArray? = null

    /**
     * Set the source of encoded frames.
     */
    fun setFrameSource(source: Flow<EncodedFrame>) {
        frameSource = source
        startDistribution()
    }

    /**
     * Start the HTTP server.
     */
    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        Log.d(TAG, "Streaming server started on port $listeningPort")
    }

    /**
     * Get the stream URL for this server.
     */
    fun getStreamUrl(): String {
        val ip = getLocalIpAddress() ?: "localhost"
        return "http://$ip:$listeningPort$STREAM_PATH"
    }

    /**
     * Handle incoming HTTP requests.
     */
    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            STREAM_PATH -> handleStreamRequest(session)
            "/crossdomain.xml" -> handleCrossDomainRequest()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun handleStreamRequest(session: IHTTPSession): Response {
        Log.d(TAG, "New stream client: ${session.remoteIpAddress}")
        
        val pipedOutput = PipedOutputStream()
        val pipedInput = PipedInputStream(pipedOutput, BUFFER_SIZE)
        
        val client = ClientStream(pipedOutput)
        clients.add(client)
        
        // Send config data (SPS/PPS) to new client
        scope.launch {
            configData?.let { config ->
                try {
                    pipedOutput.write(config)
                    pipedOutput.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending config to client", e)
                }
            }
        }

        // Create response with chunked transfer
        return newChunkedResponse(
            Response.Status.OK,
            "video/mp4", // Some renderers need this
            pipedInput
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
            addHeader("Content-Type", "video/mp4")
            addHeader("TransferMode.DLNA.ORG", "Streaming")
            addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        }
    }

    private fun handleCrossDomainRequest(): Response {
        val xml = """
            <?xml version="1.0"?>
            <cross-domain-policy>
                <allow-access-from domain="*"/>
            </cross-domain-policy>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/xml", xml)
    }

    /**
     * Start distributing frames to connected clients.
     */
    private fun startDistribution() {
        streamJob?.cancel()
        streamJob = scope.launch {
            frameSource?.collect { frame ->
                // Store config data for new clients
                if (frame.isConfig) {
                    configData = frame.data
                }
                
                // Distribute to all connected clients
                val deadClients = mutableListOf<ClientStream>()
                
                clients.forEach { client ->
                    try {
                        client.outputStream.write(frame.data)
                        client.outputStream.flush()
                    } catch (e: IOException) {
                        Log.d(TAG, "Client disconnected")
                        deadClients.add(client)
                    }
                }
                
                // Clean up disconnected clients
                clients.removeAll(deadClients.toSet())
            }
        }
    }

    /**
     * Stop the server and clean up.
     */
    override fun stop() {
        streamJob?.cancel()
        clients.forEach { 
            try {
                it.outputStream.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        clients.clear()
        scope.cancel()
        super.stop()
        Log.d(TAG, "Streaming server stopped")
    }

    /**
     * Get the local IP address.
     */
    private fun getLocalIpAddress(): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
                intf.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val ip = addr.hostAddress
                        if (ip?.contains('.') == true && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return null
    }

    private class ClientStream(val outputStream: PipedOutputStream)
}
