package com.github.damontecres.wholphin.nobars.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.nobars.data.PosterFacts
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.nobars.data.WantedStore
import com.github.damontecres.wholphin.nobars.data.WatchProviders
import com.github.damontecres.wholphin.nobars.catalog.openTrailer
import com.github.damontecres.wholphin.nobars.catalog.shortService
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * NoBars: one fixed block of buttons for a title, the same in the quick view and at the top of the title page:
 *
 *   ▶ Trailer · ▶ Netflix / Prime · Watchlist · View details →
 *   👁 Watched just now · 👁 On release · Remove from history
 *
 * Every button is always there, at the same size and in the same place. One whose link isn't known yet (or doesn't
 * exist) is shown dimmed and does nothing; when the card arrives it lights up where it is, so nothing moves.
 */
@Composable
fun TitleActions(
    tmdb: Int,
    series: Boolean,
    title: String,
    entry: StreamingStore.Entry?,
    loading: Boolean,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
    width: Dp = 732.dp,
    poster: String? = null,
    onDetails: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = (if (series) "tv:" else "movie:") + tmdb
    val target = TraktMark.Target(key, entry?.title ?: title)
    val factsVersion by PosterFacts.version.collectAsState()
    LaunchedEffect(key) {
        PosterFacts.load(listOf(key))
        HistoryStore.loadIfOld()
    }
    val facts = remember(key, factsVersion) { PosterFacts.get(key) }
    val history by HistoryStore.state.collectAsState()
    val wanted by WantedStore.items.collectAsState()
    val onList = wanted.any { it.tmdb == tmdb && it.series == series }
    val seen = history.items[key]

    val trailer = entry?.trailer
    val svc = entry?.let { e -> (e.services + e.ott.map { it.first }).firstOrNull { WatchProviders.canOpen(it) } }
    val today = LocalDate.now().toString()
    val release = facts?.releaseDate
    val released = release != null && release <= today
    val releaseText =
        when {
            release == null && facts == null -> "👁  On release"
            release == null -> "No release date"
            !released -> "Not released yet"
            else -> "👁  On release · " + LocalDate.parse(release).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier.width(width)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Slot(
                text = if (trailer != null || loading) "▶  Trailer" else "No trailer",
                active = trailer != null,
                modifier = Modifier.weight(1f).focusRequester(firstFocus),
            ) { trailer?.let { openTrailer(context, it) } }
            Slot(
                text = svc?.let { "▶  " + shortService(it) } ?: if (loading) "▶  Watch" else "Not on your services",
                active = svc != null,
                modifier = Modifier.weight(1f),
            ) {
                val e = entry
                if (svc != null && e != null) WatchProviders.open(context, WatchProviders.Provider(0, svc, null), e.title, netflixId = e.netflixId)
            }
            Slot(
                text = if (onList) "🔖  On watchlist ✓" else "＋  Watchlist",
                active = true,
                modifier = Modifier.weight(1f),
            ) {
                val listEntry =
                    entry ?: StreamingStore.Entry(
                        tmdb = tmdb,
                        series = series,
                        title = title,
                        year = facts?.year,
                        poster = poster,
                        overview = "",
                        genres = emptyList(),
                        runtime = null,
                        seasons = null,
                        imdbRating = null,
                        imdbVotes = null,
                        libraryId = null,
                        rank = null,
                    )
                scope.launch {
                    val now = WantedStore.toggle(listEntry)
                    Toast.makeText(context, if (now) "Added to your Watchlist" else "Removed from your Watchlist", Toast.LENGTH_SHORT).show()
                }
            }
            if (onDetails != null) {
                Slot(text = "View details  →", active = true, modifier = Modifier.weight(1f)) { onDetails() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Slot(text = "👁  Watched just now", active = true, modifier = Modifier.weight(1f)) { TraktMark.mark(target, "now") }
            Slot(text = releaseText, active = released, modifier = Modifier.weight(1f)) {
                if (released) TraktMark.mark(target, "released")
            }
            Slot(
                text = if (seen != null) "Remove from history…" else "Not in your history",
                active = seen != null,
                modifier = Modifier.weight(1f),
            ) {
                if (seen != null) (onRemove ?: { TraktMark.ask(target.copy(confirmRemove = true)) })()
            }
        }
    }
}

/** One fixed button: always focusable; dimmed (and does nothing) when it isn't active. */
@Composable
private fun Slot(
    text: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = modifier) {
        Text(
            text,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            color = if (active) Color.Unspecified else Color.Gray.copy(alpha = 0.7f),
        )
    }
}
