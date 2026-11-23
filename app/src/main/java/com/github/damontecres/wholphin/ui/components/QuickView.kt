package com.github.damontecres.wholphin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.services.HistoryStore
import com.github.damontecres.wholphin.services.ImdbVotesStore
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PosterFacts
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.services.WantedStore
import com.github.damontecres.wholphin.services.WatchProviders
import com.github.damontecres.wholphin.ui.detail.openTrailer
import com.github.damontecres.wholphin.ui.detail.shortService
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * NoBars: the quick view: OK on any poster that isn't in your library (Netflix / Prime / Theatre rows, Watchlist,
 * a person's career, "More like this") opens this pop-up instead of a whole page: what it is, its ratings and where it
 * streams, and the buttons you mostly want: Trailer · Netflix / Prime · Watchlist · Watched just now · Watched on
 * release · View details. Back closes it, on the same poster.
 */
object QuickView {
    data class Request(
        val tmdb: Int,
        val series: Boolean,
        val title: String,
        val poster: String?,
    )

    val request = MutableStateFlow<Request?>(null)

    fun open(
        tmdb: Int,
        series: Boolean,
        title: String,
        poster: String? = null,
    ) {
        request.value = Request(tmdb, series, title, poster)
    }

    fun open(e: StreamingStore.Entry) = open(e.tmdb, e.series, e.title, e.poster)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickViewHost(navigationManager: NavigationManager) {
    val req by QuickView.request.collectAsState()
    val r = req ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = (if (r.series) "tv:" else "movie:") + r.tmdb
    var entry by remember(r) { mutableStateOf(StreamingStore.cachedEntry(r.tmdb, r.series)) }
    LaunchedEffect(r) {
        StreamingStore.findEntry(r.tmdb, r.series)?.let { entry = it }
    }
    val factsVersion by PosterFacts.version.collectAsState()
    LaunchedEffect(r) {
        PosterFacts.load(listOf(key))
        HistoryStore.loadIfOld()
    }
    val facts = remember(key, factsVersion) { PosterFacts.get(key) }
    val history by HistoryStore.state.collectAsState()
    val wanted by WantedStore.items.collectAsState()
    val onList = wanted.any { it.tmdb == r.tmdb && it.series == r.series }
    val target = TraktMark.Target(key, entry?.title ?: r.title)
    val seen = history.items[key]
    var confirmRemove by remember(r) { mutableStateOf(false) }
    val first = remember(r, confirmRemove) { FocusRequester() }
    LaunchedEffect(r, confirmRemove) {
        repeat(20) {
            kotlinx.coroutines.delay(30)
            if (first.tryRequestFocus()) return@LaunchedEffect
        }
    }
    val close = { QuickView.request.value = null }
    val e = entry
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier =
                Modifier
                    .width(780.dp)
                    .background(Color(0xF2121212), RoundedCornerShape(14.dp))
                    .padding(24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                AsyncImage(
                    model = e?.poster ?: r.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(140.dp, 210.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(e?.title ?: r.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val line =
                        listOfNotNull(
                            (e?.year ?: facts?.year)?.toString(),
                            if (r.series) e?.seasons?.let { "$it season${if (it == 1) "" else "s"}" } else e?.runtime?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
                            e?.genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                        ).joinToString("  ·  ")
                    if (line.isNotBlank()) Text(line, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
                    // ratings: IMDb (the server's database, as on every poster) · box office · 🍅
                    val stars = facts?.rating ?: e?.imdbRating
                    val votes = facts?.votes ?: e?.imdbVotes
                    val money = facts?.money
                    val rt = facts?.rt ?: e?.rt
                    val ratings =
                        listOfNotNull(
                            stars?.let { "★ " + String.format(Locale.US, "%.1f", it) + (votes?.let { v -> "  " + ImdbVotesStore.short(v) } ?: "") },
                            money,
                            rt?.let { "🍅 $it%" },
                        ).joinToString("    ")
                    if (ratings.isNotBlank()) Text(ratings, fontSize = 15.sp, color = Color(0xFFF5C518))
                    // where it is, and what's yours
                    val status =
                        listOfNotNull(
                            e?.status?.takeIf { it.isNotBlank() },
                            if (onList) "🔖 On your watchlist" else null,
                            seen?.let { "👁 " + (watchedLine(it) ?: "Watched") },
                        )
                    status.forEach { Text(it, fontSize = 14.sp, color = Color(0xFFA5D6A7), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    e?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (confirmRemove) {
                Text(
                    if ((seen?.second ?: 1) > 1) "Remove all ${seen?.second} viewings from your Trakt history?" else "Remove it from your Trakt history?",
                    fontSize = 15.sp,
                    color = Color.White,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { confirmRemove = false }, modifier = Modifier.focusRequester(first)) { Text("Keep it") }
                    Button(onClick = {
                        TraktMark.remove(target)
                        confirmRemove = false
                    }) { Text("Remove") }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var firstUsed = false
                    fun Modifier.firstOne(): Modifier =
                        if (!firstUsed) {
                            firstUsed = true
                            this.focusRequester(first)
                        } else {
                            this
                        }
                    e?.trailer?.let { k -> Button(onClick = { openTrailer(context, k) }, modifier = Modifier.firstOne()) { Text("▶  Trailer") } }
                    if (e != null) {
                        val svc = (e.services + e.ott.map { o -> o.first }).firstOrNull { s -> WatchProviders.canOpen(s) }
                        if (svc != null) {
                            Button(
                                onClick = { WatchProviders.open(context, WatchProviders.Provider(0, svc, null), e.title, netflixId = e.netflixId) },
                                modifier = Modifier.firstOne(),
                            ) { Text("▶  " + shortService(svc)) }
                        }
                    }
                    if (e != null) {
                        Button(onClick = { scope.launch { WantedStore.toggle(e) } }, modifier = Modifier.firstOne()) {
                            Text(if (onList) "🔖  On watchlist ✓" else "＋  Watchlist")
                        }
                    }
                    Button(onClick = { TraktMark.mark(target, "now") }, modifier = Modifier.firstOne()) { Text("👁  Watched just now") }
                    releaseLabel(target)?.let { label ->
                        Button(onClick = { TraktMark.mark(target, "released") }) { Text("👁  $label") }
                    }
                    if (seen != null) Button(onClick = { confirmRemove = true }) { Text("Remove from history…") }
                    Button(onClick = {
                        close()
                        navigationManager.navigateTo(Destination.CatalogTitle(r.tmdb, r.series))
                    }) { Text("View details  →") }
                }
            }
        }
    }
}
