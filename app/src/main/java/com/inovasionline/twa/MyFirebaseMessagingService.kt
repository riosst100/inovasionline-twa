package com.inovasionline.twa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "default_channel"
        private const val PREF = "push"
        private const val KEY_SENT = "sent"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        showNotification(message)
    }

    override fun onNewToken(token: String) {

        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)

        prefs.edit {
            putBoolean(KEY_SENT, false)
        }

        PushRegistrar.ensureRegistered(this)
    }

    private fun showNotification(message: RemoteMessage) {

        val title = message.notification?.title ?: "Inovasi Online"
        val body = message.notification?.body ?: ""
        val targetUrl =
            message.data["url"] ?: "https://inovasionline.com"

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_url", targetUrl)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Default",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }
}
