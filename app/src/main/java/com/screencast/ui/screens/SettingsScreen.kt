package com.screencast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.screencast.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Quality Settings Section
            SettingsSection(title = "Quality") {
                QualitySelector(
                    selectedQuality = uiState.quality,
                    onQualitySelected = { viewModel.setQuality(it) }
                )
            }
            
            // About Section
            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "Version",
                    subtitle = BuildConfig.VERSION_NAME
                )
                
                SettingsItem(
                    icon = Icons.Default.Update,
                    title = "Check for updates",
                    subtitle = when {
                        uiState.isCheckingUpdate -> "Checking..."
                        uiState.updateAvailable != null -> "Update available: ${uiState.updateAvailable}"
                        else -> "You're up to date"
                    },
                    onClick = { viewModel.checkForUpdate() }
                )
                
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "Source code",
                    subtitle = "github.com/Bentlybro/screencast",
                    onClick = { /* Open GitHub */ }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QualitySelector(
    selectedQuality: Quality,
    onQualitySelected: (Quality) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Quality.entries.forEach { quality ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onQualitySelected(quality) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = quality == selectedQuality,
                    onClick = { onQualitySelected(quality) }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = quality.label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = quality.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

enum class Quality(
    val label: String,
    val description: String,
    val width: Int,
    val height: Int,
    val bitrate: Int
) {
    LOW("Low", "720p @ 2 Mbps - Better for slow networks", 1280, 720, 2_000_000),
    MEDIUM("Medium", "1080p @ 4 Mbps - Balanced", 1920, 1080, 4_000_000),
    HIGH("High", "1080p @ 8 Mbps - Best quality", 1920, 1080, 8_000_000)
}
