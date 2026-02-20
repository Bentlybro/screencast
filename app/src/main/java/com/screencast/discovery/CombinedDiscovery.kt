package com.screencast.discovery

import com.screencast.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines multiple discovery methods (DLNA + Miracast) into a single flow.
 */
@Singleton
class CombinedDiscovery @Inject constructor(
    private val ssdpDiscovery: SSDPDiscovery,
    private val miracastDiscovery: MiracastDiscovery
) : DeviceDiscovery {

    override fun discover(): Flow<Device> {
        return merge(
            ssdpDiscovery.discover(),
            miracastDiscovery.discover()
        )
    }

    override fun stop() {
        ssdpDiscovery.stop()
    }

    /**
     * Connect to a Miracast device.
     */
    fun connectMiracast(device: Device, callback: (Boolean) -> Unit) {
        miracastDiscovery.connect(device, callback)
    }

    /**
     * Disconnect from Miracast.
     */
    fun disconnectMiracast(callback: (Boolean) -> Unit) {
        miracastDiscovery.disconnect(callback)
    }
}
