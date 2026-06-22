package com.libertyclerk.allstarslive.scorer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
        // Keep the scorer (file://) in the WebView; send any real http(s) link (e.g. the
        // YouTube watch page) to the external browser/app so the operator can return via
        // the Android back button instead of being stranded in the WebView.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.scheme == "http" || u.scheme == "https") {
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

    /** The web tells us when a game/console is on screen (vs the menus). */
    @JavascriptInterface
    fun setInGame(inGame: Boolean) { com.libertyclerk.allstarslive.AppUi.setInGame(inGame) }

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
