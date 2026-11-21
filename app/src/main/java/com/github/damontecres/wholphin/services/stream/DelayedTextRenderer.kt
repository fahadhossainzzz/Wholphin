package com.github.damontecres.wholphin.services.stream

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer

/**
 * NoBars: subtitle delay for ExoPlayer, done inside the player. ExoPlayer has already read and timed every subtitle
 * line (inside the file, a separate .srt, or picture subtitles) into its buffer, up to minutes ahead; its text
 * renderer decides which lines are on screen at the playback position. This wrapper hands it (position - delay)
 * instead, so the lines simply come later (delay > 0) or earlier (delay < 0). Nothing extra is fetched, it changes
 * live, and the subtitles are still drawn by the player with the usual style.
 */
object SubtitleOffset {
    /** Positive: subtitles appear later. Set from the player screen (remembered per title and track). */
    @Volatile
    var delayUs: Long = 0L
}

@OptIn(UnstableApi::class)
class DelayedTextRenderer(
    private val inner: Renderer,
) : Renderer by inner {
    override fun render(
        positionUs: Long,
        elapsedRealtimeUs: Long,
    ) {
        inner.render(positionUs - SubtitleOffset.delayUs, elapsedRealtimeUs)
    }

    override fun release() = inner.release()
}
