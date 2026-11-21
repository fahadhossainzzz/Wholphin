package com.github.damontecres.wholphin.ui.playback.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.formatDuration
import com.github.damontecres.wholphin.ui.playback.TrickplayCache
import org.jellyfin.sdk.model.api.TrickplayInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

/**
 * The overlay shown during D-Pad seeking in trickplay mode, laid out like Netflix's: the paused picture lightly
 * dimmed; a filmstrip across the screen (each picture one-sixth of the screen wide, the thumbnail's own size) with
 * a fixed white frame in the centre and the pictures (one press apart) sliding under it as you move; whatever is in
 * the frame is where OK plays from. Under it a thin white bar with the elapsed and remaining time, and the keys.
 */
@Composable
fun DpadSeekOverlay(
    player: Player,
    seekPositionMs: Long,
    trickplayInfo: TrickplayInfo?,
    trickplayUrlFor: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    val durationMs = player.duration.coerceAtLeast(0L)
    val resources = LocalResources.current
    val elapsedText = remember(seekPositionMs / 1000) { resources.formatDuration((seekPositionMs / 1000).seconds) }
    val remainingSec = ((durationMs - seekPositionMs) / 1000L).coerceAtLeast(0L)
    val remainingText = remember(remainingSec) { "-" + resources.formatDuration(remainingSec.seconds) }

    // one press = the distance of the last move (10 s, more while the button is held and seeking speeds up)
    var step by remember { mutableLongStateOf(10_000L) }
    var last by remember { mutableLongStateOf(seekPositionMs) }
    var forward by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(seekPositionMs) {
        val d = seekPositionMs - last
        if (abs(d) >= 1_000L) {
            step = abs(d)
            forward = d > 0
        }
        last = seekPositionMs
        TrickplayCache.prepare(seekPositionMs, step, forward, durationMs)
    }
    // NoBars: one smooth position (in film time) drives both the strip and the bar, so they glide together;
    // far jumps (more than 20 presses) are shown at once
    val shownMs = remember { Animatable(seekPositionMs.toFloat()) }
    LaunchedEffect(seekPositionMs) {
        val target = seekPositionMs.toFloat()
        val behind = target - shownMs.value
        if (abs(behind) > 20f * step) {
            shownMs.snapTo(target)
        } else {
            // NoBars: never trail more than one picture behind (holding the button repeats faster than the
            // glide), so the picture under the frame is always the current one
            if (abs(behind) > step) shownMs.snapTo(target - kotlin.math.sign(behind) * step)
            shownMs.animateTo(target, tween(200, easing = FastOutSlowInEasing))
        }
    }
    val slide = ((seekPositionMs - shownMs.value) / step).coerceIn(-1.5f, 1.5f) // in pictures; glides to 0
    val progress = if (durationMs > 0) (shownMs.value / durationMs).coerceIn(0f, 1f) else 0f
    val ready by TrickplayCache.version.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { TrickplayCache.logSeekStats() } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
        ) {
            if (trickplayInfo != null && trickplayInfo.width > 0 && trickplayInfo.height > 0) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val fullWidth = maxWidth
                    val w = fullWidth / 6
                    val h = w * 9f / 16f
                    val gap = 8.dp
                    val frame = 6.dp // the fixed white frame sits this much outside the picture
                    Box(modifier = Modifier.fillMaxWidth().height(h + frame * 2)) {
                        // all pictures the same size, sliding under the fixed frame
                        // always fill the whole width around where the strip is now, whatever it is doing
                        val first = kotlin.math.floor(-4f - slide).toInt()
                        val last = kotlin.math.ceil(4f - slide).toInt()
                        for (j in first..last) {
                            val time = seekPositionMs + j * step
                            if (time < 0 || (durationMs > 0 && time > durationMs)) continue
                            val centreX = fullWidth / 2 + (w + gap) * (j + slide)
                            FilmstripPicture(
                                picture = if (ready >= 0) TrickplayCache.picture(time) else null,
                                width = w,
                                height = h,
                                modifier = Modifier.offset(x = centreX - w / 2, y = frame),
                            )
                        }
                        // NoBars: the white frame never moves: whatever settles inside it is where OK plays from
                        Box(
                            modifier =
                                Modifier
                                    .offset(x = fullWidth / 2 - w / 2 - frame, y = 0.dp)
                                    .size(w + frame * 2, h + frame * 2)
                                    .border(3.dp, Color.White, RoundedCornerShape(10.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(42.dp))
            }
            // the thin white bar, elapsed time on the left and remaining time on the right
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(0.74f),
            ) {
                Text(elapsedText, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Canvas(modifier = Modifier.weight(1f).height(14.dp)) {
                    val y = size.height / 2
                    val hh = 3.dp.toPx()
                    val r = CornerRadius(hh / 2, hh / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y - hh / 2), Size(size.width, hh), r)
                    drawRoundRect(Color.White, Offset(0f, y - hh / 2), Size(size.width * progress, hh), r)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(size.width * progress, y))
                }
                Text(remainingText, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = "◀ ▶ move  ·  OK play from here  ·  Back cancel",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * One picture of the filmstrip, filling a 16:9 box with rounded corners. It is grey only before any preview is
 * ready (the first second of a film), then fades in; soft pictures turn sharp in place.
 */
@Composable
private fun FilmstripPicture(
    picture: com.github.damontecres.wholphin.ui.playback.SeekPicture?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (picture != null) 1f else 0f, tween(120), label = "fade")
    Box(
        modifier =
            modifier
                .size(width, height)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.12f)),
    ) {
        if (picture != null) {
            Canvas(modifier = Modifier.size(width, height)) {
                // cover the box, centred (wide films lose a little at the sides)
                val sw = picture.srcSize.width
                val sh = picture.srcSize.height
                val s = max(size.width / sw, size.height / sh)
                val dw = sw * s
                val dh = sh * s
                drawImage(
                    image = picture.image,
                    srcOffset = picture.srcOffset,
                    srcSize = picture.srcSize,
                    dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                    alpha = alpha,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
        }
    }
}
