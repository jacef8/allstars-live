package com.libertyclerk.allstarslive

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny shared UI state.
 *
 * [inGame] is set by the web scorer (via the JS bridge) when an actual game/console is on
 * screen — MainActivity uses it to hide the bottom tab bar during a game.
 *
 * [previewRect] is the on-screen rectangle (in dp, relative to the scorer screen) where
 * the web scorer wants the LIVE camera shown — reported by the web's monitor region so the
 * native [com.libertyclerk.allstarslive.ingest.RtmpHub] preview can be placed there. This
 * lets the operator aim the camera and confirm the feed right on the scorer page (and
 * replaces the fragile in-app YouTube embed, so no more error 153).
 */
object AppUi {
    private val _inGame = MutableStateFlow(false)
    val inGame: StateFlow<Boolean> = _inGame
    fun setInGame(v: Boolean) { _inGame.value = v }

    /** Camera & streaming setup overlay — opened from the web's Settings gear (native only). */
    private val _showVideo = MutableStateFlow(false)
    val showVideo: StateFlow<Boolean> = _showVideo
    fun setShowVideo(v: Boolean) { _showVideo.value = v }

    /** Web "Continue with Google" → ask the native side to run the Credential/GoogleSignIn flow. */
    private val _googleSignIn = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val googleSignIn: SharedFlow<Unit> = _googleSignIn
    fun requestGoogleSignIn() { _googleSignIn.tryEmit(Unit) }

    /** Monitor rectangle in dp (CSS px ≈ dp at default WebView scale), or null = hide preview. */
    data class PreviewRect(val x: Float, val y: Float, val w: Float, val h: Float)

    private val _previewRect = MutableStateFlow<PreviewRect?>(null)
    val previewRect: StateFlow<PreviewRect?> = _previewRect
    fun setPreviewRect(r: PreviewRect?) { _previewRect.value = r }
}
