package com.github.damontecres.wholphin.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.Person
import com.github.damontecres.wholphin.services.BoxOffice
import com.github.damontecres.wholphin.services.ImdbVotesStore
import com.github.damontecres.wholphin.services.ServerAccess
import com.github.damontecres.wholphin.services.StreamingStore
import com.github.damontecres.wholphin.services.WantedStore
import com.github.damontecres.wholphin.services.WatchProviders
import com.github.damontecres.wholphin.ui.cards.BannerCard
import com.github.damontecres.wholphin.ui.cards.PersonRow
import com.github.damontecres.wholphin.ui.components.HeaderUtils
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.NbChip
import com.github.damontecres.wholphin.ui.components.NbJourneyAcross
import com.github.damontecres.wholphin.ui.components.NbSection
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.tryRequestFocus
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PersonKind
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * NoBars: the page of a Netflix / Prime Video / Theatre / Watchlist title you don't have, laid out like a film's
 * page: header (meta, rating chips, box office, where to watch, story, buttons), then Journey, Box office and Where
 * else to watch, Cast with photos, Crew, Trailers and clips, More like this, About. Back goes back like any page.
 * (Titles you have open their library page instead.)
 */
@Composable
fun CatalogTitlePage(
    tmdb: Int,
    series: Boolean,
    modifier: Modifier = Modifier,
    viewModel: StreamingViewModel = hiltViewModel(),
) {
    val cached = remember(tmdb, series) { StreamingStore.cachedEntry(tmdb, series) }
    var entry by remember(tmdb, series) { mutableStateOf(cached) }
    var page by remember(tmdb, series) { mutableStateOf<StreamingStore.TitlePage?>(null) }
    var searched by remember(tmdb, series) { mutableStateOf(cached != null) }
    LaunchedEffect(tmdb, series) {
        val e = cached ?: StreamingStore.findEntry(tmdb, series)
        if (e != entry) entry = e
        searched = true
        e?.let { viewModel.updateBackdrop(StreamingStore.toBaseItem(it, "page")) }
        val started = System.currentTimeMillis()
        page = StreamingStore.titlePage(tmdb, series)
        timber.log.Timber.i("NoBars timing: title page details in %d ms", System.currentTimeMillis() - started)
    }
    val e = entry
    when {
        e == null && !searched -> LoadingPage(modifier)
        e == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This title isn't in today's lists any more.") }
        else -> CatalogTitleContent(e, page, viewModel, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CatalogTitleContent(
    e: StreamingStore.Entry,
    page: StreamingStore.TitlePage?,
    viewModel: StreamingViewModel,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // focus the first button in the same frame the page appears (no stop on the menu); retry only if it missed
    LaunchedEffect(e.tmdb, e.series) {
        if (first.tryRequestFocus()) return@LaunchedEffect
        repeat(20) {
            kotlinx.coroutines.delay(30)
            if (first.tryRequestFocus()) return@LaunchedEffect
        }
    }
    val wanted by WantedStore.items.collectAsState()
    val onList = wanted.any { it.tmdb == e.tmdb && it.series == e.series }
    val added = wanted.firstOrNull { it.tmdb == e.tmdb && it.series == e.series }?.added
    val openSvc = (e.services + e.ott.map { it.first }).firstOrNull { WatchProviders.canOpen(it) }
        ?: e.from?.takeIf { it == StreamingStore.NETFLIX || it == StreamingStore.PRIME }?.let { StreamingStore.displayName(it) }
    // NoBars: scroll only as much as needed to show what has the focus (the TV's default moves it to a third of the
    // way down, which pushed the title off the top), and keep the whole header in view while its buttons have focus
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalScroll,
    ) {
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // ---- header
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth(.66f)
                        .padding(start = HeaderUtils.startPadding + 8.dp, top = HeaderUtils.topPadding)
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } },
            ) {
                Text(e.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta =
                    listOfNotNull(
                        e.year?.toString(),
                        if (e.series) {
                            e.seasons?.let { "$it season${if (it == 1) "" else "s"}" + (page?.episodes?.let { n -> " · $n episodes" } ?: "") }
                        } else {
                            e.runtime?.let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }
                        },
                        page?.cert,
                        e.genres.take(3).joinToString(", ").ifBlank { null },
                    ).joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val votes = { v: Int? -> v?.let { " · ${ImdbVotesStore.short(it)}" } ?: "" }
                    e.imdbRating?.let { r -> NbChip("IMDb " + String.format(Locale.US, "%.1f", r) + votes(e.imdbVotes), Color(0xFFF5C518), Color.Black) }
                        ?: e.tmdbRating?.let { r -> NbChip("TMDB " + String.format(Locale.US, "%.1f", r) + votes(e.tmdbVotes), Color(0xFF01B4E4), Color.Black) }
                    e.rt?.let { NbChip("🍅 $it%", Color(0xCCFA320A), Color.White) }
                        ?: e.pop?.let { NbChip("🍿 $it% audience", Color.White.copy(alpha = 0.15f), Color.White) }
                    if ((e.imdbRating ?: 0f) >= 8f) NbChip("Rating 8+", Color(0xFF7E57C2).copy(alpha = 0.75f), Color.White)
                    if (e.rank != null && e.note != null) NbChip("#${e.rank}" + (e.move?.takeIf { it != "=" }?.let { " $it" } ?: ""), Color(0xFFE50914), Color.White)
                }
                BoxOffice.forItem(StreamingStore.toBaseItem(e, "page").id)?.let { m ->
                    BoxOffice.line(m)?.let { Text(it, fontSize = 15.sp, color = Color.White) }
                }
                // where to watch, instead of a library film's file chips
                val where =
                    listOfNotNull(
                        e.status,
                        (e.services + e.ott.map { it.first }).distinct().take(3).joinToString(" · ").ifBlank { null },
                        e.date,
                    ).firstOrNull()
                where?.let { Text(it, fontSize = 14.sp, color = if (it.contains("No longer")) Color(0xFFEF9A9A) else Color(0xFFA5D6A7)) }
                page?.tagline?.let { Text(it, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.8f)) }
                if (e.overview.isNotBlank()) {
                    Text(e.overview, fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    var firstUsed = false
                    fun Modifier.firstOne(): Modifier =
                        if (!firstUsed) {
                            firstUsed = true
                            this.focusRequester(first)
                        } else {
                            this
                        }
                    e.trailer?.let { key -> Button(onClick = { openTrailer(context, key) }, modifier = Modifier.firstOne()) { Text("▶  Trailer") } }
                    openSvc?.let { svc ->
                        Button(
                            onClick = { WatchProviders.open(context, WatchProviders.Provider(0, svc, null), e.title, netflixId = e.netflixId) },
                            modifier = Modifier.firstOne(),
                        ) { Text("▶  " + shortService(svc)) }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val now = WantedStore.toggle(e)
                                android.widget.Toast
                                    .makeText(context, if (now) "Added to your Watchlist" else "Removed from your Watchlist", android.widget.Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                        modifier = Modifier.firstOne(),
                    ) { Text(if (onList) "🔖  On Watchlist" else "＋  Watchlist") }
                }
            }
        }
        // ---- journey
        val steps =
            e.journey +
                listOfNotNull(added?.let { StreamingStore.JourneyStep("you", "Added to your Watchlist", it, e.from?.let { f -> "from ${StreamingStore.displayName(f)}" }) })
        if (steps.isNotEmpty()) {
            item { NbSection("Journey") { NbJourneyAcross(steps.sortedBy { it.date ?: "0" }) } }
        }
        // ---- box office and where else to watch
        val hasMoney = e.gross != null || e.budget != null
        val hasWhere = e.streamOn != null || e.freeOn != null || e.rentBuy != null
        if (hasMoney || hasWhere) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = HeaderUtils.startPadding + 8.dp),
                ) {
                    if (hasMoney) {
                        Box(Modifier.weight(1f)) {
                            NbSection("Box office", inset = false) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val m = BoxOffice.Money(e.gross, e.budget, e.year, e.grossToday, grossLocal = e.grossLocal)
                                    BoxOffice.line(m)?.let { Text(it, fontSize = 15.sp, color = Color.White) }
                                    BoxOffice.detailLine(m)?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)) }
                                    e.facts?.let { Text(it, fontSize = 13.sp, color = Color(0xFFA5D6A7)) }
                                }
                            }
                        }
                    }
                    if (hasWhere) {
                        Box(Modifier.weight(1f)) {
                            NbSection("Where to watch", inset = false) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    e.streamOn?.let { Text("Streaming  $it", fontSize = 14.sp, color = Color(0xFFFFE082)) }
                                    e.freeOn?.let { Text("Free  $it", fontSize = 14.sp, color = Color(0xFFFFCC80)) }
                                    e.rentBuy?.let { Text("Rent or buy  $it", fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // ---- cast with photos (pressing one opens their page in your library, when they're in it)
        val cast = page?.cast.orEmpty()
        if (cast.isNotEmpty()) {
            item {
                val people =
                    remember(cast) {
                        cast.map { (name, role, photo) ->
                            Person(
                                id = UUID.nameUUIDFromBytes("cast-$name".toByteArray()),
                                name = name,
                                role = role,
                                type = PersonKind.ACTOR,
                                imageUrl = photo,
                                favorite = false,
                            )
                        }
                    }
                PersonRow(
                    people = people,
                    title = com.github.damontecres.wholphin.R.string.nb_cast,
                    onClick = { person ->
                        scope.launch {
                            val id = findLibraryPerson(person.name ?: "")
                            if (id != null) {
                                viewModel.navigationManager.navigateTo(Destination.MediaItem(id, BaseItemKind.PERSON))
                            } else {
                                android.widget.Toast.makeText(context, "${person.name} isn't in your library", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = HeaderUtils.startPadding),
                )
            }
        } else if (e.cast != null) {
            item { NbSection("Cast") { Text("With ${e.cast}", fontSize = 14.sp, color = Color.White) } }
        }
        // ---- crew
        val crew = page?.crew.orEmpty()
        if (crew.isNotEmpty()) {
            item {
                NbSection("Crew") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        crew.forEach { (job, names) ->
                            Column(Modifier.width(260.dp)) {
                                Text(job, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                                Text(names.joinToString(", "), fontSize = 14.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        // ---- trailers and clips
        val videos = page?.videos.orEmpty()
        if (videos.size > 1) {
            item {
                Column(Modifier.padding(start = HeaderUtils.startPadding + 8.dp)) {
                    Text("Trailers and clips", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(videos) { (name, key, date) ->
                            Button(onClick = { openTrailer(context, key) }) {
                                Text("▶  " + name.take(40) + (date?.let { "  ·  " + it.take(4) } ?: ""), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        // ---- more like this (only titles you can open)
        val like = page?.like.orEmpty()
        if (like.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = HeaderUtils.startPadding + 8.dp)) {
                    Text("More like this", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(like) { other ->
                            val item = remember(other) { StreamingStore.toBaseItem(other, "like") }
                            BannerCard(
                                name = other.title,
                                item = item,
                                onClick = {
                                    val lib = other.libraryId
                                    viewModel.navigationManager.navigateTo(
                                        if (lib != null) {
                                            Destination.MediaItem(lib, if (other.series) BaseItemKind.SERIES else BaseItemKind.MOVIE)
                                        } else {
                                            Destination.CatalogTitle(other.tmdb, other.series)
                                        },
                                    )
                                },
                                onLongClick = {},
                                aspectRatio = com.github.damontecres.wholphin.ui.AspectRatios.TALL,
                                cardHeight = 200.dp,
                            )
                        }
                    }
                }
            }
        }
        // ---- about
        val about =
            buildList<Pair<String, String>> {
                page?.originalTitle?.let { add("Original title" to it) }
                page?.language?.let { add("Language" to it) }
                page?.countries?.takeIf { it.isNotEmpty() }?.let { add("Country" to it.joinToString(", ")) }
                page?.studios?.takeIf { it.isNotEmpty() }?.let { add("Studio" to it.joinToString(", ")) }
                page?.networks?.takeIf { it.isNotEmpty() }?.let { add("Network" to it.joinToString(", ")) }
                page?.collection?.let { add("Collection" to it) }
                page?.status?.let { add("Status" to it) }
                if (e.genres.isNotEmpty()) add("Genres" to e.genres.joinToString(", "))
                page?.keywords?.takeIf { it.isNotEmpty() }?.let { add("Keywords" to it.take(8).joinToString(", ")) }
                e.note?.let { add("Chart" to it) }
                added?.let { add("On your Watchlist" to "since " + com.github.damontecres.wholphin.ui.components.nbDate(it)) }
            }
        if (about.isNotEmpty()) {
            item {
                NbSection(if (e.series) "About this show" else "About this film") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        about.forEach { (label, value) ->
                            Column(Modifier.width(260.dp)) {
                                Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
                                Text(value, fontSize = 14.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (page == null) {
            item {
                Text(
                    "Cast, crew and more appear here once the morning update has prepared this title.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(start = HeaderUtils.startPadding + 8.dp),
                )
            }
        }
    }
    }
}

/** Scrolls only when the focused thing is (partly) off screen, and only as far as needed. */
private object MinimalScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val end = offset + size
        return when {
            offset >= 0f && end <= containerSize -> 0f
            offset < 0f || size > containerSize -> offset
            else -> end - containerSize
        }
    }
}

/** The person's id in your library (exact name), or null. */
private suspend fun findLibraryPerson(name: String): UUID? =
    kotlinx.coroutines.withContext(com.github.damontecres.wholphin.util.WholphinDispatchers.IO) {
        try {
            val user = ServerAccess.userId ?: return@withContext null
            val q = java.net.URLEncoder.encode(name, "UTF-8")
            val o = JSONObject(ServerAccess.get("/Persons?userId=$user&searchTerm=$q&limit=5") ?: return@withContext null)
            val items = o.optJSONArray("Items") ?: return@withContext null
            (0 until items.length())
                .mapNotNull { items.optJSONObject(it) }
                .firstOrNull { it.optString("Name").equals(name, true) }
                ?.optString("Id")
                ?.replace("-", "")
                ?.takeIf { it.length == 32 }
                ?.let { UUID.fromString("${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}") }
        } catch (_: Exception) {
            null
        }
    }
