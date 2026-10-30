package com.github.damontecres.wholphin.ui.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * NoBars: some TVs (e.g. Hisense/MediaTek) draw Dolby Vision video in their own Dolby engine and ignore
 * an app's zoom. When the picture needs zooming, Dolby Vision decoders are skipped so ExoPlayer falls back
 * to the regular HEVC decoder, which shows the HDR10 base layer of a Dolby Vision profile 8 file and zooms
 * normally. Profile 5 (no HDR10 base layer) has no fallback decoder and keeps playing as Dolby Vision.
 */
object ZoomPolicy {
    /** Zoom steps for the remote's up/down buttons. */
    const val STEP = 1.01f
    const val MIN_ZOOM = 0.85f
    const val MAX_ZOOM = 2.0f

    @Volatile
    var avoidDolbyVision: Boolean = false

    /** Wraps a codec selector so Dolby Vision decoders are hidden while [avoidDolbyVision] is set. */
    fun wrap(selector: MediaCodecSelector): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            if (avoidDolbyVision && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                emptyList()
            } else {
                selector.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            }
        }

    /** Whether the video currently playing is being decoded as Dolby Vision. */
    fun isPlayingDolbyVision(player: Player): Boolean =
        player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_VIDEO &&
                (0 until group.length).any { i ->
                    group.isTrackSelected(i) && group.getTrackFormat(i).sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
                }
        }

    /** Re-creates the video decoder at the current position so a changed [avoidDolbyVision] takes effect. */
    fun restartVideoDecoder(player: Player) {
        val params = player.trackSelectionParameters
        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
    }
}
