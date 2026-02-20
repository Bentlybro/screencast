package com.screencast.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screencast.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val quality: Quality = Quality.MEDIUM,
    val isCheckingUpdate: Boolean = false,
    val updateAvailable: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val updateManager: UpdateManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setQuality(quality: Quality) {
        _uiState.update { it.copy(quality = quality) }
        // TODO: Persist quality setting
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true) }
            
            val updateInfo = updateManager.checkForUpdate()
            
            _uiState.update { 
                it.copy(
                    isCheckingUpdate = false,
                    updateAvailable = updateInfo?.versionName
                )
            }
        }
    }
}
