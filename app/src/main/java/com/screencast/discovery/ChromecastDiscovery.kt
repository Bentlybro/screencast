package com.screencast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.screencast.model.Device
import com.screencast.model.DeviceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers Chromecast devices using mDNS/DNS-SD.
 * Chromecasts advertise as "_googlecast._tcp.local"
 */
@Singleton
class ChromecastDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ChromecastDiscovery"
        private const val SERVICE_TYPE = "_googlecast._tcp."
    }

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun discoverDevices(): Flow<Device> = callbackFlow {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Chromecast discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Found Chromecast service: ${serviceInfo.serviceName}")
                
                // Resolve to get IP and port
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Failed to resolve ${info.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val port = info.port
                        
                        // Extract friendly name from TXT record or service name
                        val friendlyName = extractFriendlyName(info)
                        val model = extractModel(info)
                        
                        val device = Device(
                            id = "chromecast-$host",
                            name = friendlyName,
                            type = DeviceType.CHROMECAST,
                            address = host,
                            port = port,
                            modelName = model
                        )
                        
                        Log.d(TAG, "Resolved Chromecast: $friendlyName at $host:$port")
                        trySend(device)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Lost Chromecast service: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Chromecast discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Failed to start Chromecast discovery: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Failed to stop Chromecast discovery: $errorCode")
            }
        }

        discoveryListener = listener

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Chromecast discovery", e)
        }

        awaitClose {
            stopDiscovery()
        }
    }

    private fun extractFriendlyName(info: NsdServiceInfo): String {
        // Try to get from TXT records first (fn = friendly name)
        val attributes = info.attributes
        attributes["fn"]?.let { 
            return String(it, Charsets.UTF_8)
        }
        
        // Fall back to service name (usually "Friendly Name-xxxx")
        val serviceName = info.serviceName
        return serviceName.replace(Regex("-[a-f0-9]+$"), "").trim()
    }

    private fun extractModel(info: NsdServiceInfo): String? {
        // md = model name in TXT record
        return info.attributes["md"]?.let { String(it, Charsets.UTF_8) }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        nsdManager = null
    }
}
