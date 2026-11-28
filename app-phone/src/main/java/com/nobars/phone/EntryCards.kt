package com.nobars.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.nobars.data.ImdbVotesStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore.Entry
import java.util.Locale

val ImdbYellow = Color(0xFFF5C518)
val TmdbBlue = Color(0xFF01B4E4)
val Tomato = Color(0xFFFA320A)
val GoodGreen = Color(0xFFA5D6A7)
val WarnRed = Color(0xFFEF9A9A)
val StreamGold = Color(0xFFFFE082)
val FreeOrange = Color(0xFFFFCC80)
val DateBlue = Color(0xFF90CAF9)

fun rating(r: Float) = String.format(Locale.US, "%.1f", r)

fun votes(v: Int?) = v?.let { " (" + ImdbVotesStore.short(it) + ")" } ?: ""

@Composable
fun NbChip(
    text: String,
    bg: Color,
    fg: Color,
) = Text(
    text,
    color = fg,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    maxLines = 1,
    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
)

/** The TV's rating chips: IMDb (or TMDB) with votes, 🍅, Rating 8+, rank. */
@Composable
fun EntryChips(e: Entry) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        e.imdbRating?.let { NbChip("IMDb " + rating(it) + votes(e.imdbVotes), ImdbYellow, Color.Black) }
            ?: e.tmdbRating?.let { NbChip("TMDB " + rating(it) + votes(e.tmdbVotes), TmdbBlue, Color.Black) }
        e.rt?.let { NbChip("🍅 $it%", Tomato.copy(alpha = 0.85f), Color.White) }
            ?: e.pop?.let { NbChip("🍿 $it%", Color(0xFF8D6E63), Color.White) }
        if ((e.imdbRating ?: 0f) >= 8f) NbChip("Rating 8+", Color(0xFF7E57C2).copy(alpha = 0.75f), Color.White)
        if (e.rank != null && e.note != null) NbChip("#${e.rank}" + (e.move?.takeIf { it != "=" }?.let { " $it" } ?: ""), Color(0xFFE50914), Color.White)
    }
}

/** A catalogue poster: rank number, IMDb band at the foot, "in your library" tick. */
@Composable
fun EntryCard(
    e: Entry,
    width: Dp,
    onClick: () -> Unit,
) {
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = e.poster, contentDescription = e.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            e.rank?.let { r ->
                Text(
                    "$r",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE50914)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            if (StreamingStore.libraryId(e) != null) Tick(Modifier.align(Alignment.TopEnd))
            val band = (e.imdbRating ?: e.tmdbRating)?.let { "★ " + rating(it) + votes(e.imdbVotes ?: e.tmdbVotes) }
            if (band != null) {
                Text(
                    band,
                    color = ImdbYellow,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        val sub = listOfNotNull(e.badge ?: e.year?.toString(), if (e.series) "Series" else null).joinToString(" · ")
        Text(sub, maxLines = 1, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A titled row of catalogue posters with an optional "See all". */
@Composable
fun EntryShelf(
    title: String,
    items: List<Entry>,
    count: Int = items.size,
    onSeeAll: (() -> Unit)? = null,
    onOpen: (Entry) -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text("See all ›") } else Box(Modifier.width(12.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items.take(30), key = { it.key() }) { e -> EntryCard(e, 112.dp) { onOpen(e) } }
        }
    }
}
