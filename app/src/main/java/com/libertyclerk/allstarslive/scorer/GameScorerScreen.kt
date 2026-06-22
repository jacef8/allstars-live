package com.libertyclerk.allstarslive.scorer

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * M4: the Game tab hosts the existing web scorer (bundled into the APK assets at
 * build time from reference/web-scoring/). One codebase; works offline at the field.
 * The WebView instance is created once and kept alive across tab switches so an
 * in-progress game isn't lost when the operator peeks at the Video tab.
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
        webViewClient = WebViewClient()            // keep navigation in the WebView
        loadUrl("file:///android_asset/scorer/scoring-controller.html")
    }
}

@Composable
fun GameScorerScreen(webView: WebView) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)   // re-attach the kept-alive instance
            webView
        },
    )
}
