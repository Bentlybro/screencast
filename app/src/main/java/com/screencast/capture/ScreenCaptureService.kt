package com.screencast.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.screencast.MainActivity
import com.screencast.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that handles screen capture via MediaProjection.
 * 
 * Android requires MediaProjection to run in a foreground service with
 * a visible notification so users know their screen is being captured.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.screencast.START_CAPTURE"
        const val ACTION_STOP = "com.screencast.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DEVICE_NAME = "device_name"
        
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_capture"
    }

    private var mediaProjection: MediaProjection? = null
    private var isCapturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "device"
                
                if (resultCode != -1 && resultData != null) {
                    startCapture(resultCode, resultData, deviceName)
                }
            }
            ACTION_STOP -> {
                stopCapture()
            }
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent, deviceName: String) {
        val notification = createNotification(deviceName)
        startForeground(NOTIFICATION_ID, notification)
        
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, null)
        
        isCapturing = true
        
        // TODO: Set up VirtualDisplay and start encoding
        // This will be implemented in the capture module
    }

    private fun stopCapture() {
        isCapturing = false
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when screen is being cast"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(deviceName: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Casting screen")
            .setContentText(getString(R.string.notification_casting, deviceName))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
