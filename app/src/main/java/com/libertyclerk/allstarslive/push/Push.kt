package com.libertyclerk.allstarslive.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM push setup, topic-based (no per-device token wrangling, works signed-out — matches the no-login
 * viewing goal). The web layer computes topic names from the user's notification prefs + followed teams
 * (e.g. "t_<teamId>_live_scores") and calls subscribe()/unsubscribe() through the AllStars JS bridge.
 * A Cloud Function later sends a message to a topic when that event happens.
 *
 * NOTE: reliable FCM registration needs an Android app registered in the Firebase console
 * (google-services.json + the com.google.gms.google-services plugin). Until that's added, we init
 * Firebase MANUALLY from the project's public config so this compiles and runs; everything is wrapped
 * so a missing/invalid setup can never crash the app (it just no-ops).
 */
object Push {
    const val CHANNEL_ID = "allstars_games"
    private const val TAG = "Push"

    fun ensureInit(ctx: Context) {
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                val opts = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyDlGe5_A-d5_SgWIlOPf9Q6lGzGeVJNPKQ")
                    .setApplicationId("1:55677156135:web:4d05306da22a47f4a74702")
                    .setProjectId("allstars-live")
                    .setGcmSenderId("55677156135")
                    .build()
                FirebaseApp.initializeApp(ctx, opts)
            }
        } catch (e: Throwable) { Log.w(TAG, "init: ${e.message}") }
        createChannel(ctx)
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Game alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                            description = "Game reminders, live scoring, schedule and chat"
                        },
                    )
                }
            } catch (e: Throwable) { Log.w(TAG, "channel: ${e.message}") }
        }
    }

    fun subscribe(ctx: Context, topic: String) {
        ensureInit(ctx)
        try { FirebaseMessaging.getInstance().subscribeToTopic(topic) } catch (e: Throwable) { Log.w(TAG, "sub $topic: ${e.message}") }
    }

    fun unsubscribe(ctx: Context, topic: String) {
        try { FirebaseMessaging.getInstance().unsubscribeFromTopic(topic) } catch (e: Throwable) { Log.w(TAG, "unsub $topic: ${e.message}") }
    }
}
