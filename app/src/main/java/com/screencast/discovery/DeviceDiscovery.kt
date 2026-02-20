package com.screencast.discovery

import com.screencast.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * Interface for discovering casting target devices on the network.
 */
interface DeviceDiscovery {
    /**
     * Start discovering devices. Emits devices as they are found.
     * The flow completes when discovery finishes (after timeout).
     */
    fun discover(): Flow<Device>
    
    /**
     * Stop any ongoing discovery.
     */
    fun stop()
}
