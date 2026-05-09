package com.catguard.app.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.catguard.app.R

/**
 * Foreground service that keeps the camera streaming alive when the app is backgrounded.
 * Without this, Android will kill the camera process after a few minutes.
 */
class CameraStreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "CatGuardCameraChannel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CatGuard Camera Streaming",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active while camera is streaming"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CatGuard Active")
            .setContentText("Camera is streaming and monitoring for your cat")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
