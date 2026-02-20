package com.screencast.discovery

import com.screencast.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Combines multiple discovery methods (DLNA + Miracast + Chromecast) into a single flow.
 */
@Singleton
class CombinedDiscovery @Inject constructor(
    private val ssdpDiscovery: SSDPDiscovery,
    private val miracastDiscovery: MiracastDiscovery,
    private val chromecastDiscovery: ChromecastDiscovery
) : DeviceDiscovery {

    override fun discover(): Flow<Device> {
        return merge(
            ssdpDiscovery.discover(),
            miracastDiscovery.discover(),
            chromecastDiscovery.discoverDevices()
        )
    }

    override fun stop() {
        ssdpDiscovery.stop()
        chromecastDiscovery.stopDiscovery()
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
