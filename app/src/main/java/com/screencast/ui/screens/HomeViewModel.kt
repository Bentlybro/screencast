package com.screencast.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screencast.discovery.DeviceDiscovery
import com.screencast.model.Device
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val devices: List<Device> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
    val needsPermissions: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceDiscovery: DeviceDiscovery
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null

    init {
        startDiscovery()
    }

    fun refresh() {
        // Clear existing devices and start fresh
        _uiState.update { it.copy(devices = emptyList()) }
        startDiscovery()
    }

    fun onPermissionsGranted() {
        _uiState.update { it.copy(needsPermissions = false) }
        startDiscovery()
    }

    fun onPermissionsDenied() {
        _uiState.update { it.copy(needsPermissions = false) }
        // Still try to discover DLNA devices (doesn't need location permission)
        startDiscovery()
    }

    private fun startDiscovery() {
        // Cancel any existing discovery
        discoveryJob?.cancel()
        
        discoveryJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            
            try {
                deviceDiscovery.discover().collect { device ->
                    _uiState.update { state ->
                        val updatedDevices = if (state.devices.any { it.id == device.id }) {
                            state.devices.map { if (it.id == device.id) device else it }
                        } else {
                            state.devices + device
                        }
                        state.copy(devices = updatedDevices)
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        deviceDiscovery.stop()
    }
}
