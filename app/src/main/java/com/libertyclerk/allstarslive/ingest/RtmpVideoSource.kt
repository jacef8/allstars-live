package com.libertyclerk.allstarslive.ingest

import android.content.Context
import android.view.Surface
import com.libertyclerk.allstarslive.gl.VideoCompositor
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin UI adapter over [RtmpHub]. The actual pipeline (RTMP receiver → decoder →
 * compositor) lives in the foreground service so it keeps running while the operator
 * is on another tab or in the Mevo app. This just starts the service and attaches /
 * detaches the on-screen preview surface as the Video tab comes and goes.
 */
class RtmpVideoSource(private val context: Context) : VideoSource {

    override val stats: StateFlow<VideoStats> get() = RtmpHub.stats

    /** The live compositor (for Go Live / record / scorebug overlay). */
    val videoCompositor: VideoCompositor? get() = RtmpHub.videoCompositor

    /** Port the Mevo should publish to (rtmp://<tablet-ip>:[port]/live). */
    var port: Int = 1935

    override fun start(url: String, surface: Surface) {
        RtmpHub.port = port
        // Pipeline runs in the FGS so it survives backgrounding; preview is optional.
        RtmpReceiverService.start(context, port)
        RtmpHub.attachPreview(surface)
    }

    /** Leaving the screen detaches the preview only — the pipeline keeps streaming. */
    override fun stop() {
        RtmpHub.detachPreview()
    }

    /** Fully tear down the receiver service — call when the operator is done. */
    fun shutdown() {
        RtmpHub.detachPreview()
        RtmpReceiverService.stop(context)
    }
}
