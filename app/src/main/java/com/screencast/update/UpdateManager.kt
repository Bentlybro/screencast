package com.screencast.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.screencast.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages OTA updates from GitHub Releases.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API_URL = "https://api.github.com/repos/Bentlybro/screencast/releases/latest"
        private const val UPDATE_DIR = "updates"
    }

    /**
     * Check for available updates.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to check for updates: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                
                val tagName = json.getString("tag_name")
                val versionName = tagName.removePrefix("v")
                val versionCode = parseVersionCode(versionName)
                
                // Check if this is a newer version
                if (versionCode <= BuildConfig.VERSION_CODE) {
                    Log.d(TAG, "No update available (current: ${BuildConfig.VERSION_CODE}, latest: $versionCode)")
                    return@withContext null
                }

                // Find APK download URL
                val assets = json.getJSONArray("assets")
                var downloadUrl: String? = null
                
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (downloadUrl == null) {
                    Log.e(TAG, "No APK found in release")
                    return@withContext null
                }

                val releaseNotes = json.optString("body", "")

                UpdateInfo(
                    versionCode = versionCode,
                    versionName = versionName,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            null
        }
    }

    /**
     * Download an update.
     */
    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download update: ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()
                
                // Create update directory
                val updateDir = File(context.cacheDir, UPDATE_DIR).apply {
                    mkdirs()
                }
                
                // Clean old updates
                updateDir.listFiles()?.forEach { it.delete() }
                
                val apkFile = File(updateDir, "screencast-${updateInfo.versionName}.apk")
                
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    val inputStream = body.byteStream()
                    
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        
                        output.write(buffer, 0, read)
                        bytesRead += read
                        
                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength)
                        }
                    }
                }

                Log.d(TAG, "Update downloaded: ${apkFile.absolutePath}")
                apkFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading update", e)
            null
        }
    }

    /**
     * Install a downloaded update.
     */
    fun installUpdate(apkFile: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        
        context.startActivity(intent)
    }

    /**
     * Parse version string to version code.
     * Handles formats like "1.2.3", "1.2.3-beta", "1.0.0-rc1" -> numeric code
     */
    private fun parseVersionCode(version: String): Int {
        return try {
            // Remove any suffix like -beta, -rc1, etc
            val cleanVersion = version.replace(Regex("-.*$"), "")
            val parts = cleanVersion.split(".").map { 
                // Also handle cases like "1a" -> "1"
                it.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            }
            when (parts.size) {
                1 -> parts[0] * 10000
                2 -> parts[0] * 10000 + parts[1] * 100
                else -> parts[0] * 10000 + parts[1] * 100 + parts.getOrElse(2) { 0 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse version: $version", e)
            0
        }
    }
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String
)
