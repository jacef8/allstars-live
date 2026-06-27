package com.libertyclerk.allstarslive.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.libertyclerk.allstarslive.R

/**
 * Receives FCM messages (foreground or background) and posts a notification. Messages can carry a
 * `notification` block or `data` keys (title/body). Tapping opens the app. Topic-based, so onNewToken
 * isn't needed for delivery.
 */
class PushService : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        Push.ensureInit(applicationContext)
        val n = msg.notification
        val title = n?.title ?: msg.data["title"] ?: "All-Stars Live"
        val body = n?.body ?: msg.data["body"] ?: ""
        if (title.isBlank() && body.isBlank()) return

        // Tap → open the app. (A future deep link could carry ?watch=<teamId>-live to land on the game.)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notif = NotificationCompat.Builder(this, Push.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        try {
            val ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (ok) NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % 100000).toInt(), notif)
        } catch (e: Throwable) { /* never crash on a push */ }
    }
}
