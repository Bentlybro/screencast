package com.screencast.ui.screens

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screencast.casting.CastManager
import com.screencast.casting.CastState
import com.screencast.model.Device
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CastingViewModel @Inject constructor(
    private val castManager: CastManager
) : ViewModel() {

    val castState: StateFlow<CastState> = castManager.castState
    
    private var targetDevice: Device? = null

    fun setTargetDevice(device: Device) {
        targetDevice = device
    }

    fun startCasting(resultCode: Int, resultData: Intent) {
        val device = targetDevice ?: return
        
        viewModelScope.launch {
            castManager.startCasting(device, resultCode, resultData)
        }
    }

    fun stopCasting() {
        castManager.stopCasting()
    }
}
