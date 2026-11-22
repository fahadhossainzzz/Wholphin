package com.github.damontecres.wholphin.ui.util

import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.floor

/**
 * NoBars: scrolling for a row of equal-width posters. A row only scrolls when the focused poster isn't fully on
 * screen, and then always stops with a poster's edge exactly at the row's start margin: no poster is ever left half
 * under the menu strip on the left. [startPx] is the row's start padding, [stridePx] one poster plus the gap.
 */
class WholeItemsScroll(
    private val startPx: Float,
    private val stridePx: Float,
) : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val end = offset + size
        return when {
            // already fully visible (and not under the start margin)
            offset >= startPx - 1f && end <= containerSize + 1f -> 0f
            // going back: the focused poster lands exactly on the start margin
            offset < startPx -> offset - startPx
            // going forward: just far enough to show it, and a poster edge (offset - k * stride) on the start margin
            else -> {
                val needed = end - containerSize
                if (stridePx <= 0f) return needed
                val k = floor((offset - startPx - needed) / stridePx).coerceAtLeast(0f)
                offset - startPx - k * stridePx
            }
        }
    }
}
