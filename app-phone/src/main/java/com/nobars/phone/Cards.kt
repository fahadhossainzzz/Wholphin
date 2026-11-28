package com.nobars.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nobars.jellyfin.NbJellyfin
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** "Billions · S7 E8" for episodes, the name otherwise. */
fun BaseItemDto.cardTitle(): String =
    if (type == BaseItemKind.EPISODE) seriesName ?: name.orEmpty() else name.orEmpty()

fun BaseItemDto.cardSubtitle(): String? =
    when (type) {
        BaseItemKind.EPISODE -> {
            val code = listOfNotNull(parentIndexNumber?.let { "S$it" }, indexNumber?.let { "E$it" }).joinToString(" ")
            // leave out names that only repeat the show and number ("Bigg Boss - S20E83")
            val n = name?.takeUnless { it.isBlank() || Regex("S\\d+\\s*E\\d+", RegexOption.IGNORE_CASE).containsMatchIn(it) || seriesName?.let { s -> it.contains(s, true) } == true }
            code + (n?.let { " · $it" } ?: "")
        }
        else -> productionYear?.toString()
    }

fun BaseItemDto.progress(): Float? =
    userData?.playedPercentage?.toFloat()?.div(100f)?.takeIf { it > 0.005f && it < 0.995f }

@Composable
fun PosterCard(
    item: BaseItemDto,
    width: Dp,
    onClick: () -> Unit,
) {
    val lib = NbJellyfin.current.library
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = lib.poster(item), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.userData?.played == true) Tick(Modifier.align(Alignment.TopEnd))
            item.progress()?.let { Bar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(item.cardTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        item.cardSubtitle()?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun WideCard(
    item: BaseItemDto,
    width: Dp,
    onClick: () -> Unit,
) {
    val lib = NbJellyfin.current.library
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = lib.wide(item), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.userData?.played == true) Tick(Modifier.align(Alignment.TopEnd))
            item.progress()?.let { Bar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(item.cardTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        item.cardSubtitle()?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun Bar(
    fraction: Float,
    modifier: Modifier = Modifier,
) = LinearProgressIndicator(
    progress = { fraction },
    modifier = modifier.fillMaxWidth().height(4.dp),
    color = NbRed,
    trackColor = Color(0x88000000),
    drawStopIndicator = {},
    gapSize = 0.dp,
)

@Composable
fun Tick(modifier: Modifier = Modifier) =
    Text(
        "✓",
        color = Color.White,
        fontSize = 12.sp,
        modifier = modifier.padding(4.dp).clip(RoundedCornerShape(50)).background(NbRed).padding(horizontal = 6.dp, vertical = 1.dp),
    )
