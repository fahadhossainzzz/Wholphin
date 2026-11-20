package com.github.damontecres.wholphin.ui.playback.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.formatDuration
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.time.Duration.Companion.seconds

/** NoBars: Netflix's red. */
private val SeekRed = Color(0xFFE50914)

/**
 * The overlay shown during D-Pad seeking in trickplay mode, Netflix style: the paused picture darkened towards the
 * bottom, a small preview (one-sixth of the screen wide) with rounded corners and the time under it, following a
 * red bar with a red dot; the title above the bar; the keys and the remaining time under it.
 */
@Composable
fun DpadSeekOverlay(
    player: Player,
    seekPositionMs: Long,
    trickplayInfo: TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val durationMs = player.duration.coerceAtLeast(0L)
    val progress =
        if (durationMs > 0) (seekPositionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
    val buffered =
        if (durationMs > 0) (player.bufferedPosition.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
    val resources = LocalResources.current
    val remainingSec = ((durationMs - seekPositionMs) / 1000L).coerceAtLeast(0L)
    val remainingText = remember(remainingSec) { "-" + resources.formatDuration(remainingSec.seconds) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(modifier = modifier.padding(top = 140.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(0.35f)
                                .padding(bottom = 12.dp),
                    )
                }
                TrickplayPreview(
                    seekProgressMs = seekPositionMs,
                    trickplayInfo = trickplayInfo,
                    trickplayUrlFor = trickplayUrlFor,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .offsetByPercent(xPercentage = progress)
                            .padding(bottom = 10.dp),
                )
            }
            // the red bar: loaded part light grey, watched part red, a red dot with a white ring
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp),
            ) {
                val y = size.height / 2
                val h = 4.dp.toPx()
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - h / 2), Size(size.width, h), r)
                drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y - h / 2), Size(size.width * buffered, h), r)
                drawRoundRect(SeekRed, Offset(0f, y - h / 2), Size(size.width * progress, h), r)
                val x = (size.width * progress).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
                drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(x, y))
                drawCircle(SeekRed, radius = 7.dp.toPx(), center = Offset(x, y))
            }
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(
                    text = "◀ ▶ move  ·  OK play from here  ·  Back cancel",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )
                Text(text = remainingText, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp)
            }
        }
    }
}
