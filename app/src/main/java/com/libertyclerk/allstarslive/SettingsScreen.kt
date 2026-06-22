package com.libertyclerk.allstarslive

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libertyclerk.allstarslive.youtube.YouTubeAuth

/**
 * YouTube account connection (M3 full). "Connect YouTube" runs Google authorization
 * for the YouTube scope and verifies by reading the signed-in channel — so the app can
 * create the broadcast and stream with no stream key to copy.
 *
 * This used to be a standalone Settings tab; with only one option, it now embeds on the
 * Video tab's Camera setup panel (the admin/setup home) instead of taking its own tab.
 */
@Composable
fun YouTubeAccountSection(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("allstars", Context.MODE_PRIVATE) }
    // Show the remembered channel so returning here doesn't look "disconnected".
    var status by remember {
        mutableStateOf(prefs.getString("yt_channel", null)?.let { "Connected: $it" } ?: "Not connected")
    }
    var working by remember { mutableStateOf(false) }

    fun setOnMain(s: String, busy: Boolean) {
        Handler(Looper.getMainLooper()).post { status = s; working = busy }
    }

    fun verify(token: String) {
        status = "Checking your channel…"; working = true
        Thread {
            runCatching { YouTubeAuth.fetchChannelTitle(token) }
                .onSuccess { name ->
                    prefs.edit().putString("yt_channel", name).apply()   // remember across tab switches
                    setOnMain("Connected: $name", false)
                }
                .onFailure { setOnMain("Signed in, but channel read failed: ${it.message}", false) }
        }.start()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching {
            val result = YouTubeAuth.client(ctx).getAuthorizationResultFromIntent(res.data)
            val token = result.accessToken
            if (token != null) verify(token) else { status = "No token returned"; working = false }
        }.onFailure { status = "Sign-in cancelled"; working = false }
    }

    fun connect() {
        status = "Opening Google sign-in…"; working = true
        YouTubeAuth.client(ctx).authorize(YouTubeAuth.request())
            .addOnSuccessListener { result ->
                val pi = result.pendingIntent
                val token = result.accessToken
                when {
                    result.hasResolution() && pi != null ->
                        launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    token != null -> verify(token)
                    else -> { status = "No authorization result"; working = false }
                }
            }
            .addOnFailureListener { status = "Failed: ${it.message}"; working = false }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF18223A), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("YouTube account", color = Color(0xFFEAEDF2), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Connect your channel so the app can start the broadcast and stream — no stream key to copy.",
            color = Color(0xFF9AA0A6), fontSize = 13.sp,
        )
        Text(status, color = Color(0xFFA3E635), fontSize = 13.sp)
        Button(
            onClick = { connect() },
            enabled = !working,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000), contentColor = Color.White),
        ) {
            Text(if (status.startsWith("Connected")) "Reconnect YouTube" else "Connect YouTube", fontSize = 15.sp)
        }
    }
}
