package com.libertyclerk.allstarslive

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libertyclerk.allstarslive.ingest.CompositorTestScreen
import com.libertyclerk.allstarslive.ingest.SrtIngestScreen
import com.libertyclerk.allstarslive.scorer.GameScorerScreen
import com.libertyclerk.allstarslive.scorer.createScorerWebView
import com.libertyclerk.allstarslive.stream.Broadcast
import com.libertyclerk.allstarslive.stream.GoLiveDialog
import com.libertyclerk.allstarslive.ui.theme.AllStarsLiveTheme
import kotlinx.coroutines.delay

// A native bottom-tab-bar navigation model (Tab enum, AllStarsBottomBar, TabsPeekButton,
// ComingSoon, plus their NavBarColor/NavHairline/Sage color constants) used to live here --
// removed as dead code, confirmed zero call sites anywhere in the module. The app IS the web
// scorer, full-screen, no native tabs (matches the web/PWA) -- see the comment on the actual
// root Composable below.

// OAuth "Web client ID" (Firebase auto-created) — audience for the Google ID token we hand to
// the web's Firebase. The app's signing SHA-1 must be registered in the Firebase project too.
private const val WEB_CLIENT_ID = "55677156135-jj5069itokdpgnti217jq91mmqfpn2bo.apps.googleusercontent.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones & small screens run PORTRAIT; tablets (>= 600dp wide) stay LANDSCAPE for the wide
        // scorer/monitor layout. (Replaces the manifest's hard android:screenOrientation="landscape".)
        requestedOrientation =
            if (resources.configuration.smallestScreenWidthDp >= 600)
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        // The scorer is handheld for a whole game on top of live video — keep the screen on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Multi-network: keep cellular available + route YouTube over it when the (Mevo) Wi-Fi has no
        // internet, so you can receive the camera over Wi-Fi AND reach YouTube over cell at once.
        com.libertyclerk.allstarslive.net.NetworkRouter.start(this)
        // Restore the chosen capture mode (external camera vs this device's own camera).
        val camPrefs = getSharedPreferences("allstars", android.content.Context.MODE_PRIVATE)
        com.libertyclerk.allstarslive.ingest.RtmpHub.captureMode =
            camPrefs.getString("capture_mode", com.libertyclerk.allstarslive.ingest.RtmpHub.MODE_EXTERNAL)
                ?: com.libertyclerk.allstarslive.ingest.RtmpHub.MODE_EXTERNAL
        com.libertyclerk.allstarslive.ingest.RtmpHub.lensBack = camPrefs.getBoolean("lens_back", true)
        // Bring the camera link up at launch so Go Live works from any tab (not just Video).
        // Only the external-camera (RTMP receiver) path runs as a background service; the
        // device-camera pipeline starts when the Video screen opens or on Go Live.
        if (com.libertyclerk.allstarslive.ingest.RtmpHub.captureMode == com.libertyclerk.allstarslive.ingest.RtmpHub.MODE_EXTERNAL) {
            com.libertyclerk.allstarslive.ingest.RtmpReceiverService.start(this, 1935)
        }
        // Mic for the broadcast's audio track (YouTube needs audio to go live). Silence
        // is the fallback if denied, but real game sound is better — ask once.
        val needAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
        val needNotif = android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        val ask = buildList {
            if (needAudio) add(android.Manifest.permission.RECORD_AUDIO)
            if (needNotif) add("android.permission.POST_NOTIFICATIONS")
        }
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
        // Push notifications: create the channel + init Firebase so the web's topic subscriptions work.
        com.libertyclerk.allstarslive.push.Push.ensureInit(applicationContext)
        // Immersive: hide the status bar + nav/taskbar so the broadcast app is full-bleed.
        // Swipe from an edge to reveal them transiently; onWindowFocusChanged re-hides.
        hideSystemBars()

        setContent {
            AllStarsLiveTheme {
                // Branded splash: hold the A logo briefly, then fade to the app (the system
                // launch splash already shows the same logo on the dark bg, so it's seamless).
                var showSplash by rememberSaveable { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1200); showSplash = false }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                // Created once + kept alive so a config change doesn't reload a live game.
                val scorerWeb = androidx.compose.runtime.remember { createScorerWebView(ctx) }
                // Camera & streaming setup overlay — opened from the web's Settings gear (native only).
                val showVideo by AppUi.showVideo.collectAsStateWithLifecycle()

                // Native Google sign-in (the WebView blocks Google's OAuth). We get a Google ID
                // token via GoogleSignIn, then hand it to the web's Firebase (signInWithCredential).
                val googleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    try {
                        val acct = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
                        val tok = acct?.idToken
                        if (tok != null) scorerWeb.evaluateJavascript("window.__googleCredential && window.__googleCredential(" + org.json.JSONObject.quote(tok) + ")", null)
                        else scorerWeb.evaluateJavascript("window.__googleFail && window.__googleFail('No token — is the app SHA-1 added in Firebase?')", null)
                    } catch (e: Exception) {
                        scorerWeb.evaluateJavascript("window.__googleFail && window.__googleFail(" + org.json.JSONObject.quote("Google sign-in failed (" + (e.message ?: "error") + ")") + ")", null)
                    }
                }
                LaunchedEffect(Unit) {
                    AppUi.googleSignIn.collect {
                        try {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestIdToken(WEB_CLIENT_ID).requestEmail().build()
                            val client = GoogleSignIn.getClient(this@MainActivity, gso)
                            client.signOut()   // always show the account chooser
                            googleLauncher.launch(client.signInIntent)
                        } catch (e: Exception) {
                            scorerWeb.evaluateJavascript("window.__googleFail && window.__googleFail('Could not start Google sign-in')", null)
                        }
                    }
                }

                // Web invite field → pick a contact's EMAIL. ACTION_PICK on the Email CONTENT_URI
                // grants one-time read of just the chosen row, so NO READ_CONTACTS permission is
                // needed. The picked address is handed back to the web (window.__contactPicked).
                val contactLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.data
                    if (uri != null) {
                        runCatching {
                            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS), null, null, null)?.use { c ->
                                if (c.moveToFirst()) {
                                    val email = c.getString(0)
                                    if (!email.isNullOrBlank()) {
                                        scorerWeb.evaluateJavascript("window.__contactPicked && window.__contactPicked(" + org.json.JSONObject.quote(email) + ")", null)
                                    }
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    AppUi.pickContact.collect {
                        runCatching {
                            contactLauncher.launch(
                                Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI),
                            )
                        }
                    }
                }

                // OS back: close the camera overlay first; otherwise ask the web page
                // (window.appBack) to pop a screen; only at the home screen do we send the app to
                // the background (never destroy it).
                BackHandler {
                    if (showVideo) { AppUi.setShowVideo(false) }
                    else scorerWeb.evaluateJavascript("(window.appBack && window.appBack()) ? true : false") { r ->
                        if (r != "true") this@MainActivity.moveTaskToBack(true)
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    // The app IS the web scorer, full-screen — no native tabs (matches the web/PWA).
                    GameScorerScreen(scorerWeb)

                    // Camera & streaming setup — native-only, opened from the web Settings gear.
                    AnimatedVisibility(visible = showVideo, enter = fadeIn(animationSpec = tween(200)), exit = fadeOut(animationSpec = tween(200))) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF0B0E13))) {
                            VideoTab()
                            Button(
                                onClick = { AppUi.setShowVideo(false) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1A2233), contentColor = Color.White),
                                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp),
                            ) { Text("‹ Done", fontWeight = FontWeight.Bold) }
                        }
                    }

                    // App-level "Start game stream" dialog — raised from the Video tab
                    // button OR the Game-page web button, both via Broadcast.requestDialog().
                    val showGoLive by Broadcast.showDialog.collectAsStateWithLifecycle()
                    val bcast by Broadcast.state.collectAsStateWithLifecycle()
                    if (showGoLive) {
                        GoLiveDialog(
                            initialTitle = bcast.title,
                            onStart = { t, p -> Broadcast.goLive(ctx, t, p); Broadcast.dismissDialog() },
                            onCancel = { Broadcast.dismissDialog() },
                        )
                    }

                    // Confirm before ending a live broadcast.
                    val showStop by Broadcast.showStopConfirm.collectAsStateWithLifecycle()
                    if (showStop) {
                        ConfirmDialog(
                            title = "End the broadcast?",
                            message = "Fans will stop seeing the game on YouTube.",
                            confirmLabel = "End broadcast",
                            onConfirm = { Broadcast.stop(); Broadcast.dismissStop() },
                            onCancel = { Broadcast.dismissStop() },
                        )
                    }

                    // Branded splash overlay — turf background, a near-full-screen A, wordmark at
                    // the bottom; fades out into the app.
                    AnimatedVisibility(visible = showSplash, exit = fadeOut(animationSpec = tween(450))) {
                        // jford, 2026-07-08: "all backgrounds should have the blue turf. should never
                        // have a solid navy or the striped navy" — a prior fix (72f9389) deliberately
                        // made this solid navy instead of turf, reasoning it avoided "a jarring second
                        // splash" flickering between the navy OS splash and a turf screen. Overridden:
                        // every background in the app is turf now, full stop, this one included. Navy
                        // Box stays only as the base underneath (rare-aspect-ratio fallback — Crop
                        // fills the frame in practice), same pattern as GameScorerScreen's backdrop.
                        Box(Modifier.fillMaxSize().background(Color(0xFF11203A))) {
                            Image(
                                painter = painterResource(R.drawable.splash_turf),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            Image(
                                // splash_logo.png is a square APP-ICON asset — it has its own diagonal-
                                // striped background baked in, not transparent, so it showed as a
                                // mismatched striped patch behind the star here. splash_icon is the
                                // actual transparent-background mark (same one the OS splash above
                                // correctly uses via windowSplashScreenAnimatedIcon in themes.xml) —
                                // but unlike splash_logo (star filled its square edge-to-edge),
                                // splash_icon has generous transparent padding around the star (an
                                // icon safe-zone), so the star itself renders noticeably smaller at
                                // the same width fraction. Bumped 0.7 -> 0.92 to compensate.
                                painter = painterResource(R.mipmap.splash_icon),
                                contentDescription = "All-Stars Live",
                                modifier = Modifier.fillMaxWidth(0.92f).align(Alignment.Center),
                                contentScale = ContentScale.Fit,
                            )
                            Row(
                                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 54.dp),
                            ) {
                                Text("ALL-STARS ", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = 2.sp)
                                Text("LIVE", color = Color(0xFFA3E635), fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = 2.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Re-hide bars after dialogs / a transient swipe-reveal — BUT NOT while the soft
        // keyboard is up. Re-hiding during IME causes a relayout that bounces the keyboard
        // closed (the lineup/name fields couldn't be typed into).
        val imeUp = ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (!imeUp) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/** Generic confirm dialog (e.g. ending the broadcast). */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC05080C)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(24.dp)
                .background(Color(0xFF141A22), RoundedCornerShape(16.dp))
                .padding(22.dp)
                .clickable {},   // swallow taps so tapping the card doesn't cancel
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFF9AA0A6), fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFF9AA0A6)) }
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B5C), contentColor = Color.White),
                ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Video tab: live camera, or a test pattern (for streaming/recording without the camera).
 *  The mode switch lives INSIDE each screen (a top Row toggle wouldn't render reliably
 *  above the SurfaceView), so each screen offers a button to jump to the other. */
@Composable
private fun VideoTab() {
    var mode by rememberSaveable { mutableStateOf(0) }   // 0 = Camera, 1 = Test pattern
    Box(Modifier.fillMaxSize()) {
        if (mode == 0) SrtIngestScreen(onUseTestPattern = { mode = 1 })
        else CompositorTestScreen(onUseCamera = { mode = 0 })
    }
}
