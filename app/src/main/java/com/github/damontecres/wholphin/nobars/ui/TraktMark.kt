package com.github.damontecres.wholphin.nobars.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.nobars.data.HistoryStore
import com.github.damontecres.wholphin.nobars.data.StreamingStore
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * NoBars: on the TV, "watched" means your Trakt watch history. Any poster's menu and every title page has
 * "Mark watched…": pick when (just now, its release date, a day, or unknown) and it goes straight into your Trakt
 * history; once it's there, "Remove from history" too. Jellyfin's own played record isn't used for this.
 */
object TraktMark {
    /** What is being marked: a title ("movie:123" / "tv:456"), or a season / an episode of a series. */
    data class Target(
        val key: String,
        val title: String,
        val season: Int? = null,
        val episode: Int? = null,
        /** open the dialog straight at "Remove it from your history?" */
        val confirmRemove: Boolean = false,
    )

    val request = MutableStateFlow<Target?>(null)

    fun ask(target: Target) {
        request.value = target
    }

    /** The target for a library item (film, series, season or episode), or null for anything else. */
    fun targetFor(item: BaseItem): Target? {
        val dto = item.data
        fun seriesKey(): String? =
            dto.seriesId?.toString()?.replace("-", "")?.let { hex ->
                StreamingStore.owned.entries.firstOrNull { it.value == hex && it.key.startsWith("tv:") }?.key
            }
        return when (dto.type) {
            BaseItemKind.MOVIE -> dto.providerIds?.get("Tmdb")?.takeIf { it.isNotBlank() }?.let { Target("movie:$it", item.title ?: dto.name ?: "") }
            BaseItemKind.SERIES -> dto.providerIds?.get("Tmdb")?.takeIf { it.isNotBlank() }?.let { Target("tv:$it", item.title ?: dto.name ?: "") }
            BaseItemKind.SEASON -> seriesKey()?.let { Target(it, (dto.seriesName ?: "") + " · " + (dto.name ?: "Season ${dto.indexNumber}"), season = dto.indexNumber ?: return null) }
            BaseItemKind.EPISODE ->
                seriesKey()?.let {
                    val s = dto.parentIndexNumber ?: return null
                    val e = dto.indexNumber ?: return null
                    Target(it, (dto.seriesName ?: "") + " · S$s E$e", season = s, episode = e)
                }
            else -> null
        }
    }

    fun ask(item: BaseItem): Boolean = targetFor(item)?.also(::ask) != null

    fun watched(target: Target?): Boolean = target != null && HistoryStore.watched(target.key, target.season, target.episode)

    /** For messages from places without a screen context (poster menus). Set by the app's root. */
    @Volatile var appContext: android.content.Context? = null

    /** Mark it watched (now / released): the tick at once, Trakt in the background, a message only if it fails. */
    fun mark(
        target: Target,
        whenWatched: String,
    ) {
        appContext?.let { markWatched(it, target, whenWatched) }
            ?: HistoryStore.markInBackground(target.key, whenWatched, target.season, target.episode) {}
    }

    fun remove(target: Target) {
        appContext?.let { removeWatched(it, target) } ?: HistoryStore.removeInBackground(target.key, target.season, target.episode) {}
    }
}

/** Whether a library poster's item is in your Trakt history (null: not a film, series, season or episode). */
@Composable
fun traktWatched(item: BaseItem?): Boolean? {
    item ?: return null
    val history by HistoryStore.state.collectAsState()
    LaunchedEffect(Unit) { HistoryStore.loadIfOld() }
    val t = remember(item.id, StreamingStore.owned.size) { TraktMark.targetFor(item) } ?: return null
    return when {
        t.season == null -> t.key in history.items
        t.episode == null -> history.episodes[t.key].orEmpty().keys.any { it.startsWith("${t.season}x") }
        else -> "${t.season}x${t.episode}" in history.episodes[t.key].orEmpty()
    }
}

/** "On release (16 Jan 2026)" / "On its air date": the label for the second choice; null when it isn't out yet. */
@Composable
fun releaseLabel(target: TraktMark.Target): String? {
    if (target.season != null) return if (target.episode != null) "Watched on its air date" else "Watched on air dates"
    val factsVersion by com.github.damontecres.wholphin.nobars.data.PosterFacts.version.collectAsState()
    LaunchedEffect(target.key) { com.github.damontecres.wholphin.nobars.data.PosterFacts.load(listOf(target.key)) }
    val day = remember(target.key, factsVersion) { com.github.damontecres.wholphin.nobars.data.PosterFacts.get(target.key)?.releaseDate }
    return when {
        day == null -> null
        day > LocalDate.now().toString() -> null
        else -> "Watched on release (" + LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)) + ")"
    }
}

/** Send a mark to Trakt: the tick shows at once; a message only if Trakt doesn't take it. */
fun markWatched(
    context: android.content.Context,
    target: TraktMark.Target,
    whenWatched: String,
) {
    HistoryStore.markInBackground(target.key, whenWatched, target.season, target.episode) {
        Toast.makeText(context, "Trakt didn't take it" + (if (whenWatched == "released") " (no release date yet)" else "") + ". Try again in a moment.", Toast.LENGTH_SHORT).show()
    }
}

fun removeWatched(
    context: android.content.Context,
    target: TraktMark.Target,
) {
    HistoryStore.removeInBackground(target.key, target.season, target.episode) {
        Toast.makeText(context, "Trakt didn't remove it. Try again in a moment.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * The small dialog for the watched button on library pages: Watched just now · Watched on release (date) · Remove from
 * history (with a check first). Each choice closes it at once; Trakt gets it in the background.
 */
@Composable
fun TraktMarkHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TraktMark.appContext = context.applicationContext }
    val target by TraktMark.request.collectAsState()
    val t = target ?: return
    val history by HistoryStore.state.collectAsState()
    var confirmRemove by remember(t) { mutableStateOf(t.confirmRemove) }
    val first = remember(t, confirmRemove) { FocusRequester() }
    LaunchedEffect(t, confirmRemove) { first.tryRequestFocus() }
    val inHistory = TraktMark.watched(t)
    val plays = if (t.season == null) history.items[t.key]?.second ?: 0 else 0
    val release = releaseLabel(t)
    val close = { TraktMark.request.value = null }
    Dialog(onDismissRequest = close) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .width(460.dp)
                    .background(Color(0xF0141414), RoundedCornerShape(12.dp))
                    .padding(20.dp),
        ) {
            Text(t.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2)
            if (inHistory) {
                Text(watchedLine(history.items[t.key]) ?: "In your history", fontSize = 13.sp, color = Color(0xFF9BE59B))
            }
            if (!confirmRemove) {
                Button(onClick = {
                    markWatched(context, t, "now")
                    close()
                }, modifier = Modifier.focusRequester(first)) { Text("👁  Watched just now") }
                release?.let {
                    Button(onClick = {
                        markWatched(context, t, "released")
                        close()
                    }) { Text("👁  $it") }
                }
                if (inHistory) Button(onClick = { confirmRemove = true }) { Text("Remove from history…") }
            } else {
                Text(
                    if (plays > 1) "Remove all $plays viewings from your Trakt history?" else "Remove it from your Trakt history?",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Button(onClick = { if (t.confirmRemove) close() else confirmRemove = false }, modifier = Modifier.focusRequester(first)) { Text("Keep it") }
                Button(onClick = {
                    removeWatched(context, t)
                    close()
                }) { Text("Remove") }
            }
        }
    }
}

/** "Watched 12 Mar 2019 · 2×" (or just "Watched" when the date isn't known). */
fun watchedLine(entry: Pair<String, Int>?): String? {
    entry ?: return null
    val (date, plays) = entry
    val d = date.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)) }.getOrNull() }
    return listOfNotNull(d?.let { "Watched $it" } ?: "Watched", if (plays > 1) "$plays×" else null).joinToString(" · ")
}
