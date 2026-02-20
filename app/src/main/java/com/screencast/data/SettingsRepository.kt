package com.screencast.data

import android.content.SharedPreferences
import com.screencast.ui.screens.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_QUALITY = "quality"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
    }

    private val _quality = MutableStateFlow(loadQuality())
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    private val _audioEnabled = MutableStateFlow(loadAudioEnabled())
    val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    fun setQuality(quality: Quality) {
        prefs.edit().putString(KEY_QUALITY, quality.name).apply()
        _quality.value = quality
    }

    fun setAudioEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply()
        _audioEnabled.value = enabled
    }

    private fun loadQuality(): Quality {
        val name = prefs.getString(KEY_QUALITY, Quality.MEDIUM.name)
        return try {
            Quality.valueOf(name ?: Quality.MEDIUM.name)
        } catch (e: Exception) {
            Quality.MEDIUM
        }
    }

    private fun loadAudioEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUDIO_ENABLED, true)
    }
}
