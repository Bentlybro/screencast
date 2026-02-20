package com.screencast.discovery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.screencast.model.Device
import com.screencast.model.DeviceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers Miracast-capable devices using WiFi Direct (P2P).
 */
@Singleton
class MiracastDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MiracastDiscovery"
    }

    private var manager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isDiscovering = false

    /**
     * Discover Miracast devices via WiFi Direct.
     */
    fun discover(): Flow<Device> = callbackFlow {
        // Check permissions
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing required permissions for WiFi P2P")
            close()
            return@callbackFlow
        }

        // Initialize WiFi P2P
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "WiFi P2P not supported on this device")
            close()
            return@callbackFlow
        }

        p2pChannel = manager?.initialize(context, Looper.getMainLooper()) { 
            Log.d(TAG, "WiFi P2P channel disconnected")
        }

        // Create broadcast receiver for P2P events
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.w(TAG, "WiFi P2P is not enabled")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Request peer list
                        requestPeers { devices ->
                            devices.forEach { device ->
                                trySend(device)
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Log.d(TAG, "P2P connection changed")
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        Log.d(TAG, "This device changed")
                    }
                }
            }
        }

        // Register receiver
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }

        // Start discovery
        startDiscovery()

        awaitClose {
            stopDiscovery()
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val locationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val nearbyPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return locationPermission && nearbyPermission
    }

    @Suppress("MissingPermission")
    private fun startDiscovery() {
        if (isDiscovering) return
        
        manager?.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P discovery started")
                isDiscovering = true
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                    WifiP2pManager.ERROR -> "Internal error"
                    WifiP2pManager.BUSY -> "Framework busy"
                    else -> "Unknown ($reason)"
                }
                Log.e(TAG, "P2P discovery failed: $reasonStr")
            }
        })
    }

    @Suppress("MissingPermission")
    private fun stopDiscovery() {
        if (!isDiscovering) return
        
        manager?.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P discovery stopped")
                isDiscovering = false
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to stop P2P discovery: $reason")
            }
        })
    }

    @Suppress("MissingPermission")
    private fun requestPeers(callback: (List<Device>) -> Unit) {
        manager?.requestPeers(p2pChannel) { peers: WifiP2pDeviceList? ->
            val devices = peers?.deviceList?.mapNotNull { p2pDevice ->
                // Filter for display-capable devices
                if (isDisplayCapable(p2pDevice)) {
                    Device(
                        id = p2pDevice.deviceAddress,
                        name = p2pDevice.deviceName.ifEmpty { "Unknown Device" },
                        type = DeviceType.MIRACAST,
                        address = p2pDevice.deviceAddress,
                        modelName = getDeviceTypeString(p2pDevice.primaryDeviceType)
                    )
                } else null
            } ?: emptyList()
            
            callback(devices)
        }
    }

    private fun isDisplayCapable(device: WifiP2pDevice): Boolean {
        // Primary device type format: "category-OUI-subcategory"
        // Display devices are category 7
        val primaryType = device.primaryDeviceType ?: return true // Accept if unknown
        return primaryType.startsWith("7-") || // Display
               primaryType.startsWith("1-") || // Computer
               primaryType.contains("Display", ignoreCase = true)
    }

    private fun getDeviceTypeString(primaryType: String?): String {
        if (primaryType == null) return "Unknown"
        return when {
            primaryType.startsWith("7-") -> "Display"
            primaryType.startsWith("1-") -> "Computer"
            primaryType.startsWith("10-") -> "Phone"
            else -> "Device"
        }
    }

    /**
     * Connect to a Miracast device.
     */
    @Suppress("MissingPermission")
    fun connect(device: Device, callback: (Boolean) -> Unit) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.address
            // Request to be the group owner (sender)
            groupOwnerIntent = 0
        }

        manager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P connection initiated to ${device.name}")
                callback(true)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P connection failed: $reason")
                callback(false)
            }
        })
    }

    /**
     * Disconnect from current P2P connection.
     */
    @Suppress("MissingPermission")
    fun disconnect(callback: (Boolean) -> Unit) {
        manager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P disconnected")
                callback(true)
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "P2P disconnect failed: $reason")
                callback(false)
            }
        })
    }
}
