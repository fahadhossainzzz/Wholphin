package com.github.damontecres.wholphin.ui.playback.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.formatDuration
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.time.Duration.Companion.seconds

@Composable
fun TrickplayPreview(
    seekProgressMs: Long,
    trickplayInfo: TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        if (trickplayInfo != null) {
            val tilesPerImage = trickplayInfo.tileWidth * trickplayInfo.tileHeight
            val index =
                (seekProgressMs / trickplayInfo.interval).toInt() / tilesPerImage
            val imageUrl = remember(index) { trickplayUrlFor(index) }

            if (imageUrl != null) {
                SeekPreviewImage(
                    modifier = Modifier,
                    previewImageUrl = imageUrl,
                    seekProgressMs = seekProgressMs,
                    trickPlayInfo = trickplayInfo,
                )
            }
        }
        val resources = LocalResources.current
        val seekProgressSec = seekProgressMs / 1000L
        val seekText = remember(seekProgressSec) { resources.formatDuration(seekProgressSec.seconds) }
        // NoBars: Netflix style: one bold time right under the preview
        Text(
            text = seekText,
            color = Color.White,
            style =
                MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 8f),
                ),
        )
    }
}
