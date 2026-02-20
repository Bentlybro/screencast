package com.screencast.model

/**
 * Represents a discovered casting target device.
 */
data class Device(
    val id: String,
    val name: String,
    val type: DeviceType,
    val address: String,
    val port: Int = 0,
    val controlUrl: String? = null, // For DLNA AVTransport control
    val modelName: String? = null,
    val manufacturer: String? = null
)

enum class DeviceType {
    DLNA,
    MIRACAST,
    CHROMECAST,
    ROKU,
    UNKNOWN
}
