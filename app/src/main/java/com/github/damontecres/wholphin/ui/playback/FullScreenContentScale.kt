package com.github.damontecres.wholphin.ui.playback

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import kotlin.math.max
import kotlin.math.min

/**
 * "Full screen (auto)": fills the screen without stretching.
 *
 * [pictureAspect] is the aspect ratio of the real picture when black bars are baked into the video
 * (read from a Jellyfin tag like "bars:2.40"). The video is zoomed uniformly so that picture covers
 * the screen, so pixels stay square; a picture wider than the screen loses some of its left and
 * right edges, like [ContentScale.Crop].
 *
 * Without a tag the whole frame is treated as the picture. Pictures narrower than the screen
 * (e.g. 4:3) are shown whole instead of cutting off their top and bottom.
 */
data class FullScreenContentScale(
    val pictureAspect: Float? = null,
    /** Extra zoom chosen with the remote's up/down buttons (1 = automatic). */
    val zoom: Float = 1f,
) : ContentScale {
    override fun computeScaleFactor(
        srcSize: Size,
        dstSize: Size,
    ): ScaleFactor {
        if (srcSize.width <= 0f || srcSize.height <= 0f || dstSize.width <= 0f || dstSize.height <= 0f) {
            return ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
        }
        val aspect = pictureAspect?.takeIf { it > 0f } ?: (srcSize.width / srcSize.height)
        // Height of the real picture inside the frame; never taller than the frame itself
        val pictureHeight = min(srcSize.height, srcSize.width / aspect)
        val pictureAspectActual = srcSize.width / pictureHeight
        val screenAspect = dstSize.width / dstSize.height
        val scale =
            if (pictureAspectActual < screenAspect * 0.99f) {
                // Narrower than the screen: show it whole
                min(dstSize.width / srcSize.width, dstSize.height / pictureHeight)
            } else {
                max(dstSize.width / srcSize.width, dstSize.height / pictureHeight)
            }
        return ScaleFactor(scale * zoom, scale * zoom)
    }

    companion object {
        /** Jellyfin tag prefix, e.g. "bars:2.40" means a 2.40:1 picture with black bars baked into the video */
        const val TAG_PREFIX = "bars:"

        /** Reads the picture aspect ratio from an item's Jellyfin tags, if it has a valid "bars:" tag */
        fun aspectFromTags(tags: List<String>?): Float? =
            tags
                ?.firstNotNullOfOrNull { tag ->
                    tag
                        .trim()
                        .takeIf { it.startsWith(TAG_PREFIX, ignoreCase = true) }
                        ?.substring(TAG_PREFIX.length)
                        ?.trim()
                        ?.toFloatOrNull()
                }?.takeIf { it in 1.0f..4.0f }
    }
}
