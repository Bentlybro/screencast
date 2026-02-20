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
        private const val DISCOVERY_TIMEOUT_MS = 15000  // Increased to 15 seconds
        private const val SOCKET_TIMEOUT_MS = 5000
        
        // Search for various device types
        private val SEARCH_TARGETS = listOf(
            "ssdp:all",  // Find ALL UPnP devices
            "upnp:rootdevice",  // All root devices
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaRenderer:2",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:dial-multiscreen-org:service:dial:1",  // DIAL (Netflix, YouTube casting)
            "urn:schemas-upnp-org:device:Basic:1",
            "urn:samsung.com:device:RemoteControlReceiver:1",  // Samsung TVs
            "urn:panasonic-com:device:p00RemoteController:1"   // Panasonic TVs
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
            
            // Send M-SEARCH for each target (multiple rounds for reliability)
            repeat(3) { round ->
                for (target in SEARCH_TARGETS) {
                    sendMSearch(target)
                }
                if (round < 2) {
                    Thread.sleep(500)  // Small delay between rounds
                }
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
        // Accept both M-SEARCH responses and NOTIFY announcements
        if (!response.startsWith("HTTP/1.1 200") && 
            !response.contains("NOTIFY") &&
            !response.contains("HTTP/1.1")) {
            return null
        }
        
        val headers = parseHeaders(response)
        val location = headers["location"] ?: return null
        val usn = headers["usn"] ?: location
        val server = headers["server"] ?: ""
        val st = headers["st"] ?: headers["nt"] ?: ""
        
        // Skip non-rendering devices if we can tell
        if (st.contains("ContentDirectory") || st.contains("ConnectionManager")) {
            return null  // These are media servers, not renderers
        }
        
        // Create a basic device - we'll fetch more details from the location URL
        return Device(
            id = usn.hashCode().toString(),
            name = extractNameFromServer(server),
            type = DeviceType.DLNA,
            address = address,
            controlUrl = location,
            modelName = server.takeIf { it.isNotEmpty() }
        )
    }
    
    private fun extractNameFromServer(server: String): String {
        // Try to extract a friendly name from the server header
        return when {
            server.contains("Samsung", ignoreCase = true) -> "Samsung TV"
            server.contains("LG", ignoreCase = true) -> "LG TV"
            server.contains("Sony", ignoreCase = true) -> "Sony TV"
            server.contains("Roku", ignoreCase = true) -> "Roku"
            server.contains("Fire", ignoreCase = true) -> "Fire TV"
            server.contains("Chromecast", ignoreCase = true) -> "Chromecast"
            server.isNotEmpty() -> server.take(30)
            else -> "Unknown Device"
        }
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
