package com.libertyclerk.allstarslive.scorer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.Base64
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.libertyclerk.allstarslive.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libertyclerk.allstarslive.AppUi
import com.libertyclerk.allstarslive.ingest.IngestState
import com.libertyclerk.allstarslive.ingest.RtmpHub
import com.libertyclerk.allstarslive.ingest.buildScorebugOverlay
import com.libertyclerk.allstarslive.stream.Broadcast

/**
 * M4: the Game tab hosts the existing web scorer (bundled into the APK assets at
 * build time from reference/web-scoring/). One codebase; works offline at the field.
 *
 * The web ↔ app bridge ([ScorerBridge], exposed as `window.AllStars`) lets the Game-page
 * "Start game stream" button raise the native Go Live dialog. The WebView is rendered
 * TRANSPARENT over a native [SurfaceView]: the web reports where its monitor region is
 * ([ScorerBridge.setPreviewRect]) and we place the live camera preview there — so the
 * operator can aim the camera and confirm the feed right on the scorer page, before going
 * live. (This replaces the old in-app YouTube embed, which threw error 153 for private
 * broadcasts.)
 */
// Load the LIVE web app (so web changes reach the app with no rebuild, and Firebase/sign-in work —
// they can't run from file://). The service worker (sw.js) caches the shell for offline use at the
// field after the first online load.
private const val APP_HOST = "web-production-77d34.up.railway.app"
private const val APP_URL = "https://web-production-77d34.up.railway.app/scoring-controller.html"

@SuppressLint("SetJavaScriptEnabled")
fun createScorerWebView(context: Context): WebView {
    WebView.setWebContentsDebuggingEnabled(true)
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true          // team DB / season stats / settings persist
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT   // service worker handles offline
        // Drop the "; wv" WebView marker so Google OAuth / Firebase are happier with the user agent.
        settings.userAgentString = settings.userAgentString?.replace("; wv", "")
        setBackgroundColor(Color.TRANSPARENT)       // see-through so the native camera preview shows behind
        addJavascriptInterface(ScorerBridge(context.applicationContext), "AllStars")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                // Keep navigation to OUR app (Railway) inside the WebView; open everything else
                // (YouTube, mailto, sms, tel, other sites) in the native app/browser.
                if ((u.scheme == "http" || u.scheme == "https") && u.host.equals(APP_HOST, true)) {
                    return false
                }
                if (u.scheme in setOf("http", "https", "mailto", "sms", "smsto", "tel")) {
                    runCatching {
                        view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    return true
                }
                return false
            }
        }
        loadUrl(APP_URL)
    }
}

/** Methods callable from the web scorer's JS as `window.AllStars.*`. */
private class ScorerBridge(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())

    // Team logos for the burned-in scorebug, decoded from the data URLs the web sends.
    @Volatile private var awayLogo: Bitmap? = null
    @Volatile private var homeLogo: Bitmap? = null
    private fun decodeDataUrl(s: String?): Bitmap? {
        if (s.isNullOrBlank()) return null
        return try {
            val b64 = if (s.contains("base64,")) s.substringAfter("base64,") else s
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    /** Web pushes the two teams' logos (data URLs) when they change; drawn in the scorebug. */
    @JavascriptInterface
    fun setTeamLogos(away: String?, home: String?) {
        awayLogo = decodeDataUrl(away)
        homeLogo = decodeDataUrl(home)
    }

    /** Lets the web detect it's running inside the tablet app. */
    @JavascriptInterface
    fun isApp(): Boolean = true

    /** "Start game stream" → raise the native name/privacy dialog (same as the Video tab). */
    @JavascriptInterface
    fun requestGoLive() { main.post { Broadcast.requestDialog() } }

    /** Settings → "Camera & stream setup" → open the native camera/streaming screen (overlay). */
    @JavascriptInterface
    fun openVideo() { main.post { AppUi.setShowVideo(true) } }

    /** Web "Continue with Google" → run the native Google sign-in (WebView OAuth is blocked). */
    @JavascriptInterface
    fun googleSignIn() { AppUi.requestGoogleSignIn() }

    /** Invite field "Contacts" button → open the native contact picker; the chosen email is
     *  handed back to the web via window.__contactPicked(value). No permission needed. */
    @JavascriptInterface
    fun pickContact() { AppUi.requestPickContact() }

    /** "End broadcast" from the Game page — asks for confirmation first. */
    @JavascriptInterface
    fun stopStream() { main.post { Broadcast.requestStop() } }

    /** The web tells us when a game/console is on screen (vs the menus). */
    @JavascriptInterface
    fun setInGame(inGame: Boolean) {
        AppUi.setInGame(inGame)
        if (!inGame) {
            AppUi.setPreviewRect(null)                              // no console → no preview
            RtmpHub.videoCompositor?.setOverlay(null)               // hide the scorebug off-console
        }
    }

    /** The web reports where (in CSS px ≈ dp) it wants the live camera shown. */
    @JavascriptInterface
    fun setPreviewRect(x: Float, y: Float, w: Float, h: Float, show: Boolean) {
        main.post {
            AppUi.setPreviewRect(if (show && w > 0 && h > 0) AppUi.PreviewRect(x, y, w, h) else null)
        }
    }

    /**
     * Live score from the web scorer — rendered to a scorebug bitmap and laid over the
     * camera feed (preview AND the YouTube broadcast) via the persistent compositor.
     */
    @JavascriptInterface
    fun setScore(
        away: String, home: String, awayScore: Int, homeScore: Int,
        inning: Int, topHalf: Boolean, balls: Int, strikes: Int, outs: Int,
    ) {
        val comp = RtmpHub.videoCompositor ?: return
        val bmp = runCatching {
            buildScorebugOverlay(1280, 720, away, home, awayScore, homeScore, inning, topHalf, balls, strikes, outs, awayLogo, homeLogo)
        }.getOrNull() ?: return
        comp.setOverlay(bmp)
    }

    /** Open a link (e.g. the YouTube watch page) in the external app/browser. */
    @JavascriptInterface
    fun openExternal(url: String) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
fun GameScorerScreen(webView: WebView) {
    // Mirror the shared broadcast state into the web monitor (Start vs End, video id).
    val bcast by Broadcast.state.collectAsStateWithLifecycle()
    LaunchedEffect(bcast.phase, bcast.videoId) {
        val vid = bcast.videoId ?: ""
        webView.evaluateJavascript("window.__bcast && window.__bcast('${bcast.phase.name}','$vid')", null)
    }
    // Tell the web whether the camera is delivering frames (drives the monitor's
    // "● camera live" vs "waiting for camera" status — the operator's connection check).
    val stats by RtmpHub.stats.collectAsStateWithLifecycle()
    val camLive = stats.state == IngestState.PLAYING
    LaunchedEffect(camLive) {
        webView.evaluateJavascript("window.__cam && window.__cam(${if (camLive) "true" else "false"})", null)
    }

    // Where the web wants the live camera shown (its monitor region, in dp).
    val rect by AppUi.previewRect.collectAsStateWithLifecycle()

    // Dark backdrop matching the scorer theme: the WebView is transparent, so this shows
    // everywhere except the monitor rect (where the camera TextureView sits).
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0E13))) {
        // Blue turf behind the transparent WebView so the native app matches the web look
        // (the web body is transparent in the app). Camera + web UI draw on top of this.
        Image(
            painter = painterResource(R.drawable.splash_turf),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x99070B13)))  // scrim for contrast
        // Native camera preview, BEHIND the transparent WebView, at the web's monitor rect.
        // TextureView (not SurfaceView) so it composites in the normal view hierarchy and
        // reliably shows through the transparent WebView, with web controls drawn on top.
        rect?.let { r ->
            AndroidView(
                modifier = Modifier.absoluteOffset(x = r.x.dp, y = r.y.dp).size(width = r.w.dp, height = r.h.dp),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) =
                                RtmpHub.attachPreview(Surface(st))
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                RtmpHub.detachPreview(); return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)   // re-attach the kept-alive instance
                webView
            },
        )
    }
}
