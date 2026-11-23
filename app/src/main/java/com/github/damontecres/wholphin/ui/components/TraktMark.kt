package com.github.damontecres.wholphin.ui.components

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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.github.damontecres.wholphin.services.HistoryStore
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

/** The dialog for "Mark watched…" (shown over any page; placed once in the app's root). */
@Composable
fun TraktMarkHost() {
    val target by TraktMark.request.collectAsState()
    val t = target ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by HistoryStore.state.collectAsState()
    var step by remember(t) { mutableStateOf("when") }
    var day by remember(t) { mutableStateOf(LocalDate.now()) }
    var busy by remember(t) { mutableStateOf(false) }
    val first = remember(t, step) { FocusRequester() }
    LaunchedEffect(t, step) { first.tryRequestFocus() }
    val inHistory = TraktMark.watched(t)
    val plays = if (t.season == null) history.items[t.key]?.second ?: 0 else 0
    val lastDay = if (t.season == null) history.items[t.key]?.first else null

    fun done(ok: Boolean, what: String) {
        busy = false
        Toast.makeText(context, if (ok) what else "Trakt didn't take it. Try again in a moment.", Toast.LENGTH_SHORT).show()
        if (ok) TraktMark.request.value = null
    }

    fun mark(whenWatched: String) {
        if (busy) return
        busy = true
        scope.launch { done(HistoryStore.mark(t.key, whenWatched, t.season, t.episode), "Added to your Trakt history") }
    }

    Dialog(onDismissRequest = { TraktMark.request.value = null }) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .width(460.dp)
                    .background(Color(0xF0141414), RoundedCornerShape(12.dp))
                    .padding(20.dp),
        ) {
            Text(
                if (step == "remove") "Remove from your history?" else "Mark watched",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(t.title, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2)
            if (inHistory) {
                val d = lastDay?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)) }.getOrNull() }
                Text(
                    "In your history" + (d?.let { ": watched $it" } ?: "") + (if (plays > 1) " · $plays×" else ""),
                    fontSize = 13.sp,
                    color = Color(0xFF9BE59B),
                )
            }
            when (step) {
                "when" -> {
                    if (inHistory) Text("Watched again? Add another viewing:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    Button(onClick = { mark("now") }, modifier = Modifier.focusRequester(first)) { Text("Just now") }
                    Button(onClick = { mark("released") }) { Text("On its release date") }
                    Button(onClick = { step = "pick" }) { Text("Pick a date…") }
                    Button(onClick = { mark("unknown") }) { Text("Unknown date") }
                    if (inHistory) {
                        Button(onClick = { step = "remove" }) { Text("Remove from history…") }
                    }
                }
                "pick" -> {
                    @Composable
                    fun adjuster(
                        label: String,
                        value: String,
                        minus: () -> Unit,
                        plus: () -> Unit,
                        focus: Boolean = false,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(label, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(70.dp))
                            Button(onClick = minus, modifier = if (focus) Modifier.focusRequester(first) else Modifier) { Text("−") }
                            Text(value, color = Color.White, fontSize = 18.sp, modifier = Modifier.width(110.dp))
                            Button(onClick = plus) { Text("+") }
                        }
                    }
                    val today = LocalDate.now()
                    adjuster("Day", day.dayOfMonth.toString(), { day = day.minusDays(1) }, { if (day < today) day = day.plusDays(1) }, focus = true)
                    adjuster("Month", day.format(DateTimeFormatter.ofPattern("MMMM", Locale.UK)), { day = day.minusMonths(1) }, { day = minOf(today, day.plusMonths(1)) })
                    adjuster("Year", day.year.toString(), { day = day.minusYears(1) }, { day = minOf(today, day.plusYears(1)) })
                    Button(onClick = { mark(day.toString()) }) {
                        Text("Watched on " + day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)))
                    }
                    Button(onClick = { step = "when" }) { Text("Back") }
                }
                "remove" -> {
                    Text(
                        if (plays > 1) "This removes all $plays viewings from your Trakt history." else "This removes it from your Trakt history.",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Button(onClick = { step = "when" }, modifier = Modifier.focusRequester(first)) { Text("Keep it") }
                    Button(onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch { done(HistoryStore.unmark(t.key, t.season, t.episode), "Removed from your Trakt history") }
                        }
                    }) { Text("Remove") }
                }
            }
            if (busy) Text("Sending to Trakt…", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
