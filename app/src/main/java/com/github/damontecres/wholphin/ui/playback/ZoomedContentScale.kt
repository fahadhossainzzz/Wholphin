package com.github.damontecres.wholphin.ui.playback

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor

/**
 * NoBars: the chosen video scale (Crop, Fit, ...) with the extra zoom from the Zoom panel on top.
 * Pixels stay square; zoom 1 is exactly the chosen scale.
 */
data class ZoomedContentScale(
    val base: ContentScale,
    val zoom: Float,
) : ContentScale {
    override fun computeScaleFactor(
        srcSize: Size,
        dstSize: Size,
    ): ScaleFactor {
        val f = base.computeScaleFactor(srcSize, dstSize)
        return ScaleFactor(f.scaleX * zoom, f.scaleY * zoom)
    }
}
