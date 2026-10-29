package com.github.damontecres.wholphin.ui.playback

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import java.util.Locale
import kotlin.math.max

/**
 * Zooms past black bars that are baked into the video picture (hardcoded letterboxing).
 *
 * The active picture is assumed to be centered and to have the aspect ratio [pictureAspect]
 * (for example 2.35 for a 2.35:1 film inside a 16:9 file). The video is scaled uniformly so that
 * the active picture covers the whole screen, so pixels stay square and nothing is stretched.
 * On a 16:9 screen the left and right edges of a wider picture are trimmed, like [ContentScale.Crop].
 */
data class RemoveBarsContentScale(
    val pictureAspect: Float,
) : ContentScale {
    override fun computeScaleFactor(
        srcSize: Size,
        dstSize: Size,
    ): ScaleFactor {
        if (srcSize.width <= 0f || srcSize.height <= 0f || pictureAspect <= 0f) {
            return ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        }
        // Height of the active picture inside the source frame; never taller than the frame itself
        val activeHeight = (srcSize.width / pictureAspect).coerceAtMost(srcSize.height)
        val scale = max(dstSize.width / srcSize.width, dstSize.height / activeHeight)
        return ScaleFactor(scale, scale)
    }

    val label: String get() = String.format(Locale.US, "Remove bars (%.2f:1)", pictureAspect)

    companion object {
        /** Jellyfin tag prefix, e.g. a tag "bars:2.35" on an item means its picture is 2.35:1 with baked-in bars */
        const val TAG_PREFIX = "bars:"

        /** Fixed choices offered in the video scale menu */
        val presets = listOf(2.00f, 2.35f, 2.39f).map { RemoveBarsContentScale(it) }

        /** Reads the picture aspect ratio from an item's Jellyfin tags, if it has a valid "bars:" tag */
        fun fromTags(tags: List<String>?): RemoveBarsContentScale? =
            tags
                ?.firstNotNullOfOrNull { tag ->
                    tag
                        .trim()
                        .takeIf { it.startsWith(TAG_PREFIX, ignoreCase = true) }
                        ?.substring(TAG_PREFIX.length)
                        ?.trim()
                        ?.toFloatOrNull()
                }?.takeIf { it in 1.0f..4.0f }
                ?.let { RemoveBarsContentScale(it) }
    }
}
