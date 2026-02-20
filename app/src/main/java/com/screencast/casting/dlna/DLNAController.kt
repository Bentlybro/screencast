package com.screencast.casting.dlna

import android.util.Log
import com.screencast.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controls DLNA MediaRenderer devices using UPnP AVTransport.
 * 
 * Sends SOAP requests to control playback on the target device.
 */
@Singleton
class DLNAController @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DLNAController"
        private val SOAP_MEDIA_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }

    /**
     * Tell the device to play our stream URL.
     */
    suspend fun setAVTransportURI(device: Device, streamUrl: String): Boolean {
        val controlUrl = device.controlUrl ?: return false
        
        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        val soapBody = buildSetAVTransportURIBody(streamUrl, device.name)
        
        return sendSoapRequest(controlUrl, soapAction, soapBody)
    }

    /**
     * Start playback on the device.
     */
    suspend fun play(device: Device): Boolean {
        val controlUrl = device.controlUrl ?: return false
        
        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""
        val soapBody = buildPlayBody()
        
        return sendSoapRequest(controlUrl, soapAction, soapBody)
    }

    /**
     * Pause playback on the device.
     */
    suspend fun pause(device: Device): Boolean {
        val controlUrl = device.controlUrl ?: return false
        
        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\""
        val soapBody = buildPauseBody()
        
        return sendSoapRequest(controlUrl, soapAction, soapBody)
    }

    /**
     * Stop playback on the device.
     */
    suspend fun stop(device: Device): Boolean {
        val controlUrl = device.controlUrl ?: return false
        
        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\""
        val soapBody = buildStopBody()
        
        return sendSoapRequest(controlUrl, soapAction, soapBody)
    }

    /**
     * Get the current transport state (PLAYING, STOPPED, etc).
     */
    suspend fun getTransportInfo(device: Device): TransportInfo? {
        val controlUrl = device.controlUrl ?: return null
        
        val soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\""
        val soapBody = buildGetTransportInfoBody()
        
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(controlUrl)
                    .post(soapBody.toRequestBody(SOAP_MEDIA_TYPE))
                    .addHeader("SOAPAction", soapAction)
                    .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@withContext null
                        parseTransportInfo(body)
                    } else {
                        Log.e(TAG, "GetTransportInfo failed: ${response.code}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting transport info", e)
                null
            }
        }
    }

    private suspend fun sendSoapRequest(
        controlUrl: String,
        soapAction: String,
        soapBody: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(controlUrl)
                .post(soapBody.toRequestBody(SOAP_MEDIA_TYPE))
                .addHeader("SOAPAction", soapAction)
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "SOAP request successful: $soapAction")
                    true
                } else {
                    Log.e(TAG, "SOAP request failed: ${response.code} - ${response.body?.string()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending SOAP request", e)
            false
        }
    }

    private fun buildSetAVTransportURIBody(uri: String, title: String): String {
        val metadata = buildDIDLMetadata(uri, title)
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>${escapeXml(uri)}</CurrentURI>
                        <CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    private fun buildPlayBody(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                    <Speed>1</Speed>
                </u:Play>
            </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun buildPauseBody(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
                <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                </u:Pause>
            </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun buildStopBody(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
                <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                </u:Stop>
            </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun buildGetTransportInfoBody(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
                <u:GetTransportInfo xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                    <InstanceID>0</InstanceID>
                </u:GetTransportInfo>
            </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun buildDIDLMetadata(uri: String, title: String): String = """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="0" parentID="-1" restricted="1">
                <dc:title>$title</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000">$uri</res>
            </item>
        </DIDL-Lite>
    """.trimIndent()

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun parseTransportInfo(xml: String): TransportInfo? {
        val state = extractXmlValue(xml, "CurrentTransportState") ?: return null
        val status = extractXmlValue(xml, "CurrentTransportStatus") ?: "OK"
        val speed = extractXmlValue(xml, "CurrentSpeed") ?: "1"
        
        return TransportInfo(
            state = TransportState.fromString(state),
            status = status,
            speed = speed
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
}

data class TransportInfo(
    val state: TransportState,
    val status: String,
    val speed: String
)

enum class TransportState {
    STOPPED,
    PLAYING,
    PAUSED_PLAYBACK,
    TRANSITIONING,
    NO_MEDIA_PRESENT,
    UNKNOWN;

    companion object {
        fun fromString(value: String): TransportState {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
