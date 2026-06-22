package com.libertyclerk.allstarslive.scorer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libertyclerk.allstarslive.stream.Broadcast

/**
 * M4: the Game tab hosts the existing web scorer (bundled into the APK assets at
 * build time from reference/web-scoring/). One codebase; works offline at the field.
 * The WebView instance is created once and kept alive across tab switches so an
 * in-progress game isn't lost when the operator peeks at the Video tab.
 *
 * The web ↔ app bridge ([ScorerBridge], exposed as `window.AllStars`) lets the
 * Game-page "Start game stream" button raise the same native Go Live dialog and the
 * monitor auto-load the broadcast — all driven by the shared [Broadcast] state.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createScorerWebView(context: Context): WebView {
    WebView.setWebContentsDebuggingEnabled(true)
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true          // team DB / season stats / settings persist
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        setBackgroundColor(0xFF10141A.toInt())     // match the scorer's dark field bg
        addJavascriptInterface(ScorerBridge(context.applicationContext), "AllStars")
        webViewClient = WebViewClient()            // keep navigation in the WebView
        loadUrl("file:///android_asset/scorer/scoring-controller.html")
    }
}

/** Methods callable from the web scorer's JS as `window.AllStars.*`. */
private class ScorerBridge(private val appContext: Context) {
    private val main = Handler(Looper.getMainLooper())

    /** Lets the web detect it's running inside the tablet app. */
    @JavascriptInterface
    fun isApp(): Boolean = true

    /** "Start game stream" → raise the native name/privacy dialog (same as the Video tab). */
    @JavascriptInterface
    fun requestGoLive() { main.post { Broadcast.requestDialog() } }

    /** "End broadcast" from the Game page. */
    @JavascriptInterface
    fun stopStream() { main.post { Broadcast.stop() } }
}

@Composable
fun GameScorerScreen(webView: WebView) {
    // Mirror the shared broadcast state into the web monitor: set the video id (so the
    // player auto-loads) and the phase (so the page shows Start vs End). Same source of
    // truth as the Video tab — going live anywhere reflects here.
    val bcast by Broadcast.state.collectAsStateWithLifecycle()
    LaunchedEffect(bcast.phase, bcast.videoId) {
        val vid = bcast.videoId ?: ""
        webView.evaluateJavascript("window.__bcast && window.__bcast('${bcast.phase.name}','$vid')", null)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)   // re-attach the kept-alive instance
            webView
        },
    )
}
