package com.screencast.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screencast.data.SettingsRepository
import com.screencast.update.UpdateInfo
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
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val updateAvailable: UpdateInfo? = null,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val updateManager: UpdateManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        // Load saved quality setting
        viewModelScope.launch {
            settingsRepository.quality.collect { quality ->
                _uiState.update { it.copy(quality = quality) }
            }
        }
    }

    fun setQuality(quality: Quality) {
        settingsRepository.setQuality(quality)
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, error = null) }
            
            val updateInfo = updateManager.checkForUpdate()
            
            _uiState.update { 
                it.copy(
                    isCheckingUpdate = false,
                    updateAvailable = updateInfo
                )
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val updateInfo = _uiState.value.updateAvailable ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadProgress = 0f, error = null) }
            
            try {
                val apkFile = updateManager.downloadUpdate(updateInfo) { progress ->
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
                
                if (apkFile != null) {
                    _uiState.update { it.copy(isDownloading = false) }
                    updateManager.installUpdate(apkFile)
                } else {
                    _uiState.update { 
                        it.copy(isDownloading = false, error = "Download failed") 
                    }
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(isDownloading = false, error = e.message ?: "Download failed") 
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
