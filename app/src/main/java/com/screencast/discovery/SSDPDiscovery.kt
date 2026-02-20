package com.screencast.discovery

import android.util.Log
import com.screencast.model.Device
import com.screencast.model.DeviceType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers DLNA MediaRenderer devices using SSDP (Simple Service Discovery Protocol).
 * 
 * SSDP works by sending M-SEARCH multicast messages and listening for responses
 * from UPnP devices on the network.
 */
@Singleton
class SSDPDiscovery @Inject constructor(
    private val httpClient: OkHttpClient
) : DeviceDiscovery {

    companion object {
        private const val TAG = "SSDPDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DISCOVERY_TIMEOUT_MS = 5000
        private const val SOCKET_TIMEOUT_MS = 3000
        
        // Search for MediaRenderer devices (TVs, speakers, etc.)
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:dial-multiscreen-org:service:dial:1" // For smart TVs
        )
    }

    private var socket: DatagramSocket? = null
    private var isRunning = false

    override fun discover(): Flow<Device> = channelFlow {
        isRunning = true
        val discoveredIds = mutableSetOf<String>()
        
        try {
            socket = DatagramSocket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                broadcast = true
            }
            
            // Send M-SEARCH for each target
            for (target in SEARCH_TARGETS) {
                sendMSearch(target)
            }
            
            // Listen for responses
            val buffer = ByteArray(2048)
            val endTime = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS
            
            while (isRunning && System.currentTimeMillis() < endTime) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val response = String(packet.data, 0, packet.length)
                    parseResponse(response, packet.address.hostAddress ?: continue)?.let { device ->
                        if (device.id !in discoveredIds) {
                            discoveredIds.add(device.id)
                            // Fetch device details
                            fetchDeviceDetails(device)?.let { detailedDevice ->
                                send(detailedDevice)
                            } ?: send(device)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Continue listening until timeout
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        } finally {
            stop()
        }
    }

    override fun stop() {
        isRunning = false
        socket?.close()
        socket = null
    }

    private fun sendMSearch(searchTarget: String) {
        val message = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $searchTarget\r\n")
            append("\r\n")
        }
        
        try {
            val data = message.toByteArray()
            val address = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(data, data.size, address, SSDP_PORT)
            socket?.send(packet)
            Log.d(TAG, "Sent M-SEARCH for $searchTarget")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send M-SEARCH", e)
        }
    }

    private fun parseResponse(response: String, address: String): Device? {
        if (!response.startsWith("HTTP/1.1 200") && !response.contains("NOTIFY")) {
            return null
        }
        
        val headers = parseHeaders(response)
        val location = headers["location"] ?: return null
        val usn = headers["usn"] ?: location
        
        // Create a basic device - we'll fetch more details from the location URL
        return Device(
            id = usn.hashCode().toString(),
            name = "Unknown Device",
            type = DeviceType.DLNA,
            address = address,
            controlUrl = location
        )
    }

    private fun parseHeaders(response: String): Map<String, String> {
        return response.lines()
            .mapNotNull { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim().lowercase()
                    val value = line.substring(colonIndex + 1).trim()
                    key to value
                } else null
            }
            .toMap()
    }

    private suspend fun fetchDeviceDetails(device: Device): Device? {
        val location = device.controlUrl ?: return null
        
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(location)
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    
                    val xml = response.body?.string() ?: return@withContext null
                    parseDeviceXml(xml, device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch device details from $location", e)
                null
            }
        }
    }

    private fun parseDeviceXml(xml: String, baseDevice: Device): Device {
        // Simple XML parsing - extract key fields
        val friendlyName = extractXmlValue(xml, "friendlyName") ?: "Unknown Device"
        val modelName = extractXmlValue(xml, "modelName")
        val manufacturer = extractXmlValue(xml, "manufacturer")
        
        // Find AVTransport control URL
        val controlUrl = findAVTransportControlUrl(xml, baseDevice.controlUrl ?: "")
        
        return baseDevice.copy(
            name = friendlyName,
            modelName = modelName,
            manufacturer = manufacturer,
            controlUrl = controlUrl ?: baseDevice.controlUrl
        )
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val startTag = "<$tag>"
        val endTag = "</$tag>"
        val startIndex = xml.indexOf(startTag)
        if (startIndex == -1) return null
        val valueStart = startIndex + startTag.length
        val endIndex = xml.indexOf(endTag, valueStart)
        if (endIndex == -1) return null
        return xml.substring(valueStart, endIndex).trim()
    }

    private fun findAVTransportControlUrl(xml: String, baseUrl: String): String? {
        // Look for AVTransport service
        val avTransportIndex = xml.indexOf("AVTransport")
        if (avTransportIndex == -1) return null
        
        // Find controlURL after AVTransport
        val searchStart = avTransportIndex
        val controlUrlTag = "<controlURL>"
        val controlUrlIndex = xml.indexOf(controlUrlTag, searchStart)
        if (controlUrlIndex == -1) return null
        
        val controlUrl = extractXmlValue(
            xml.substring(controlUrlIndex),
            "controlURL"
        ) ?: return null
        
        // Make absolute URL if relative
        return if (controlUrl.startsWith("http")) {
            controlUrl
        } else {
            val baseUri = java.net.URI(baseUrl)
            "${baseUri.scheme}://${baseUri.host}:${baseUri.port}$controlUrl"
        }
    }
}
