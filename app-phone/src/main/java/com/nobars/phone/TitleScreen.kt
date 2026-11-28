package com.nobars.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.github.damontecres.wholphin.nobars.data.BoxOffice
import com.github.damontecres.wholphin.nobars.data.CriticFallback
import com.github.damontecres.wholphin.nobars.data.ImdbVotesStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.data.WantedStore
import kotlinx.coroutines.launch
import com.nobars.jellyfin.NbJellyfin
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.Locale
import java.util.UUID

private fun runtime(ticks: Long?): String? {
    val min = (ticks ?: return null) / 600_000_000L
    if (min <= 0) return null
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

@Composable
fun TitleScreen(
    id: UUID,
    nav: Nav,
) {
    val lib = NbJellyfin.current.library
    val context = LocalContext.current
    var item by remember(id) { mutableStateOf<BaseItemDto?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var seasons by remember(id) { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var season by remember(id) { mutableIntStateOf(0) }
    var episodes by remember(id) { mutableStateOf<List<BaseItemDto>?>(null) }
    var round by remember(id) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // NoBars: the same card and page the TV shows (ratings, votes, where to watch, cast, crew, trailers, like this)
    var nbEntry by remember(id) { mutableStateOf<StreamingStore.Entry?>(null) }
    var nbPage by remember(id) { mutableStateOf<StreamingStore.TitlePage?>(null) }

    // reload when coming back from the player, so Resume and ticks are current
    LaunchedEffect(id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                val it = lib.item(id)
                item = it
                if (it.type == BaseItemKind.SERIES && seasons.isEmpty()) {
                    seasons = lib.seasons(id)
                    // open the season with the next unwatched episode
                    season = seasons.indexOfFirst { s -> s.userData?.played != true }.coerceAtLeast(0)
                }
                round++
                it.tmdb()?.let { (t, series) ->
                    if (nbEntry == null) nbEntry = StreamingStore.title(t, series)
                    if (nbPage == null) nbPage = StreamingStore.titlePage(t, series)
                }
            } catch (e: Exception) {
                Timber.w(e, "NoBars: title load failed")
                failed = true
            }
        }
    }
    LaunchedEffect(seasons, season, round) {
        val s = seasons.getOrNull(season) ?: return@LaunchedEffect
        episodes =
            try {
                lib.episodes(id, s.id)
            } catch (e: Exception) {
                Timber.w(e, "NoBars: episodes failed")
                emptyList()
            }
    }

    val it = item
    if (it == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) Text("Couldn't load this title.") else CircularProgressIndicator()
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(model = lib.backdrop(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
                TextButton(onClick = { nav.back() }, modifier = Modifier.padding(4.dp)) { Text("‹ Back", color = Color.White) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AsyncImage(
                    model = lib.poster(it),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (it.type == BaseItemKind.EPISODE) {
                        Text(it.seriesName.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(it.name.orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val facts =
                        listOfNotNull(
                            it.cardSubtitle()?.takeIf { _ -> it.type == BaseItemKind.EPISODE }?.substringBefore(" ·"),
                            it.productionYear?.toString(),
                            it.officialRating,
                            runtime(it.runTimeTicks),
                        )
                    Text(facts.joinToString("  ·  "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    it.genres?.takeIf { g -> g.isNotEmpty() }?.let { g -> Text(g.take(3).joinToString(", "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (it.type != BaseItemKind.SERIES) {
            item {
                val pos = it.userData?.playbackPositionTicks ?: 0L
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pos > 0) {
                        Button(onClick = { play(context, it.id, fromStart = false) }) { Text("▶ Resume from ${clock(pos / 10_000)}") }
                        OutlinedButton(onClick = { play(context, it.id, fromStart = true) }) { Text("From start") }
                    } else {
                        Button(onClick = { play(context, it.id, fromStart = true) }) { Text("▶ Play") }
                    }
                }
                it.progress()?.let { f -> Bar(f, Modifier.padding(horizontal = 16.dp)) }
            }
        }
        item { LibraryFacts(it, nbEntry) }
        it.overview?.let { text ->
            item { Text(text, modifier = Modifier.padding(16.dp), lineHeight = 20.sp) }
        }
        if (it.type == BaseItemKind.SERIES && seasons.isNotEmpty()) {
            item {
                PrimaryScrollableTabRow(selectedTabIndex = season.coerceIn(0, seasons.lastIndex), edgePadding = 12.dp) {
                    seasons.forEachIndexed { i, s ->
                        Tab(selected = i == season, onClick = { season = i }, text = { Text(s.name ?: "Season ${s.indexNumber}") })
                    }
                }
            }
            val eps = episodes
            if (eps == null) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            } else {
                items(eps, key = { e -> e.id }) { e -> EpisodeRow(e) { nav.open(Screen.Title(e.id)) } }
            }
        }
        nbSections(nbEntry, nbPage, nav, showStory = false)
        item { Box(Modifier.height(24.dp)) }
    }
}

/** Ratings with votes, 🍅, box office, where else it streams, and the Watchlist button. */
@Composable
private fun LibraryFacts(
    item: BaseItemDto,
    e: StreamingStore.Entry?,
) {
    val scope = rememberCoroutineScope()
    val v = ImdbVotesStore.get(item.id)
    val wanted by WantedStore.items.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val r = v?.rating ?: e?.imdbRating ?: item.communityRating
            r?.let { NbChip("IMDb " + rating(it) + votes(v?.votes ?: e?.imdbVotes), ImdbYellow, Color.Black) }
            (CriticFallback.label(item.id) ?: e?.rt?.let { "🍅 $it%" })?.let { NbChip(it, Tomato.copy(alpha = 0.85f), Color.White) }
            if ((r ?: 0f) >= 8f) NbChip("Rating 8+", Color(0xFF7E57C2).copy(alpha = 0.75f), Color.White)
        }
        BoxOffice.forItem(item.id)?.let { m -> BoxOffice.line(m)?.let { Text(it, fontSize = 14.sp) } }
        val f = StreamingStore.libraryFacts[item.id.toString().replace("-", "")]
        (f?.streamOn ?: e?.streamOn)?.let { Text("Also streaming  $it", fontSize = 13.sp, color = StreamGold) }
        (f?.freeOn ?: e?.freeOn)?.let { Text("Free  $it", fontSize = 13.sp, color = FreeOrange) }
        f?.collection?.let { Text("Collection  $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (e != null) {
            val onList = wanted.any { w -> w.tmdb == e.tmdb && w.series == e.series }
            OutlinedButton(onClick = { scope.launch { WantedStore.toggle(e, "phone") } }) { Text(if (onList) "✓ On Watchlist" else "+ Watchlist") }
        }
    }
}

@Composable
private fun EpisodeRow(
    e: BaseItemDto,
    onClick: () -> Unit,
) {
    val lib = NbJellyfin.current.library
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model = lib.wide(e), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (e.userData?.played == true) Tick(Modifier.align(Alignment.TopEnd))
            e.progress()?.let { f -> Bar(f, Modifier.align(Alignment.BottomCenter)) }
        }
        Column(Modifier.weight(1f)) {
            Text("${e.indexNumber ?: "?"}. ${e.name.orEmpty()}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            runtime(e.runTimeTicks)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            e.overview?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
